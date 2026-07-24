package com.surprising.wallet.service.chain.evm;

import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Bool;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.generated.Bytes32;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.crypto.Hash;
import org.web3j.protocol.core.methods.response.Log;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Strict per-item parser for a payout transaction sent to a delegated hot-wallet EOA. */
public class Evm7702PayoutReceiptParser {
    private static final String NATIVE_TOKEN = "0x0000000000000000000000000000000000000000";    public static final String ITEM_TOPIC = Hash.sha3String(
            "PayoutItemResult(bytes32,uint256,bytes32,address,address,uint256,uint256,bool,bytes32)");
    public static final String BATCH_TOPIC = Hash.sha3String(
            "PayoutBatchProcessed(bytes32,address,uint256,uint256,uint256)");
    public static final String TRANSFER_TOPIC = Hash.sha3String("Transfer(address,address,uint256)");

    private static final List<TypeReference<Type>> ITEM_OUTPUTS = List.of(
            ref(new TypeReference<Address>() { }), ref(new TypeReference<Address>() { }),
            ref(new TypeReference<Uint256>() { }), ref(new TypeReference<Uint256>() { }),
            ref(new TypeReference<Bool>() { }), ref(new TypeReference<Bytes32>() { }));

    public ParsedReceipt parse(TransactionReceipt receipt, String expectedAuthority,
                               byte[] expectedBatchId, List<ExpectedPayout> expected) {
        if (receipt == null || !receipt.isStatusOK()) {
            throw new IllegalArgumentException("successful payout receipt is required");
        }
        if (receipt.getTo() == null || !receipt.getTo().equalsIgnoreCase(expectedAuthority)) {
            throw new IllegalStateException("payout receipt target is not the expected tenant hot wallet");
        }
        String expectedBatch = Numeric.toHexString(expectedBatchId).toLowerCase(Locale.ROOT);
        ArrayList<ItemResult> results = new ArrayList<>();
        BatchResult batch = null;
        for (Log log : receipt.getLogs()) {
            if (!log.getAddress().equalsIgnoreCase(expectedAuthority) || log.getTopics().isEmpty()) continue;
            if (ITEM_TOPIC.equalsIgnoreCase(log.getTopics().getFirst())) {
                if (log.getTopics().size() != 4
                        || !expectedBatch.equals(log.getTopics().get(1).toLowerCase(Locale.ROOT))) {
                    throw new IllegalStateException("payout item event batch/topics mismatch");
                }
                List<Type> values = FunctionReturnDecoder.decode(log.getData(), ITEM_OUTPUTS);
                if (values.size() != 6) throw new IllegalStateException("payout item event data is malformed");
                results.add(new ItemResult(
                        Numeric.toBigInt(log.getTopics().get(2)).intValueExact(),
                        Numeric.hexStringToByteArray(log.getTopics().get(3)),
                        (String) values.get(0).getValue(), (String) values.get(1).getValue(),
                        (BigInteger) values.get(2).getValue(), (BigInteger) values.get(3).getValue(),
                        (Boolean) values.get(4).getValue(),
                        Numeric.toHexString((byte[]) values.get(5).getValue()),
                        log.getLogIndex().intValueExact()));
            } else if (BATCH_TOPIC.equalsIgnoreCase(log.getTopics().getFirst())) {
                if (batch != null || log.getTopics().size() != 3
                        || !expectedBatch.equals(log.getTopics().get(1).toLowerCase(Locale.ROOT))
                        || !addressTopic(expectedAuthority).equalsIgnoreCase(log.getTopics().get(2))) {
                    throw new IllegalStateException("payout batch event is duplicated or mismatched");
                }
                List<Type> values = FunctionReturnDecoder.decode(log.getData(), List.of(
                        ref(new TypeReference<Uint256>() { }), ref(new TypeReference<Uint256>() { }),
                        ref(new TypeReference<Uint256>() { })));
                batch = new BatchResult(
                        ((BigInteger) values.get(0).getValue()).intValueExact(),
                        ((BigInteger) values.get(1).getValue()).intValueExact(),
                        ((BigInteger) values.get(2).getValue()).intValueExact());
            }
        }
        results.sort(Comparator.comparingInt(ItemResult::itemIndex));
        if (batch == null || batch.totalItems() != expected.size() || results.size() != expected.size()
                || batch.successCount() + batch.failureCount() != expected.size()) {
            throw new IllegalStateException("receipt does not contain the complete payout batch");
        }
        for (int index = 0; index < expected.size(); index++) {
            ExpectedPayout identity = expected.get(index);
            ItemResult result = results.get(index);
            if (result.itemIndex() != index
                    || !java.util.Arrays.equals(result.withdrawalId(), identity.withdrawalId())
                    || !result.token().equalsIgnoreCase(identity.token())
                    || !result.recipient().equalsIgnoreCase(identity.recipient())
                    || !result.requestedAmount().equals(identity.amount())) {
                throw new IllegalStateException("payout event does not match persisted item identity");
            }
            boolean nativeAsset = NATIVE_TOKEN.equalsIgnoreCase(identity.token());
            long transfers = nativeAsset ? 0 : receipt.getLogs().stream()
                    .filter(log -> isExpectedTransfer(log, expectedAuthority, identity)).count();
            if (result.success()) {
                if (!result.actualReceived().equals(identity.amount()) || (!nativeAsset && transfers != 1)) {
                    throw new IllegalStateException("successful payout item lacks exact transfer evidence");
                }
            } else if (result.actualReceived().signum() != 0 || (!nativeAsset && transfers != 0)) {
                throw new IllegalStateException("failed payout item moved funds");
            }
        }
        long successfulItems = results.stream().filter(ItemResult::success).count();
        if (batch.successCount() != successfulItems
                || batch.failureCount() != results.size() - successfulItems) {
            throw new IllegalStateException("payout batch totals do not match item results");
        }
        return new ParsedReceipt(List.copyOf(results), batch);
    }
    private static boolean isExpectedTransfer(Log log, String authority, ExpectedPayout expected) {
        if (!log.getAddress().equalsIgnoreCase(expected.token()) || log.getTopics().size() != 3
                || !TRANSFER_TOPIC.equalsIgnoreCase(log.getTopics().getFirst())
                || !addressTopic(authority).equalsIgnoreCase(log.getTopics().get(1))
                || !addressTopic(expected.recipient()).equalsIgnoreCase(log.getTopics().get(2))) return false;
        try {
            return Numeric.toBigInt(log.getData()).equals(expected.amount());
        } catch (RuntimeException e) {
            return false;
        }
    }
    private static String addressTopic(String address) {
        String clean = Numeric.cleanHexPrefix(address);
        if (!clean.matches("[0-9a-fA-F]{40}")) throw new IllegalArgumentException("invalid EVM address");
        return "0x" + "0".repeat(24) + clean.toLowerCase(Locale.ROOT);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Type> TypeReference<Type> ref(TypeReference<T> reference) {
        return (TypeReference<Type>) (TypeReference<?>) reference;
    }
    public record ExpectedPayout(byte[] withdrawalId, String token, String recipient, BigInteger amount) {
        public ExpectedPayout {
            if (withdrawalId == null || withdrawalId.length != 32) {
                throw new IllegalArgumentException("withdrawalId must be bytes32");
            }
            withdrawalId = withdrawalId.clone();
            Evm7702PayoutItem.requireAddress(token, "token", true);
            Evm7702PayoutItem.requireAddress(recipient, "recipient", false);
            Evm7702PayoutItem.requireUint(amount, "amount", false);
        }

        @Override
        public byte[] withdrawalId() {
            return withdrawalId.clone();
        }
    }

    public record ItemResult(int itemIndex, byte[] withdrawalId, String token, String recipient,
                             BigInteger requestedAmount, BigInteger actualReceived, boolean success,
                             String errorHash, int logIndex) {
        public ItemResult {
            withdrawalId = withdrawalId.clone();
        }

        @Override
        public byte[] withdrawalId() {
            return withdrawalId.clone();
        }
    }

    public record BatchResult(int totalItems, int successCount, int failureCount) { }
    public record ParsedReceipt(List<ItemResult> items, BatchResult batch) { }
}
