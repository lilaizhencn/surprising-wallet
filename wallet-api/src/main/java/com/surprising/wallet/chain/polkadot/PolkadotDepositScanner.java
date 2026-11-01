package com.surprising.wallet.chain.polkadot;

import com.surprising.wallet.chain.polkadot.MpcPolkadotTransactionCodec.Role;
import com.surprising.wallet.common.chain.*;
import com.surprising.wallet.repository.*;
import com.surprising.wallet.service.DepositService;
import com.surprising.wallet.service.WalletRuntimeConfigService;
import java.math.BigDecimal;
import java.util.*;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Finalized successful Transfer/Transferred events; fee refunds and owned internal payments never credit. */
@Service
@RequiredArgsConstructor
public class PolkadotDepositScanner {
    private final PolkadotRuntimeClient rpc;
    private final ChainScanHeightRepository heights;
    private final DepositService deposits;
    private final ChainAddressRepository addresses;
    private final ChainProfileRepository profiles;
    private final TokenConfigRepository tokens;
    private final ChainAssetRepository assets;
    private final DepositRecordRepository records;
    @NonNull private final WalletRuntimeConfigService runtime;

    public List<DepositEvent> scanAndCredit() {
        runtime.requireTaskEnabled("DOT",WalletRuntimeConfigService.TASK_SCAN,"DOT scanAndCredit");
        var profile=profiles.findAccountByChain("DOT").orElseThrow();var tracked=new HashMap<String,ChainAddressRecord>();
        // The published native MPC account receives all configured Asset Hub tokens.
        for(var address:addresses.listEnabled("DOT","DOT"))if("DEPOSIT".equals(address.getWalletRole()) && address.getMpcKeyId()!=null)tracked.put(account(address.getAddress()),address);
        var configured=new HashMap<String,TokenDefinition>();
        for(var token:tokens.listDefinitions("DOT",profile.getNetwork()))if(Boolean.TRUE.equals(token.getActive()))configured.put(PolkadotRuntimeClient.normalizeAssetId(token.getContractAddress()),token);
        return scan(profile,tracked,configured);
    }
    private List<DepositEvent> scan(AccountChainProfile profile,Map<String,ChainAddressRecord> tracked,Map<String,TokenDefinition> configured) {
        String scanner="polkadot-assethub-scanner";
        long latest=rpc.finalized(Role.ASSET_HUB).path("height").asLong(-1);require(latest>=0,"DOT finalized height is unavailable");
        int required=Math.max(1,profile.getDepositConfirmations()==null?12:profile.getDepositConfirmations());
        long safe=Math.max(0,latest-required+1L),batch=Math.min(100,Math.max(1,profile.getScanMaxBlocksPerRun()==null?25:profile.getScanMaxBlocksPerRun()));
        long start=heights.findSafeHeight("DOT",scanner).map(value->value+1).orElse(Math.max(1,profile.getScanStartHeight()==null?safe-batch+1:profile.getScanStartHeight()));
        if(start>safe)return List.of();long end=Math.min(safe,start+batch-1);var result=new ArrayList<DepositEvent>();
        if(!tracked.isEmpty()) {
            var originals=tracked.values().stream().map(ChainAddressRecord::getAddress).toList();
            var transfers=rpc.scanTransfers(start,end,originals,configured);
            for(var transfer:transfers) {
                require(transfer.blockHeight()>=start && transfer.blockHeight()<=end && transfer.txHash().matches("0x[0-9a-f]{64}")
                        && transfer.blockHash().matches("0x[0-9a-f]{64}") && transfer.eventIndex()>=0,"DOT deposit canonical position differs");
                var target=tracked.get(account(transfer.toAddress()));
                String from=MpcPolkadotTransactionCodec.address(MpcPolkadotTransactionCodec.account(transfer.fromAddress()),PolkadotRuntimeClient.ss58Prefix(profile));
                if(target==null || transfer.amountPlanck().signum()<=0 || addresses.existsAddress("DOT",from) || records.existsCanonical("DOT",transfer.txHash(),Math.toIntExact(transfer.eventIndex())))continue;
                TokenDefinition token=transfer.assetId()==null?null:configured.get(transfer.assetId());
                if(transfer.assetId()!=null && token==null)continue;
                String symbol=token==null?"DOT":token.getSymbol();int decimals=token==null?assets.findActiveDefinition("DOT","DOT").orElseThrow().getDecimals():token.getDecimals();
                int confirmations=(int)Math.min(Integer.MAX_VALUE,latest-transfer.blockHeight()+1);
                var event=new DepositEvent(ChainType.DOT,symbol,transfer.txHash(),from,target.getAddress(),new BigDecimal(transfer.amountPlanck()).movePointLeft(decimals),transfer.blockHeight(),transfer.blockHash(),confirmations,transfer.assetId(),transfer.rawPayload());
                deposits.recordAndCredit(event,transfer.eventIndex(),required,target.getAccountId());result.add(event);
            }
        }
        heights.upsert("DOT",scanner,latest,end);return result;
    }
    private static String account(String address){return HexFormat.of().formatHex(MpcPolkadotTransactionCodec.account(address));}
    private static void require(boolean condition,String message){if(!condition)throw new IllegalStateException(message);}
}
