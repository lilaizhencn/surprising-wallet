package com.surprising.wallet.chain.near;

import com.surprising.wallet.sdk.signing.near.MpcNearTransactionCodec;

import com.surprising.wallet.chain.model.NearTransactionRecord;
import com.surprising.wallet.common.chain.*;
import com.surprising.wallet.repository.*;
import com.surprising.wallet.service.DepositService;
import com.surprising.wallet.service.WalletRuntimeConfigService;
import java.math.*;
import java.util.*;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.*;

/** FINAL NEP-141 net events and direct native payments; arguments alone never prove a token deposit. */
@Service
@RequiredArgsConstructor
public class NearDepositScanner {
    private static final String CHAIN="NEAR",SCANNER="near-block-scanner";
    private static final ObjectMapper JSON=new ObjectMapper();
    private final NearRpcClient rpc;
    private final ChainScanHeightRepository heights;
    private final NearTransactionRepository transactions;
    private final DepositService deposits;
    private final ChainAddressRepository addresses;
    private final ChainProfileRepository profiles;
    private final TokenConfigRepository tokens;
    private final DepositRecordRepository records;
    @NonNull private final WalletRuntimeConfigService runtime;

    public List<DepositEvent> scanAndCredit() {
        runtime.requireTaskEnabled(CHAIN,WalletRuntimeConfigService.TASK_SCAN,"NEAR scanAndCredit");
        var profile=profiles.findAccountByChain(CHAIN).orElseThrow();long latest=rpc.latestFinalBlockHeight();int batch=batch(profile);
        long start=heights.findSafeHeight(CHAIN,SCANNER).map(value->Math.min(latest+1,value+1))
                .orElseGet(()->Math.max(profile.getScanStartHeight()==null?0:profile.getScanStartHeight(),latest-batch+1));
        long end=Math.min(latest,start+batch-1L);var tracked=new HashMap<String,ChainAddressRecord>();
        for(var address:addresses.listEnabled(CHAIN,"NEAR"))if("DEPOSIT".equals(address.getWalletRole()))tracked.put(address.getAddress(),address);
        var configured=new HashMap<String,TokenDefinition>();
        for(var token:tokens.listDefinitions(CHAIN,profile.getNetwork()))if(Boolean.TRUE.equals(token.getActive()))configured.put(token.getContractAddress(),token);
        var events=new ArrayList<DepositEvent>();
        for(long height=start;height<=end;height++) {
            var block=nextBlock(height,end);if(block==null)break;height=block.path("header").path("height").asLong();
            for(var chunkHeader:block.path("chunks")) {
                if(chunkHeader.path("height_included").asLong(height)!=height || "11111111111111111111111111111111".equals(chunkHeader.path("tx_root").asText()))continue;
                for(var tx:rpc.chunk(chunkHeader.path("chunk_hash").asText()).path("transactions")) {
                    String hash=tx.path("hash").asText(),sender=tx.path("signer_id").asText();
                    if(addresses.existsAddress(CHAIN,sender))continue;
                    var execution=rpc.finalTransaction(hash,sender);
                    require("FINAL".equals(execution.path("final_execution_status").asText()) && hash.equals(execution.path("transaction").path("hash").asText())
                            && sender.equals(execution.path("transaction").path("signer_id").asText()) && tx.path("receiver_id").equals(execution.path("transaction").path("receiver_id"))
                            && tx.path("actions").equals(execution.path("transaction").path("actions")),"NEAR deposit final execution differs from canonical transaction");
                    var changes=changes(execution);if(changes.isEmpty())continue;
                    long receiptHeight=height;String receiptBlock=block.path("header").path("hash").asText();
                    for(var outcome:execution.path("receipts_outcome")) {
                        String expected=outcome.path("block_hash").asText();var header=rpc.block(expected).path("header");
                        require(expected.equals(header.path("hash").asText()),"NEAR deposit receipt block identity differs");
                        if(header.path("height").asLong()>receiptHeight){receiptHeight=header.path("height").asLong();receiptBlock=expected;}
                    }
                    require(receiptHeight<=latest,"NEAR deposit receipts exceed selected final height");int confirmations=confirmations(latest,receiptHeight);
                    for(var change:changes) {
                        var address=tracked.get(change.receiver());if(address==null || addresses.existsAddress(CHAIN,change.sender()) || records.existsCanonical(CHAIN,hash,change.index()))continue;
                        TokenDefinition token=change.contract()==null?null:configured.get(change.contract());if(change.contract()!=null && token==null)continue;
                        String symbol=token==null?"NEAR":token.getSymbol();int decimals=token==null?24:token.getDecimals();
                        var event=new DepositEvent(ChainType.NEAR,symbol,hash,change.sender(),address.getAddress(),new BigDecimal(change.amount()).movePointLeft(decimals),receiptHeight,receiptBlock,confirmations,change.contract(),execution.toString());
                        deposits.recordAndCredit(event,change.index(),profile.getDepositConfirmations(),address.getAccountId());
                        transactions.upsert(NearTransactionRecord.builder().chain(CHAIN).txHash(hash).actionIndex((long)change.index()).sender(change.sender()).receiver(address.getAddress()).assetSymbol(symbol).amount(event.amount()).gasBurnt(0L).blockHeight(receiptHeight).status(confirmations>=profile.getDepositConfirmations()?"CONFIRMED":"CONFIRMING").executionReceipt(execution.toString()).build());events.add(event);
                    }
                }
            }
            heights.upsert(CHAIN,SCANNER,height,height);
        }
        confirmObserved();return events;
    }
    /** A missing height advances only when a later finalized header proves a skipped block. */
    private JsonNode nextBlock(long height,long limit) {
        IllegalStateException missing=null;
        for(long candidate=height;candidate<=limit;candidate++) {
            JsonNode block;
            try{block=rpc.block(candidate);}catch(IllegalStateException error){if(!NearRpcClient.isUnknownBlockError(error.getMessage()))throw error;missing=error;continue;}
            require(block.path("header").path("height").asLong(-1)==candidate,"NEAR scan block height differs");
            if(candidate>height && block.path("header").path("prev_height").asLong(Long.MAX_VALUE)>=height)throw Objects.requireNonNull(missing);
            return block;
        }
        if(missing!=null)throw missing;return null;
    }
    public void confirmObserved() {
        var pending=records.listPending(CHAIN,Integer.MAX_VALUE,500);if(pending.isEmpty())return;
        var profile=profiles.findAccountByChain(CHAIN).orElseThrow();long latest=rpc.latestFinalBlockHeight();
        for(var row:pending){int count=confirmations(latest,row.blockHeight());if(count<=row.confirmations())continue;
            var event=new DepositEvent(ChainType.NEAR,row.assetSymbol(),row.txHash(),row.fromAddress(),row.toAddress(),row.amount(),row.blockHeight(),row.blockHash(),count,row.contractAddress(),row.rawPayload());
            deposits.recordAndCredit(event,row.logIndex(),profile.getDepositConfirmations(),row.accountId());if(count>=profile.getDepositConfirmations())transactions.confirmDeposit(row.txHash());}
    }
    record Change(String contract,String sender,String receiver,BigInteger amount,int index) { }
    private static final class Net {
        BigInteger amount=BigInteger.ZERO;String source;int index;
        void add(BigInteger delta,String from,int position){amount=amount.add(delta);if(delta.signum()>0 && source==null){source=from;index=position;}}
    }
    static List<Change> changes(JsonNode receipt) {
        require("FINAL".equals(receipt.path("final_execution_status").asText()),"NEAR deposit execution is not final");var result=new ArrayList<Change>();var tx=receipt.path("transaction");
        var actions=tx.path("actions");boolean direct=receipt.path("status").has("SuccessValue") && actions.isArray() && !actions.isEmpty();BigInteger nativeAmount=BigInteger.ZERO;
        for(var action:actions){if(action.has("Transfer"))nativeAmount=nativeAmount.add(amount(action.path("Transfer").path("deposit")));else if(!action.has("CreateAccount") && !action.has("AddKey"))direct=false;}
        if(direct && nativeAmount.signum()>0)result.add(new Change(null,tx.path("signer_id").asText(),tx.path("receiver_id").asText(),nativeAmount,0));
        var net=new TreeMap<String,Map<String,Net>>();var outcomes=new ArrayList<JsonNode>();receipt.path("receipts_outcome").forEach(outcomes::add);outcomes.sort(Comparator.comparing(item->item.path("id").asText()));
        var seen=new HashSet<String>();int position=1;
        for(var item:outcomes) {
            require(seen.add(item.path("id").asText()),"NEAR duplicated deposit outcome");var outcome=item.path("outcome");String contract=outcome.path("executor_id").asText();
            if(!outcome.path("status").has("SuccessValue") && !outcome.path("status").has("SuccessReceiptId"))continue;
            for(var log:outcome.path("logs")) {
                String text=log.asText();if(!text.startsWith("EVENT_JSON:"))continue;require(text.length()<=65_536,"NEAR deposit event exceeds bounds");
                var event=JSON.readTree(text.substring(11).trim());if(!"nep141".equals(event.path("standard").asText()))continue;
                require("1.0.0".equals(event.path("version").asText()),"NEP-141 event version requires reconciliation");String kind=event.path("event").asText();
                require(Set.of("ft_transfer","ft_mint","ft_burn").contains(kind) && event.path("data").isArray(),"NEP-141 event action requires reconciliation");
                var owners=net.computeIfAbsent(contract,ignored->new TreeMap<>());
                for(var data:event.path("data")) {
                    require(position<100_000,"NEAR deposit event count exceeds bounds");var amount=amount(data.path("amount"));
                    if("ft_transfer".equals(kind)) {
                        String from=MpcNearTransactionCodec.account(data.path("old_owner_id").asText()),to=MpcNearTransactionCodec.account(data.path("new_owner_id").asText());
                        owners.computeIfAbsent(from,ignored->new Net()).add(amount.negate(),to,position++);owners.computeIfAbsent(to,ignored->new Net()).add(amount,from,position++);
                    } else {String owner=MpcNearTransactionCodec.account(data.path("owner_id").asText());owners.computeIfAbsent(owner,ignored->new Net()).add("ft_mint".equals(kind)?amount:amount.negate(),contract,position++);}
                }
            }
        }
        net.forEach((contract,owners)->owners.forEach((owner,value)->{if(value.amount.signum()>0)result.add(new Change(contract,value.source,owner,value.amount,value.index));}));return result;
    }
    private static BigInteger amount(JsonNode value){String text=value.asText();require(text.length()<=39 && text.matches("0|[1-9][0-9]*"),"NEAR deposit amount is malformed");var result=new BigInteger(text);require(result.bitLength()<=128,"NEAR deposit amount exceeds u128");return result;}
    private static int confirmations(long latest,long height){return (int)Math.min(Integer.MAX_VALUE,Math.max(0,latest-height+1));}
    private static int batch(AccountChainProfile profile){long configured=profile.getScanMaxBlocksPerRun()!=null && profile.getScanMaxBlocksPerRun()>0?profile.getScanMaxBlocksPerRun():profile.getScanBatchSize()==null?50:profile.getScanBatchSize();return (int)Math.min(500,Math.max(1,configured));}
    private static void require(boolean condition,String message){if(!condition)throw new IllegalStateException(message);}
}
