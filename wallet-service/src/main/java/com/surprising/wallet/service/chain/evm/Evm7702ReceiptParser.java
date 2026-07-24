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

/** Strictly decodes and validates per-item collector events from a canonical receipt. */
public class Evm7702ReceiptParser {
    private static final String NATIVE_TOKEN = "0x0000000000000000000000000000000000000000";
    public static final String ITEM_TOPIC = Hash.sha3String(
            "CollectionItemResult(bytes32,uint256,address,address,address,uint256,uint256,bool,bytes32)");
    public static final String BATCH_TOPIC = Hash.sha3String(
            "BatchProcessed(bytes32,uint256,uint256,uint256)");
    public static final String TRANSFER_TOPIC = Hash.sha3String("Transfer(address,address,uint256)");

    private static final List<TypeReference<Type>> ITEM_OUTPUTS = List.of(
            ref(new TypeReference<Address>() { }),
            ref(new TypeReference<Address>() { }),
            ref(new TypeReference<Uint256>() { }),
            ref(new TypeReference<Uint256>() { }),
            ref(new TypeReference<Bool>() { }),
            ref(new TypeReference<Bytes32>() { }));

    public ParsedReceipt parse(TransactionReceipt receipt, String expectedCollector,
                               byte[] expectedBatchId, int expectedItemCount) {
        return parseEvents(receipt, expectedCollector, expectedBatchId, expectedItemCount);
    }

    public ParsedReceipt parse(TransactionReceipt receipt, String expectedCollector,
                               byte[] expectedBatchId, List<ExpectedTransfer> expectedTransfers) {
        if (expectedTransfers == null) {
            throw new IllegalArgumentException("expected transfers are required");
        }
        ParsedReceipt parsed = parseEvents(
                receipt, expectedCollector, expectedBatchId, expectedTransfers.size());
        for (int index = 0; index < expectedTransfers.size(); index++) {
            ExpectedTransfer expected = expectedTransfers.get(index);
            ItemResult item = parsed.items().get(index);
            if (!item.authority().equalsIgnoreCase(expected.authority())
                    || !item.token().equalsIgnoreCase(expected.token())
                    || !item.recipient().equalsIgnoreCase(expected.recipient())
                    || !item.requestedAmount().equals(expected.amount())) {
                throw new IllegalStateException("collection event does not match expected transfer identity");
            }
            long matchingTransfers = receipt.getLogs().stream()
                    .filter(log -> isExpectedTransfer(log, expected))
                    .count();
            boolean nativeAsset = NATIVE_TOKEN.equalsIgnoreCase(expected.token());
            if (item.success()) {
                if (!item.actualReceived().equals(expected.amount())
                        || (nativeAsset ? matchingTransfers != 0 : matchingTransfers != 1)) {
                    throw new IllegalStateException(
                            nativeAsset
                                    ? "successful native collection item must report the exact received amount"
                                    : "successful collection item must have one exact ERC-20 Transfer log");
                }
            } else if (item.actualReceived().signum() != 0 || matchingTransfers != 0) {
                throw new IllegalStateException(
                        "failed collection item must not have an ERC-20 Transfer log or received amount");
            }
        }
        return parsed;
    }

    private ParsedReceipt parseEvents(TransactionReceipt receipt, String expectedCollector,
                                      byte[] expectedBatchId, int expectedItemCount) {
        if (receipt == null || !receipt.isStatusOK()) {
            throw new IllegalArgumentException("successful transaction receipt is required");
        }
        if (receipt.getTo() == null || !receipt.getTo().equalsIgnoreCase(expectedCollector)) {
            throw new IllegalStateException("receipt target is not the configured collector");
        }
        String expectedBatch = Numeric.toHexString(expectedBatchId).toLowerCase(Locale.ROOT);
        ArrayList<ItemResult> items = new ArrayList<>();
        BatchResult batch = null;
        for (Log log : receipt.getLogs()) {
            if (!log.getAddress().equalsIgnoreCase(expectedCollector) || log.getTopics().isEmpty()) {
                continue;
            }
            String topic = log.getTopics().getFirst();
            if (ITEM_TOPIC.equalsIgnoreCase(topic)) {
                if (log.getTopics().size() != 4
                        || !expectedBatch.equals(log.getTopics().get(1).toLowerCase(Locale.ROOT))) {
                    throw new IllegalStateException("collection item event batch/topics mismatch");
                }
                BigInteger itemIndex = Numeric.toBigInt(log.getTopics().get(2));
                String authority = "0x" + log.getTopics().get(3).substring(26);
                List<Type> values = FunctionReturnDecoder.decode(log.getData(), ITEM_OUTPUTS);
                if (values.size() != 6) {
                    throw new IllegalStateException("collection item event data is malformed");
                }
                items.add(new ItemResult(
                        itemIndex.intValueExact(), authority,
                        (String) values.get(0).getValue(), (String) values.get(1).getValue(),
                        (BigInteger) values.get(2).getValue(), (BigInteger) values.get(3).getValue(),
                        (Boolean) values.get(4).getValue(),
                        Numeric.toHexString((byte[]) values.get(5).getValue()),
                        log.getLogIndex().intValueExact()));
            } else if (BATCH_TOPIC.equalsIgnoreCase(topic)) {
                if (batch != null || log.getTopics().size() != 2
                        || !expectedBatch.equals(log.getTopics().get(1).toLowerCase(Locale.ROOT))) {
                    throw new IllegalStateException("batch event is duplicated or mismatched");
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
        items.sort(Comparator.comparingInt(ItemResult::itemIndex));
        if (batch == null || batch.totalItems() != expectedItemCount || items.size() != expectedItemCount
                || batch.successCount() + batch.failureCount() != expectedItemCount) {
            throw new IllegalStateException("receipt does not contain the complete expected collection batch");
        }
        for (int index = 0; index < items.size(); index++) {
            if (items.get(index).itemIndex() != index) {
                throw new IllegalStateException("collection item events are missing or duplicated");
            }
        }
        return new ParsedReceipt(List.copyOf(items), batch);
    }
    private static boolean isExpectedTransfer(Log log, ExpectedTransfer expected) {
        if (!log.getAddress().equalsIgnoreCase(expected.token()) || log.getTopics().size() != 3
                || !TRANSFER_TOPIC.equalsIgnoreCase(log.getTopics().getFirst())
                || !addressTopic(expected.authority()).equalsIgnoreCase(log.getTopics().get(1))
                || !addressTopic(expected.recipient()).equalsIgnoreCase(log.getTopics().get(2))) {
            return false;
        }
        try {
            return Numeric.toBigInt(log.getData()).equals(expected.amount());
        } catch (RuntimeException e) {
            return false;
        }
    }
    private static String addressTopic(String address) {
        String clean = Numeric.cleanHexPrefix(address);
        if (!clean.matches("[0-9a-fA-F]{40}")) {
            throw new IllegalArgumentException("invalid EVM address in expected transfer");
        }
        return "0x" + "0".repeat(24) + clean.toLowerCase(Locale.ROOT);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Type> TypeReference<Type> ref(TypeReference<T> reference) {
        return (TypeReference<Type>) (TypeReference<?>) reference;
    }

    public record ItemResult(
            int itemIndex, String authority, String token, String recipient,
            BigInteger requestedAmount, BigInteger actualReceived, boolean success,
            String errorHash, int logIndex) {
    }
    public record BatchResult(int totalItems, int successCount, int failureCount) {
    }

    public record ExpectedTransfer(
            String authority, String token, String recipient, BigInteger amount) {
        public ExpectedTransfer {
            if (amount == null || amount.signum() <= 0) {
                throw new IllegalArgumentException("expected transfer amount must be positive");
            }
            addressTopic(authority);
            addressTopic(token);
            addressTopic(recipient);
        }
    }
    public record ParsedReceipt(List<ItemResult> items, BatchResult batch) {
    }
}
