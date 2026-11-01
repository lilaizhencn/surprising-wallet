package com.surprising.wallet.custody;

import com.surprising.wallet.common.signing.MpcSignCommand;

import com.surprising.wallet.common.chain.ChainScanPoint;
import com.surprising.wallet.chain.monero.*;
import com.surprising.wallet.chain.signing.MpcNodes;
import com.surprising.wallet.common.chain.*;
import com.surprising.wallet.model.CustodySecurityProperties;
import com.surprising.wallet.model.mpc.*;
import com.surprising.wallet.repository.*;
import com.surprising.wallet.service.*;
import com.surprising.wallet.service.MoneroSubmissionService.*;
import com.surprising.wallet.testutil.*;
import java.math.*;
import java.net.URI;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.*;
import tools.jackson.databind.ObjectMapper;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Actual formal XMR native submission, deposit classification, principal settlement and original-request recovery. */
public final class MpcMoneroBusinessNativeProbe {
    private MpcMoneroBusinessNativeProbe() { }
    public static void main(String[] args) throws Exception {
        var json=new ObjectMapper();var root=Path.of(args[0]);String phase=args[1];int pair=Integer.parseInt(args[2]);
        var config=json.readTree(Files.readAllBytes(root.resolve("java-probe.json")));
        var test=json.readTree(Files.readAllBytes(root.resolve("business-case-public.json")));
        var jdbc=new JdbcTemplate(CustodyIntegrationDatabase.dataSource());UUID tenant=UUID.fromString(config.path("tenantId").asText());
        var master=root.resolve("coordinator-aes.bin");
        require(!Files.isSymbolicLink(master) && Files.getPosixFilePermissions(master).equals(PosixFilePermissions.fromString("rw-------")),"protected fixture coordinator key required");
        byte[] secret=Files.readAllBytes(master);require(secret.length==32,"fixture coordinator key length differs");
        var security=new CustodySecurityProperties();security.setSecretMasterKey(HexFormat.of().formatHex(secret));Arrays.fill(secret,(byte)0);
        var properties=new MpcNodeProperties();properties.setCaFile(root.resolve("ca.pem"));properties.setCertificateFile(root.resolve("client.pem"));properties.setPrivateKeyFile(root.resolve("client.key"));
        int[][] order={{0,1,2},{0,2,1},{1,2,0}};var configured=new ArrayList<MpcNodeProperties.Node>();
        for(int index:order[pair]){var peer=config.path("peers").get(index);configured.add(new MpcNodeProperties.Node(peer.path("identity").asText(),URI.create("https://localhost:"+peer.path("port").asInt())));}
        properties.setNodes(configured);
        try(var support=new MpcNativeTestSupport(jdbc);var nodes=new MpcNodes(properties);
            var deposits=DepositTestSupport.context(jdbc,CustodyTestSupport.events(jdbc),json)) {
            var requests=new WalletMpcSignRequestRepository(jdbc);var addresses=new ChainAddressRepository(jdbc);var profiles=new ChainProfileRepository(jdbc);
            var original=new MpcSigningService(new CustodyTenantTableRepository(jdbc),new WalletMpcKeyRepository(jdbc),new WalletMpcKeyEpochRepository(jdbc,json),requests,
                    new WalletMpcSignAttemptRepository(jdbc,json),new CustodyCryptoService(security),json,support.manager, com.surprising.wallet.testutil.MpcAuthorizationTestSupport.service(jdbc,json,support.manager));
            var signing=spy(original);var orders=new WithdrawalOrderRepository(jdbc);var collections=new CollectionRecordRepository(jdbc);
            var transactions=new MoneroTransactionRepository(jdbc);var balances=new LedgerBalanceRepository(jdbc);
            var submissions=new MoneroSubmissionService(orders,collections,transactions,requests,new WalletMpcKeyEpochRepository(jdbc,json),signing,nodes,profiles,addresses,
                    new ChainScanHeightRepository(jdbc),new ChainAssetRepository(jdbc),new WithdrawalSettlementService(orders,balances),CustodyTestSupport.gasAccounting(jdbc),json,support.manager);
            var daemon=new MoneroDaemonClient(new ChainRpcNodeService(new ChainRpcNodeRepository(jdbc),"mpc-test",2),json);
            var runtime=new WalletRuntimeConfigService(profiles,new WalletSystemConfigRepository(jdbc));
            var service=new MoneroTransactionService(daemon,submissions,signing,nodes,profiles,addresses,collections,CustodyTestSupport.gas(jdbc),runtime,com.surprising.wallet.testutil.MpcAuthorizationTestSupport.service(jdbc,json,support.manager));
            var scanner=new MoneroDepositScanner(daemon,signing,submissions,nodes,profiles,new WalletMpcKeyEpochRepository(jdbc,json),addresses,
                    new ChainScanHeightRepository(jdbc),new ChainScanBlockRepository(jdbc),new DepositRecordRepository(jdbc),deposits.getBean(DepositService.class),support.manager,json,runtime);
            var source=addresses.listMpcAddresses("XMR").stream().filter(row->row.getTenantId().equals(tenant) && row.getAddress().equals(test.path("source").asText())).findFirst().orElseThrow();
            String reference=test.path("reference").asText();Kind kind=Kind.valueOf(test.path("kind").asText());var op=new Operation(tenant,reference,kind);
            var loseReply=new AtomicBoolean(phase.equals("start") || phase.equals("funded-resume"));
            doAnswer(call->{
                require(!TransactionSynchronizationManager.isActualTransactionActive(),"native relay held a database transaction");
                UUID requestId=call.getArgument(1);var request=requests.find(tenant,requestId).orElseThrow();var row=transactions.find(tenant,requestId).orElseThrow();
                require(request.state()==MpcSignRequest.State.COMPLETED && row.txHash().equals(call.getArgument(2)),"native relay preceded original durable signature selection");
                if(request.command().businessType()!=MpcSignCommand.BusinessType.FUNDING) {
                    String bound=request.command().businessType()==MpcSignCommand.BusinessType.WITHDRAWAL?orders.findTxHash("XMR",request.command().businessReference(),tenant).orElseThrow()
                            :collections.findTxHash(tenant,"XMR",request.command().businessReference()).orElseThrow();
                    require(row.txHash().equals(bound),"native relay preceded durable business hash");
                }
                var result=call.callRealMethod();
                if(request.command().businessReference().equals(reference) && loseReply.getAndSet(false))throw new IllegalStateException("deliberately lost accepted native Monero relay response");
                return result;
            }).when(signing).relayMonero(eq(tenant),any(),anyString(),eq(nodes));
            if(phase.equals("baseline")) {
                enable(jdbc);scan(scanner,profiles);scan(scanner,profiles);
                require(jdbc.queryForObject("select count(*) from custody_deposit where tenant_id=? and chain='XMR'",Integer.class,tenant)==2,"initial native source/Gas funds were not deposited exactly once");
                require(CustodyTestSupport.gas(jdbc).findGasAccount(tenant,"XMR").orElseThrow().availableBalance().compareTo(new BigDecimal("5"))==0,"native Gas funding did not project automatically");
            } else if(phase.equals("start") || phase.equals("funded-resume")) {
                enable(jdbc);
                if(submissions.intent(op).isEmpty()) {
                    BigDecimal amount=new BigDecimal(test.path("amount").asText());
                    new TransactionTemplate(support.manager).execute(status->{
                        if(kind==Kind.WITHDRAWAL) {
                            orders.createForTenant(tenant,reference,source.getUserId(),"XMR","XMR",source.getAddress(),source.getAccountId(),test.path("recipient").asText(),amount,BigDecimal.ZERO);
                            require(balances.freeze("XMR","XMR",source.getAccountId(),amount,tenant),"insufficient actual native customer principal");
                            orders.updateStatus(tenant,"XMR",reference,"FROZEN",source.getAddress(),null,null);
                            require(orders.claimSigningDurable(tenant,"XMR",reference,source.getAddress())==1,"native withdrawal admission raced");
                        } else {
                            UUID custodyId=jdbc.queryForObject("select id from custody_address where tenant_id=? and chain_address_id=?",UUID.class,tenant,source.getId());
                            require(collections.create(tenant,custodyId,reference,"XMR","XMR",source.getAddress(),test.path("recipient").asText(),amount,BigDecimal.ZERO,null)==1,"native collection admission raced");
                        }
                        return null;
                    });
                }
                boolean lost=false,pending=false;
                try{if(kind==Kind.WITHDRAWAL)service.send(op,source);else service.collect(tenant,reference,source);}
                catch(MpcPendingException wait){
                    var request=submissions.intent(op).orElseThrow();
                    var observations=new WalletMpcSignAttemptRepository(jdbc,json).find(tenant,request.currentAttemptId()).orElseThrow().nodeResults();
                    System.err.println("Native business wait: "+wait.state()+", original request="+request.state()+", holder observations="+observations);
                    require(test.path("mode").asText().equals("funded") && phase.equals("start"),"unexpected original native payment wait: "+wait.state());pending=true;
                }
                catch(IllegalStateException error){require(!loseReply.get() && error.getMessage().contains("deliberately lost"),"native business failed before actual accepted reply loss: "+error);lost=true;}
                require(lost || pending,"native business did not exercise accepted reply loss or original funding wait");
                var request=submissions.intent(op).orElseThrow();require(submissions.releaseUnsigned(op)==0,"accepted native intent released frozen principal");
                require(jdbc.queryForObject("select count(*) from wallet_mpc_sign_attempt where tenant_id=? and request_id=?",Integer.class,tenant,request.id())==1,"native business generated a replacement signing attempt");
                if(pending)require(submissions.stored(op).orElseThrow().txHash()==null
                        && jdbc.queryForObject("select count(*) from monero_transaction where tenant_id=? and tx_hash is not null",Integer.class,tenant)>0,"native funding was not durably selected before main signing wait");
                jdbc.update("update chain_profile set enabled=false,withdraw_enabled=false,collection_enabled=false where chain='XMR'");
            } else if(phase.equals("recover")) {
                require(profiles.findAccountByChain("XMR").isEmpty(),"native recovery fixture did not close the chain");
                var before=submissions.stored(op).orElseThrow();require(service.recover(op),"original native business was not recovered while chain disabled");
                require(before.selectedAttemptId().equals(submissions.stored(op).orElseThrow().selectedAttemptId()),"native recovery replaced its original signature");
            } else if(phase.equals("confirm")) {
                require(profiles.findAccountByChain("XMR").isEmpty(),"native confirmation fixture did not close the chain");
                require(kind==Kind.WITHDRAWAL?service.confirmWithdrawal(tenant,reference):service.confirmCollection(tenant,reference),"native original canonical receipt did not settle");
                require(!(kind==Kind.WITHDRAWAL?service.confirmWithdrawal(tenant,reference):service.confirmCollection(tenant,reference)),"native principal settled twice");
                require(submissions.terminal(op),"native original payment did not become terminal");
                enable(jdbc);scan(scanner,profiles);scan(scanner,profiles);
                int expected=test.path("expectedDeposits").asInt();
                require(jdbc.queryForObject("select count(*) from custody_deposit where tenant_id=? and chain='XMR'",Integer.class,tenant)==expected,"native change/internal transfer/customer recipient classification differs");
                if(kind==Kind.WITHDRAWAL)require(balances.find(tenant,"XMR","XMR",source.getAccountId()).orElseThrow().getLockedBalance().signum()==0,"native confirmed customer principal remained frozen");
                var before=submissions.stored(op).orElseThrow();require(service.recover(op),"native confirmed original result was not recoverable");
                require(before.equals(submissions.stored(op).orElseThrow()),"native confirmed recovery modified original receipt");
            } else if(!phase.equals("snapshot"))throw new IllegalArgumentException("unknown native business phase");
            var snapshots=new ArrayList<MpcSigningService.MoneroSnapshot>();
            if(Set.of("baseline","confirm","snapshot").contains(phase)) {
                var network=daemon.network("regtest");var header=daemon.header("regtest",network.height()-1);
                var anchor=new MoneroScanAnchorService.Anchor("regtest",network.genesisHash(),new ChainScanPoint(header.height(),header.height(),header.hash()),header.parentHash());
                for(var address:addresses.listMpcAddresses("XMR"))if(address.getTenantId().equals(tenant))snapshots.add(signing.observeMonero(tenant,address.getMpcKeyId(),address.getAddress(),header.height(),anchor,null,nodes));
            }
            var pending=jdbc.queryForObject("select count(*) from monero_transaction where tenant_id=? and status<>'CONFIRMED'",Integer.class,tenant);
            var fee=jdbc.queryForObject("select coalesce(sum(fee_atomic),0) from monero_transaction where tenant_id=? and status='CONFIRMED'",BigDecimal.class,tenant).toBigIntegerExact();
            var result=new LinkedHashMap<String,Object>();result.put("phase",phase);result.put("pair",pair);result.put("reference",reference);
            result.put("snapshots",snapshots);result.put("pending",pending);result.put("confirmedFeeAtomic",fee.toString());
            result.put("transactions",jdbc.queryForList("select request_id,tx_hash,status,fee_atomic,selected_attempt_id,artifact_hash,block_height,block_hash,confirmations from monero_transaction where tenant_id=? order by id",tenant));
            if(phase.equals("confirm") && test.path("mode").asText().equals("funded")) {
                var funding=new ArrayList<com.surprising.wallet.model.custody.GasUsageRecord>();
                for(UUID id:jdbc.queryForList("select request_id from monero_transaction where tenant_id=?",UUID.class,tenant)) {
                    var request=requests.find(tenant,id).orElseThrow();
                    require(jdbc.queryForObject("select count(*) from wallet_mpc_sign_attempt where tenant_id=? and request_id=?",Integer.class,tenant,id)==1,"native funding or payment recovery created a replacement signing attempt");
                    if(request.command().businessType()!=MpcSignCommand.BusinessType.FUNDING)continue;
                    var row=transactions.find(tenant,id).orElseThrow();
                    var usage=new CustodyGasUsageRepository(jdbc).find(tenant,"ADDRESS_PREPARATION",id).orElseThrow();
                    require(usage.status().equals("SETTLED") && usage.txHash().equals(row.txHash())
                            && usage.actualAmount().compareTo(new BigDecimal(row.feeAtomic()).movePointLeft(12))==0,"native funding charged internal principal or did not settle its original actual fee");
                    funding.add(usage);
                }
                require(funding.size()==1,"native fee funding was duplicated");
                var gasBalance=CustodyTestSupport.gas(jdbc).findGasAccount(tenant,"XMR").orElseThrow().availableBalance();
                require(gasBalance.compareTo(new BigDecimal("5").subtract(funding.getFirst().actualAmount()))==0,"native funding original fee was charged more than once or reserved funds were not released");
                result.put("fundingGasUsages",funding);result.put("fundingGasBookBalance",gasBalance.toPlainString());
            }
            submissions.stored(op).ifPresent(value->result.put("selected",value));
            Files.writeString(root.resolve("monero-business-public.json"),json.writeValueAsString(result));
            System.out.println("XMR actual native business passed: "+phase+", pair="+pair+", reference="+reference);
        }
    }
    private static void enable(JdbcTemplate jdbc){jdbc.update("update chain_profile set enabled=(network='regtest'),withdraw_confirmations=10,deposit_confirmations=1,scan_max_blocks_per_run=500,withdraw_enabled=true,scan_enabled=true,collection_enabled=true,transfer_enabled=true where chain='XMR'");}
    private static void scan(MoneroDepositScanner scanner,ChainProfileRepository profiles){scanner.scanAndCredit(profiles.findAccountByChain("XMR").orElseThrow());scanner.confirmObserved();}
    private static void require(boolean valid,String message){if(!valid)throw new AssertionError(message);}
}
