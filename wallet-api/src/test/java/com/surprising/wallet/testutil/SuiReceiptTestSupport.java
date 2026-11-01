package com.surprising.wallet.testutil;

import com.surprising.wallet.sdk.signing.sui.MpcSuiTransactionCodec;

import com.google.protobuf.ByteString;
import com.surprising.wallet.chain.sui.*;
import java.math.BigInteger;
import java.util.*;
import sui.rpc.v2.*;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Synthetic execution observations for PostgreSQL fault tests; actual chain proofs have separate native acceptance. */
public final class SuiReceiptTestSupport {
    private SuiReceiptTestSupport() { }
    public static ObjectNode payment(MpcSuiTransactionCodec.Unsigned u,MpcSuiTransactionCodec.Signed signed,boolean paid,long fee) {
        var tx=ExecutedTransactionOuterClass.ExecutedTransaction.newBuilder().setDigest(signed.digest()).setCheckpoint(123)
                .setTransaction(TransactionOuterClass.Transaction.newBuilder().setSender(u.sender()).setDigest(signed.digest())
                        .setBcs(bcs(Base64.getDecoder().decode(signed.txBytesBase64()))))
                .addSignatures(Signature.UserSignature.newBuilder().setBcs(bcs(Base64.getDecoder().decode(signed.signatureBase64()))))
                .setEffects(Effects.TransactionEffects.newBuilder().setTransactionDigest(signed.digest())
                        .setStatus(ExecutionStatusOuterClass.ExecutionStatus.newBuilder().setSuccess(paid))
                        .setGasUsed(GasCostSummaryOuterClass.GasCostSummary.newBuilder().setComputationCost(1_000_000).setStorageCost(1_000_000).setStorageRebate(Math.subtractExact(2_000_000,fee))));
        var changes=new LinkedHashMap<String,BigInteger>();String nativeType=MpcSuiTransactionCodec.canonicalCoinType(SuiRpcClient.SUI_COIN_TYPE);
        delta(changes,u.sender(),nativeType,BigInteger.valueOf(fee).negate());
        if(paid){delta(changes,u.sender(),u.coinType(),BigInteger.valueOf(u.amountAtomic()).negate());delta(changes,u.recipient(),u.coinType(),BigInteger.valueOf(u.amountAtomic()));}
        changes.forEach((key,amount)->{if(amount.signum()!=0){String[] fields=key.split("/",2);tx.addBalanceChanges(BalanceChangeOuterClass.BalanceChange.newBuilder().setAddress(fields[0]).setCoinType(fields[1]).setAmount(amount.toString()));}});
        return envelope(tx.build());
    }
    public static ObjectNode envelope(ExecutedTransactionOuterClass.ExecutedTransaction tx){return new ObjectMapper().createObjectNode().put("checkpoint",tx.getCheckpoint()).put("executedTransactionBase64",Base64.getEncoder().encodeToString(tx.toByteArray()));}
    private static BcsOuterClass.Bcs bcs(byte[] bytes){return BcsOuterClass.Bcs.newBuilder().setValue(ByteString.copyFrom(bytes)).build();}
    private static void delta(Map<String,BigInteger> changes,String owner,String type,BigInteger amount){changes.merge(owner+"/"+type,amount,BigInteger::add);}
}
