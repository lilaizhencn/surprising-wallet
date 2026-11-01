package com.surprising.wallet.chain.hypercore;

import java.math.BigDecimal;
import tools.jackson.databind.JsonNode;

/** The original spot transfer, identified by the signed owner's nonce rather than a balance delta. */
public final class HyperCoreExecutionReceipt {
    private HyperCoreExecutionReceipt() { }

    public static void verify(MpcHyperCoreActionCodec.Unsigned unsigned, JsonNode receipt) {
        var action = MpcHyperCoreActionCodec.restore(unsigned);
        require("spotSend".equals(action.path("type").asText()), "HyperCore receipt requires a spot payment");
        require(receipt != null && receipt.isObject()
                && receipt.path("hash").asText().matches("0x[0-9a-f]{64}")
                && receipt.path("time").isIntegralNumber() && receipt.path("time").canConvertToLong()
                && receipt.path("time").asLong() > 0, "HyperCore ledger identity is missing");
        var delta = receipt.path("delta");
        require("spotTransfer".equals(delta.path("type").asText())
                && delta.path("nonce").isIntegralNumber() && delta.path("nonce").canConvertToLong()
                && delta.path("nonce").asLong() == action.path("time").asLong()
                && unsigned.owner().equalsIgnoreCase(delta.path("user").asText())
                && action.path("destination").asText().equalsIgnoreCase(delta.path("destination").asText()),
                "HyperCore ledger nonce, sender or recipient differs");
        String token = action.path("token").asText().split(":", 2)[0];
        require(token.equals(delta.path("token").asText())
                && decimal(delta, "amount").compareTo(new BigDecimal(action.path("amount").asText())) == 0,
                "HyperCore ledger asset or amount differs");
        // All admitted destinations were activated before signing. Unexpected fees need reconciliation.
        require(decimal(delta, "fee").signum() == 0 && decimal(delta, "nativeTokenFee").signum() == 0,
                "HyperCore ledger contains an unreserved network fee");
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        require(node.path(field).isTextual(), "HyperCore ledger decimal is missing: " + field);
        var value = new BigDecimal(node.path(field).asText());
        require(value.signum() >= 0, "HyperCore ledger decimal is negative");
        return value;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
