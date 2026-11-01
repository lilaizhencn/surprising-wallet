package com.surprising.wallet.chain.sui;

import java.math.BigInteger;
import java.util.*;
import sui.rpc.v2.ExecutedTransactionOuterClass.ExecutedTransaction;
import tools.jackson.databind.JsonNode;

/** Original gRPC proof bound to the exact selected PTB, witness, principal and signed net MIST expense. */
public final class SuiExecutionReceipt {
    private static final String NATIVE = MpcSuiTransactionCodec.canonicalCoinType(SuiRpcClient.SUI_COIN_TYPE);
    private SuiExecutionReceipt() { }
    public record Outcome(boolean paid, long checkpoint, long feeMist) { }

    public static ExecutedTransaction decode(JsonNode receipt) {
        try {
            String value = receipt.path("executedTransactionBase64").asText();
            require(!value.isEmpty() && value.length() <= 2 * 1024 * 1024, "Sui original executed transaction is absent or oversized");
            var tx = ExecutedTransaction.parseFrom(Base64.getDecoder().decode(value));
            require(tx.hasDigest() && tx.hasTransaction() && tx.getTransaction().hasBcs()
                    && tx.getDigest().equals(MpcSuiTransactionCodec.digest(tx.getTransaction().getBcs().getValue().toByteArray())), "Sui original transaction digest differs from BCS");
            require(tx.getTransaction().hasSender() && tx.hasCheckpoint() && tx.getCheckpoint() > 0
                    && tx.hasEffects() && tx.getEffects().hasStatus() && tx.getEffects().getStatus().hasSuccess()
                    && tx.getEffects().hasTransactionDigest() && tx.getDigest().equals(tx.getEffects().getTransactionDigest()), "Sui finalized execution identity or status is absent");
            return tx;
        } catch (com.google.protobuf.InvalidProtocolBufferException error) {
            throw new IllegalArgumentException("invalid Sui original execution proof", error);
        }
    }

    public static Outcome verify(JsonNode receipt, MpcSuiTransactionCodec.Unsigned payment, MpcSuiTransactionCodec.Signed selected) {
        MpcSuiTransactionCodec.verifySelected(payment, selected);
        var tx = decode(receipt);
        require(selected.digest().equals(tx.getDigest()) && payment.sender().equals(MpcSuiTransactionCodec.canonicalAddress(tx.getTransaction().getSender()))
                && Arrays.equals(Base64.getDecoder().decode(selected.txBytesBase64()), tx.getTransaction().getBcs().getValue().toByteArray()), "Sui receipt differs from fixed selected PTB");
        require(tx.getSignaturesCount() == 1 && tx.getSignatures(0).hasBcs(), "Sui selected witness is absent from original execution");
        byte[] witness = tx.getSignatures(0).getBcs().getValue().toByteArray();
        // UserSignature BCS may carry its canonical vector length. This is not part of the 97-byte wire witness.
        if (witness.length == 98 && witness[0] == 97) witness = Arrays.copyOfRange(witness, 1, 98);
        require(Arrays.equals(Base64.getDecoder().decode(selected.signatureBase64()), witness), "Sui original execution used another witness");
        long fee = feeMist(tx);
        require(fee <= payment.gasBudget(), "Sui net gas exceeds fixed budget");
        boolean paid = tx.getEffects().getStatus().getSuccess();
        var expected = new HashMap<String, BigInteger>();
        delta(expected, payment.sender(), NATIVE, BigInteger.valueOf(fee).negate());
        if (paid) {
            delta(expected, payment.sender(), payment.coinType(), BigInteger.valueOf(payment.amountAtomic()).negate());
            delta(expected, payment.recipient(), payment.coinType(), BigInteger.valueOf(payment.amountAtomic()));
        }
        var actual = new HashMap<String, BigInteger>();
        for (var change : tx.getBalanceChangesList()) {
            require(change.hasAddress() && change.hasCoinType() && change.hasAmount() && change.getAmount().matches("-?[0-9]+"), "Sui balance change is incomplete");
            String key = key(change.getAddress(), change.getCoinType());
            require(!actual.containsKey(key), "Sui duplicate balance change");
            actual.put(key, new BigInteger(change.getAmount()));
        }
        actual.values().removeIf(value -> value.signum() == 0); expected.values().removeIf(value -> value.signum() == 0);
        require(expected.equals(actual), "Sui exact principal or owner net gas balance changes differ");
        return new Outcome(paid, tx.getCheckpoint(), fee);
    }

    public static long feeMist(ExecutedTransaction tx) {
        require(tx.hasEffects() && tx.getEffects().hasGasUsed(), "Sui actual gas summary is absent");
        var gas = tx.getEffects().getGasUsed();
        require(gas.hasComputationCost() && gas.hasStorageCost() && gas.hasStorageRebate(), "Sui actual gas summary is incomplete");
        return unsigned(gas.getComputationCost()).add(unsigned(gas.getStorageCost())).subtract(unsigned(gas.getStorageRebate())).longValueExact();
    }
    private static BigInteger unsigned(long value) { return new BigInteger(Long.toUnsignedString(value)); }
    private static String key(String owner, String type) { return MpcSuiTransactionCodec.canonicalAddress(owner) + "/" + MpcSuiTransactionCodec.canonicalCoinType(type); }
    private static void delta(Map<String, BigInteger> changes, String owner, String type, BigInteger amount) { changes.merge(key(owner, type), amount, BigInteger::add); }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
}
