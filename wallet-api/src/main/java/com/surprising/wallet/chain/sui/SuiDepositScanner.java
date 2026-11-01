package com.surprising.wallet.chain.sui;

import com.surprising.wallet.sdk.signing.sui.MpcSuiTransactionCodec;

import com.surprising.wallet.chain.model.SuiTransactionRecord;
import com.surprising.wallet.common.chain.*;
import com.surprising.wallet.repository.*;
import com.surprising.wallet.service.DepositService;
import com.surprising.wallet.service.WalletRuntimeConfigService;
import java.math.BigDecimal;
import java.util.*;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Finalized original protobuf balance changes; typed assets share the published MPC owner address. */
@Service
@RequiredArgsConstructor
public class SuiDepositScanner {
    private static final String CHAIN="SUI",SCANNER="sui-balance-change-scanner";
    private final SuiRpcClient rpc;
    private final ChainProfileRepository profiles;
    private final TokenConfigRepository tokens;
    private final ChainScanHeightRepository heights;
    private final ChainAddressRepository addresses;
    private final SuiTransactionRepository transactions;
    private final DepositService deposits;
    private final ChainAssetRepository assets;
    private final DepositRecordRepository records;
    @NonNull private final WalletRuntimeConfigService runtime;

    public List<DepositEvent> scanAndCredit() {
        runtime.requireTaskEnabled(CHAIN,WalletRuntimeConfigService.TASK_SCAN,"sui scanAndCredit");
        var profile=profiles.findAccountByChain(CHAIN).orElseThrow();long latest=rpc.latestCheckpoint();
        var tracked=new HashMap<String,ChainAddressRecord>();var internal=new HashSet<String>();
        for(var address:addresses.listEnabled(CHAIN)) {
            internal.add(MpcSuiTransactionCodec.canonicalAddress(address.getAddress()));
            if("SUI".equals(address.getAssetSymbol()) && "DEPOSIT".equals(address.getWalletRole()))tracked.put(MpcSuiTransactionCodec.canonicalAddress(address.getAddress()),address);
        }
        var configured=new HashMap<String,Asset>();
        int nativeDecimals=assets.findDefinition(CHAIN,"SUI").orElseThrow().getDecimals();
        configured.put(MpcSuiTransactionCodec.canonicalCoinType(SuiRpcClient.SUI_COIN_TYPE),new Asset("SUI",nativeDecimals,null));
        for(var token:tokens.listDefinitions(CHAIN,profile.getNetwork()))if(Boolean.TRUE.equals(token.getActive()))
            configured.put(MpcSuiTransactionCodec.canonicalCoinType(token.getContractAddress()),new Asset(token.getSymbol(),token.getDecimals(),token.getContractAddress()));
        int batch=batch(profile);long start=heights.findSafeHeight(CHAIN,SCANNER).map(height->Math.min(latest,height+1))
                .orElseGet(()->Math.max(profile.getScanStartHeight()==null?0:profile.getScanStartHeight(),latest-batch+1));
        long end=Math.min(latest,Math.addExact(start,batch-1L));var found=new ArrayList<DepositEvent>();
        for(var receipt:rpc.checkpointTransactions(start,end)) {
            var tx=SuiExecutionReceipt.decode(receipt);
            if(!tx.getEffects().getStatus().getSuccess())continue;
            String sender=MpcSuiTransactionCodec.canonicalAddress(tx.getTransaction().getSender());
            if(internal.contains(sender))continue;
            if(tx.getCheckpoint()>latest)throw new IllegalStateException("Sui deposit proof checkpoint exceeds finalized ledger");
            int confirmations=confirmations(latest,tx.getCheckpoint());
            for(int index=0;index<tx.getBalanceChangesCount();index++) {
                var change=tx.getBalanceChanges(index);
                String type;try{type=MpcSuiTransactionCodec.canonicalCoinType(change.getCoinType());}catch(IllegalArgumentException unsupported){continue;}
                var asset=configured.get(type);if(asset==null)continue;
                var address=tracked.get(MpcSuiTransactionCodec.canonicalAddress(change.getAddress()));if(address==null)continue;
                var amount=new BigDecimal(change.getAmount());if(amount.signum()<=0)continue;
                if(records.existsCanonical(CHAIN,tx.getDigest(),index))continue;
                var event=new DepositEvent(ChainType.SUI,asset.symbol(),tx.getDigest(),sender,address.getAddress(),amount.movePointLeft(asset.decimals()),
                        tx.getCheckpoint(),tx.getDigest(),confirmations,asset.contract(),receipt.toString());
                // DepositService owns balance, mandatory tenant projection, audit and replay in one transaction.
                deposits.recordAndCredit(event,index,profile.getDepositConfirmations(),address.getAccountId());
                transactions.upsert(SuiTransactionRecord.builder().chain(CHAIN).txDigest(tx.getDigest()).sender(sender).receiver(address.getAddress())
                        .assetSymbol(asset.symbol()).coinType(type).amount(event.amount()).feeMist(SuiExecutionReceipt.feeMist(tx))
                        .checkpoint(tx.getCheckpoint()).status(confirmations>=profile.getDepositConfirmations()?"CONFIRMED":"CONFIRMING")
                        .executionReceipt(receipt.toString()).build());
                found.add(event);
            }
        }
        heights.upsert(CHAIN,SCANNER,end,Math.max(0,end-profile.getDepositConfirmations()+1));
        confirmObserved();return found;
    }

    /** Previously observed deposits finish even after new scanning or token work is disabled. */
    public void confirmObserved() {
        var pending=records.listPending(CHAIN,Integer.MAX_VALUE,500);if(pending.isEmpty())return;
        var profile=profiles.findAccountByChain(CHAIN).orElseThrow();long latest=rpc.latestCheckpoint();
        for(var row:pending) {
            int confirmations=confirmations(latest,row.blockHeight());if(confirmations<=row.confirmations())continue;
            var event=new DepositEvent(ChainType.SUI,row.assetSymbol(),row.txHash(),row.fromAddress(),row.toAddress(),row.amount(),row.blockHeight(),row.blockHash(),confirmations,row.contractAddress(),row.rawPayload());
            deposits.recordAndCredit(event,row.logIndex(),profile.getDepositConfirmations(),row.accountId());
            if(confirmations>=profile.getDepositConfirmations())transactions.confirmDeposit(row.txHash());
        }
    }
    private static int confirmations(long latest,long checkpoint){return (int)Math.min(Integer.MAX_VALUE,Math.max(0,latest-checkpoint+1));}
    private static int batch(AccountChainProfile profile) {
        long configured=profile.getScanMaxBlocksPerRun()!=null && profile.getScanMaxBlocksPerRun()>0?profile.getScanMaxBlocksPerRun():profile.getScanBatchSize()==null?50:profile.getScanBatchSize();
        return (int)Math.min(1000,Math.max(Math.max(1,profile.getDepositConfirmations()),configured));
    }
    private record Asset(String symbol,int decimals,String contract) { }
}
