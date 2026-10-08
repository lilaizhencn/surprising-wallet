package com.surprising.wallet.chain;

import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.ArrayNode;
import java.util.LinkedHashSet;
import java.util.List;

/** Public transaction attempts retained inside the signing row for confirmation races. */
public final class BitcoinLikeRbfHistory {
    private BitcoinLikeRbfHistory() { }
    private static final List<String> ATTEMPT_FIELDS = List.of(
            "txId", "rawTransaction", "fee", "feeRate", "withdraw", "vBytes", "estimatedVBytes",
            "signingRequestId", "weight", "estimatedWeight");

    public static boolean hasHistory(ObjectNode signature) {
        return signature.path("rbfHistory").isArray() && !signature.path("rbfHistory").isEmpty();
    }

    public static void archive(ObjectNode signature, String txId) {
        ObjectNode attempt = signature.objectNode();
        for (String field : ATTEMPT_FIELDS) {
            if (signature.has(field)) attempt.set(field, signature.get(field).deepCopy());
        }
        attempt.put("txId", txId);
        attempt.put("replacedAt", java.time.Instant.now().toString());
        ArrayNode history = signature.has("rbfHistory")
                ? (ArrayNode) signature.get("rbfHistory") : signature.putArray("rbfHistory");
        history.add(attempt);
    }

    public static List<String> transactionIds(ObjectNode signature, String currentTxId) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (currentTxId != null && currentTxId.matches("[0-9a-fA-F]{64}")) ids.add(currentTxId);
        for (var attempt : signature.path("rbfHistory")) {
            String id = attempt.path("txId").asText();
            if (id.matches("[0-9a-fA-F]{64}")) ids.add(id);
        }
        return List.copyOf(ids);
    }

    /** Restore the amounts of the attempt that actually confirmed, retaining all audit history. */
    public static ObjectNode confirmedSignature(ObjectNode signature, String currentTxId, String confirmedTxId) {
        ObjectNode result = signature.deepCopy();
        if (!confirmedTxId.equals(currentTxId)) {
            ObjectNode matched = null;
            for (var attempt : signature.path("rbfHistory")) {
                if (confirmedTxId.equals(attempt.path("txId").asText())) matched = (ObjectNode) attempt;
            }
            if (matched == null) throw new IllegalArgumentException("unknown confirmation attempt");
            for (String field : ATTEMPT_FIELDS) {
                result.remove(field);
                if (matched.has(field)) result.set(field, matched.get(field).deepCopy());
            }
            result.put("confirmedPreviousAttempt", true);
        }
        result.put("confirmedTxId", confirmedTxId);
        return result;
    }
}
