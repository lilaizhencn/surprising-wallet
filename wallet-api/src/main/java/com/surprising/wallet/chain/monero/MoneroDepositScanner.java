package com.surprising.wallet.chain.monero;

import com.surprising.wallet.common.chain.ChainScanPoint;
import com.surprising.wallet.chain.signing.MpcNodes;
import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.chain.DepositEvent;
import com.surprising.wallet.repository.*;
import com.surprising.wallet.service.DepositService;
import com.surprising.wallet.service.MoneroScanAnchorService;
import com.surprising.wallet.service.MoneroSubmissionService;
import com.surprising.wallet.service.MpcSigningService;
import com.surprising.wallet.service.WalletRuntimeConfigService;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Two native holders identify exact outputs; canonical headers, credits and the cursor commit together. */
@Service
@RequiredArgsConstructor
public class MoneroDepositScanner {
    private static final String CHAIN="XMR",SCANNER=MoneroScanAnchorService.SCANNER;
    private final MoneroDaemonClient daemon;
    private final MpcSigningService signing;
    private final MoneroSubmissionService submissions;
    private final MpcNodes nodes;
    private final ChainProfileRepository profiles;
    private final WalletMpcKeyEpochRepository epochs;
    private final ChainAddressRepository addresses;
    private final ChainScanHeightRepository heights;
    private final ChainScanBlockRepository blocks;
    private final DepositRecordRepository records;
    private final DepositService deposits;
    private final PlatformTransactionManager manager;
    private final ObjectMapper json;
    private final WalletRuntimeConfigService runtime;

    /** Reorg checks remain available when a chain, tenant or new scan work is disabled. */
    public int reconcileObserved() {
        var state=heights.nativeState(CHAIN,SCANNER,false);if(state.isEmpty())return 0;
        var profile=observedProfile();var network=daemon.network(profile.getNetwork());
        require(network.genesisHash().equals(state.get().genesisHash()),"Monero scanner native network changed");
        var candidates=blocks.recentNative(CHAIN,SCANNER,1000);
        require(!candidates.isEmpty() && candidates.getFirst().point().height()==state.get().safeHeight(),"Monero cursor has no stored canonical header");
        ChainScanPoint common=null;
        for(var candidate:candidates) {
            if(candidate.point().height()>=network.height())continue;
            if(daemon.header(profile.getNetwork(),candidate.point().height()).hash().equals(candidate.point().hash())) {common=candidate.point();break;}
        }
        require(common!=null,"Monero fork has no stored canonical intersection");
        if(common.equals(candidates.getFirst().point()))return 0;
        var intersection=common;
        return Objects.requireNonNull(new TransactionTemplate(manager).execute(status->{
            var locked=heights.nativeState(CHAIN,SCANNER,true).orElseThrow();
            if(locked.safeHeight()!=state.get().safeHeight())return 0;
            require(locked.genesisHash().equals(network.genesisHash())
                    && blocks.nativePoint(CHAIN,SCANNER,locked.safeHeight()).orElseThrow().point().equals(candidates.getFirst().point())
                    && blocks.nativePoint(CHAIN,SCANNER,intersection.height()).orElseThrow().point().equals(intersection),"Monero reorg cursor changed");
            int reversed=deposits.rollbackAbove(CHAIN,intersection.height(),intersection.hash());
            blocks.removeAbove(CHAIN,SCANNER,intersection.height());
            require(heights.advanceNative(CHAIN,SCANNER,locked.genesisHash(),network.height()-1,locked.safeHeight(),intersection.height())==1,
                    "Monero reorg cursor raced");
            return reversed;
        }));
    }

    /** Refresh already detected output locks without depending on new work or tenant switches. */
    public int confirmObserved() {
        reconcileObserved();var pending=records.listPendingMaturity(CHAIN,500);if(pending.isEmpty())return 0;
        var profile=observedProfile();var tracked=tracked();
        Map<PendingBlock,List<DepositRecordRepository.PendingRecord>> groups=new LinkedHashMap<>();
        for(var row:pending)groups.computeIfAbsent(new PendingBlock(row.toAddress(),row.blockHeight()),ignored->new ArrayList<>()).add(row);
        int credited=0;
        for(var group:groups.entrySet()) {
            var owner=tracked.stream().filter(row->row.getAddress().equals(group.getKey().address())).findFirst().orElseThrow();
            var state=heights.nativeState(CHAIN,SCANNER,false).orElseThrow();
            var cursor=blocks.nativePoint(CHAIN,SCANNER,state.safeHeight()).orElseThrow().point();
            var header=daemon.header(profile.getNetwork(),group.getKey().height());
            require(group.getValue().stream().allMatch(row->row.blockHash().equals(header.hash())),"Monero pending output needs canonical reconciliation");
            var snapshot=observe(owner,profile.getNetwork(),state.genesisHash(),header,header.height());
            credited+=Objects.requireNonNull(new TransactionTemplate(manager).execute(status->{
                lockCursor(state,cursor);requireSameOwner(owner);
                require(blocks.nativePoint(CHAIN,SCANNER,header.height()).orElseThrow().point().hash().equals(header.hash()),"Monero pending output left the canonical cursor");
                int result=0;
                for(var row:group.getValue()) {
                    var output=findOutput(snapshot,row.txHash(),row.logIndex());
                    require(!output.path("change").asBoolean() && amount(output).compareTo(row.amount())==0,
                            "Monero pending output changed its original amount or payment identity");
                    var prior=json.readTree(row.rawPayload()).path("output");
                    require(prior.path("outputKey").equals(output.path("outputKey")),"Monero pending output changed its original public key");
                    if(credit(owner,output,snapshot,required(profile)))result++;
                }
                return result;
            }));
        }
        return credited;
    }

    public List<DepositEvent> scanAndCredit(AccountChainProfile profile) {
        runtime.requireTaskEnabled(CHAIN,WalletRuntimeConfigService.TASK_SCAN,"Monero scanAndCredit");
        require(profile!=null && CHAIN.equals(profile.getChain()),"Monero profile is required");
        reconcileObserved();var state=heights.nativeState(CHAIN,SCANNER,false);var tracked=tracked();
        if(state.isEmpty()) {require(tracked.isEmpty(),"Monero published address has no native anchor");return List.of();}
        var network=daemon.network(profile.getNetwork());require(network.genesisHash().equals(state.get().genesisHash()),"Monero scanner network changed");
        long end=Math.min(network.height()-required(profile),Math.addExact(state.get().safeHeight(),batch(profile)));
        if(end<=state.get().safeHeight())return List.of();
        var previous=blocks.nativePoint(CHAIN,SCANNER,state.get().safeHeight()).orElseThrow().point();
        Map<Long,MoneroDaemonClient.Header> headers=new LinkedHashMap<>();String parent=previous.hash();
        for(long height=previous.height()+1;height<=end;height++) {
            var header=daemon.header(profile.getNetwork(),height);require(header.parentHash().equals(parent),"Monero deposit batch is not the original parent chain");
            headers.put(height,header);parent=header.hash();
        }
        var anchor=headers.get(end);List<AccountSnapshot> snapshots=new ArrayList<>();
        for(var owner:tracked)snapshots.add(new AccountSnapshot(owner,observe(owner,profile.getNetwork(),network.genesisHash(),anchor,previous.height()+1)));
        require(daemon.header(profile.getNetwork(),end).hash().equals(anchor.hash()),"Monero canonical anchor changed before publication");
        return Objects.requireNonNull(new TransactionTemplate(manager).execute(status->{
            lockCursor(state.get(),previous);
            require(identity(tracked).equals(identity(tracked())),"Monero address publication raced with native scanning");
            var credited=new ArrayList<DepositEvent>();
            for(var account:snapshots)for(var output:account.snapshot().result().monero().path("outputs")) {
                var header=headers.get(output.path("blockHeight").asLong());
                require(header!=null && header.hash().equals(output.path("blockHash").asText()),"Monero output differs from the original batch header");
                if(output.path("change").asBoolean())continue;
                if(credit(account.owner(),output,account.snapshot(),required(profile)))credited.add(event(account.owner(),output,account.snapshot()));
            }
            for(var header:headers.values())blocks.insertNative(CHAIN,SCANNER,new ChainScanPoint(header.height(),header.height(),header.hash()),header.parentHash());
            require(heights.advanceNative(CHAIN,SCANNER,network.genesisHash(),network.height()-1,previous.height(),end)==1,"Monero scan cursor raced");
            return List.copyOf(credited);
        }));
    }

    private MpcSigningService.MoneroSnapshot observe(ChainAddressRecord owner,String network,String genesis,MoneroDaemonClient.Header header,long from) {
        var anchor=new MoneroScanAnchorService.Anchor(network,genesis,new ChainScanPoint(header.height(),header.height(),header.hash()),header.parentHash());
        return signing.observeMonero(owner.getTenantId(),owner.getMpcKeyId(),owner.getAddress(),from,anchor,null,nodes);
    }
    private boolean credit(ChainAddressRecord owner,JsonNode output,MpcSigningService.MoneroSnapshot snapshot,int required) {
        if(output.path("sourceAddress").isTextual() && !submissions.customerTransfer(owner.getTenantId(),output.path("transactionHash").asText(),
                output.path("sourceAddress").asText(),owner.getAddress()))return false;
        return deposits.recordAndCredit(event(owner,output,snapshot),output.path("outputIndex").asLong(),required,owner.getAccountId(),
                output.path("unlocked").asBoolean() && !output.path("frozen").asBoolean());
    }
    private DepositEvent event(ChainAddressRecord owner,JsonNode output,MpcSigningService.MoneroSnapshot snapshot) {
        var audit=json.createObjectNode();audit.set("request",json.valueToTree(snapshot.request()));audit.set("output",output);
        audit.put("intentHash",snapshot.result().intentHash()).put("publicKeyHex",snapshot.result().publicKeyHex())
                .put("viewHeight",snapshot.result().monero().path("viewHeight").asLong()).put("viewHash",snapshot.result().monero().path("viewHash").asText());
        try {audit.put("snapshotHash",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(snapshot.result().monero()))));}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
        return new DepositEvent(ChainType.XMR,CHAIN,output.path("transactionHash").asText(),output.path("sourceAddress").isTextual()?output.path("sourceAddress").asText():null,
                owner.getAddress(),amount(output),output.path("blockHeight").asLong(),output.path("blockHash").asText(),
                Math.toIntExact(Math.min(Integer.MAX_VALUE,output.path("confirmations").asLong())),null,json.writeValueAsString(audit));
    }
    private static BigDecimal amount(JsonNode output) {return new BigDecimal(new BigInteger(output.path("amountAtomic").asText())).movePointLeft(12);}
    private static JsonNode findOutput(MpcSigningService.MoneroSnapshot snapshot,String hash,long index) {
        for(var output:snapshot.result().monero().path("outputs"))if(output.path("transactionHash").asText().equals(hash) && output.path("outputIndex").asLong()==index)return output;
        throw new IllegalStateException("Monero native holders no longer observe the pending original output");
    }
    private void lockCursor(ChainScanHeightRepository.NativeScanState expected,ChainScanPoint point) {
        var locked=heights.nativeState(CHAIN,SCANNER,true).orElseThrow();
        require(locked.safeHeight()==expected.safeHeight() && locked.genesisHash().equals(expected.genesisHash())
                && blocks.nativePoint(CHAIN,SCANNER,locked.safeHeight()).orElseThrow().point().equals(point),"Monero cursor changed before output publication");
    }
    private void requireSameOwner(ChainAddressRecord owner) {
        require(identity(List.of(owner)).equals(identity(List.of(addresses.findById(owner.getId()).orElseThrow()))),"Monero observed output ownership changed");
    }
    private List<ChainAddressRecord> tracked() {
        return addresses.listMpcAddresses(CHAIN).stream().filter(row->CHAIN.equals(row.getAssetSymbol()) && "DEPOSIT".equals(row.getWalletRole())).toList();
    }
    private AccountChainProfile observedProfile() {
        var owner=tracked().getFirst();
        var creation=epochs.find(owner.getTenantId(),owner.getMpcKeyId(),1).orElseThrow().request();
        var policy=json.readTree(HexFormat.of().parseHex(creation.messageHex()));
        return profiles.findByChainAndNetwork(CHAIN,policy.path("network").asText()).orElseThrow();
    }
    private static List<List<Object>> identity(List<ChainAddressRecord> rows) {
        return rows.stream().map(row->List.<Object>of(row.getId(),row.getTenantId(),row.getMpcKeyId(),row.getAddress(),row.getAccountId())).toList();
    }
    private static int required(AccountChainProfile profile) {return Math.max(1,profile.getDepositConfirmations()==null?1:profile.getDepositConfirmations());}
    private static int batch(AccountChainProfile profile) {
        long configured=profile.getScanMaxBlocksPerRun()!=null && profile.getScanMaxBlocksPerRun()>0?profile.getScanMaxBlocksPerRun():profile.getScanBatchSize()==null?50:profile.getScanBatchSize();
        return Math.toIntExact(Math.min(1000L,Math.max(1L,configured)));
    }
    private record PendingBlock(String address,long height) { }
    private record AccountSnapshot(ChainAddressRecord owner,MpcSigningService.MoneroSnapshot snapshot) { }
    private static void require(boolean value,String message) {if(!value)throw new IllegalStateException(message);}
}
