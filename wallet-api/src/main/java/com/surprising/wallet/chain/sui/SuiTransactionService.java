package com.surprising.wallet.chain.sui;

import com.surprising.wallet.sdk.signing.sui.MpcSuiTransactionCodec;
import com.surprising.wallet.sdk.signing.sui.SuiCoin;

import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.signing.SuiSigningPayload;
import com.surprising.wallet.repository.*;
import com.surprising.wallet.service.*;
import com.surprising.wallet.service.SuiSubmissionService.Operation;
import com.surprising.wallet.service.SuiSubmissionService.Kind;
import java.math.BigDecimal;
import java.util.*;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Public Coin selection and gRPC only; fixed-object recovery never creates a replacement payment. */
@Service
@RequiredArgsConstructor
public class SuiTransactionService {
    private final SuiRpcClient rpc;
    private final SuiSubmissionService submissions;
    private final ChainProfileRepository profiles;
    private final TokenConfigRepository tokens;
    private final WithdrawalOrderRepository orders;
    private final CollectionRecordRepository collections;
    @NonNull private final WalletRuntimeConfigService runtime;
    private final HotWalletBroadcastFence broadcastFence;

    public String send(Operation op,ChainAddressRecord source) {
        runtime.requireTaskEnabled("SUI",WalletRuntimeConfigService.TASK_WITHDRAW,"Sui withdrawal");
        if(op.kind()!=Kind.WITHDRAWAL)throw new IllegalArgumentException("withdrawal operation required");
        return transfer(op,source);
    }
    public String collect(UUID tenant,String reference,ChainAddressRecord source) {
        runtime.requireTaskEnabled("SUI",WalletRuntimeConfigService.TASK_COLLECTION,"Sui collection");
        var previous=collections.findTxHash(tenant,"SUI",reference);if(previous.isPresent())return previous.get();
        if(collections.claimSigningDurable(tenant,"SUI",reference)!=1)throw new IllegalStateException("Sui collection is claimed; recovery owns the intent");
        var op=new Operation(tenant,reference,Kind.COLLECTION);
        try{String digest=transfer(op,source);submissions.restoreSent(op,digest);return digest;}
        catch(RuntimeException error){submissions.unknown(op,error.getClass().getSimpleName());throw error;}
    }
    private String transfer(Operation op,ChainAddressRecord source) {
        SuiSigningPayload draft=null;
        if(submissions.intent(op).isEmpty()) {
            var payment=submissions.payment(op);var profile=profiles.findAccountByChain("SUI").orElseThrow();
            String type=SuiRpcClient.SUI_COIN_TYPE;int decimals=9;
            if(!"SUI".equals(payment.symbol())){var token=tokens.findDefinition("SUI",profile.getNetwork(),payment.symbol()).orElseThrow();type=token.getContractAddress();decimals=token.getDecimals();}
            long amount=payment.amount().movePointRight(decimals).longValueExact();
            long budget=Math.max(1,profile.getDefaultFee());long price=rpc.referenceGasPrice();
            var gas=select(source.getAddress(),SuiRpcClient.SUI_COIN_TYPE,BigDecimal.valueOf(budget).add("SUI".equals(payment.symbol())?BigDecimal.valueOf(amount):BigDecimal.ZERO));
            var inputs="SUI".equals(payment.symbol())?List.<SuiCoin>of():select(source.getAddress(),type,BigDecimal.valueOf(amount));
            var unsigned=new MpcSuiTransactionCodec.Unsigned(profile.getNetwork(),rpc.serviceInfo().chainIdentifier(),source.getAddress(),payment.recipient(),amount,type,inputs,gas,price,budget,submissions.publicKey(source));
            draft=new SuiSigningPayload(payment.symbol(),payment.amount(),payment.customerFee(),decimals,unsigned);
        }
        var request=submissions.reserve(op,source,draft);submissions.sign(op,request.id());
        var signed=submissions.commit(op,request.id());return broadcast(op,signed,submissions.payload(request));
    }
    private List<SuiCoin> select(String owner,String type,BigDecimal required) {
        var selected=new ArrayList<SuiCoin>();BigDecimal total=BigDecimal.ZERO;
        for(var coin:rpc.coins(owner,type,128)) {
            if(coin.balance().signum()<=0)continue;
            selected.add(coin);total=total.add(coin.balance());if(total.compareTo(required)>=0)return selected;
        }
        throw new IllegalStateException("insufficient owned Sui Coin objects for exact principal and gas budget");
    }
    public boolean recover(Operation op) {
        var request=submissions.intent(op);if(request.isEmpty())return false;
        submissions.sign(op,request.get().id());var signed=submissions.commit(op,request.get().id());
        String digest=broadcast(op,signed,submissions.payload(request.get()));submissions.restoreSent(op,digest);return true;
    }
    public int releaseUnsigned(Operation op){return submissions.releaseUnsigned(op);}
    private String broadcast(Operation op,MpcSuiTransactionCodec.Signed signed,SuiSigningPayload p) {
        requireIdentity(p);
        var known=rpc.transactionBlock(signed.digest());
        if(!signed.digest().equals(known.path("digest").asText())) {
            var sourceKeyId=submissions.intent(op).orElseThrow().command().keyId();
            var answer=op.kind()==Kind.COLLECTION
                    ?broadcastFence.withAccountCollectionBroadcastPermit(op.tenantId(),"SUI",
                            profiles.findAccountByChain("SUI").orElseThrow().getNetwork(),op.reference(),
                            signed.digest(),sourceKeyId,
                            ()->rpc.executeSignedTransaction(signed.txBytesBase64(),signed.signatureBase64()))
                    :broadcastFence.withPayoutBroadcastPermit(op.tenantId(),"SUI",sourceKeyId,
                            ()->rpc.executeSignedTransaction(signed.txBytesBase64(),signed.signatureBase64()));
            if(!signed.digest().equals(answer.path("digest").asText()))throw new IllegalStateException("Sui node returned another selected transaction digest");
        }
        submissions.observed(op,signed.digest());return signed.digest();
    }
    public boolean confirmWithdrawal(UUID tenant,String reference){return confirm(new Operation(tenant,reference,Kind.WITHDRAWAL),orders.findTxHash("SUI",reference,tenant).orElseThrow());}
    public boolean confirmCollection(UUID tenant,String reference){return confirm(new Operation(tenant,reference,Kind.COLLECTION),collections.findTxHash(tenant,"SUI",reference).orElseThrow());}
    private boolean confirm(Operation op,String digest) {
        var p=submissions.payload(submissions.intent(op).orElseThrow());requireIdentity(p);
        var proof=submissions.proof(op).orElseGet(()->rpc.transactionBlock(digest));
        if(proof.path("checkpoint").asLong()<=0)return false;
        return submissions.settle(op,proof,rpc.latestCheckpoint(),profiles.findAccountByChain("SUI").orElseThrow().getWithdrawConfirmations());
    }
    private void requireIdentity(SuiSigningPayload p) {
        if(!p.unsigned().chainIdentifier().equals(rpc.serviceInfo().chainIdentifier()))throw new IllegalStateException("Sui RPC genesis identity differs from fixed intent");
    }
}
