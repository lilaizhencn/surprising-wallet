package com.surprising.wallet.common.signing;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.surprising.wallet.sdk.signing.MoneroPublicAddressCodec;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Public result boundary tests. Actual native ownership and consensus are verified by the native fixture. */
class MoneroMpcResultVerifierTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ADDRESS = "89FAEsq3NekPZd19gJqD8YjhvHdYNAxhsPHbrHZdjge6emJ1rdgrJRV3gwxq4mey7JYBcNUUeTweVZErX9aYkAWZFPx4kri";
    private static final String PUBLIC = HexFormat.of().formatHex(MoneroPublicAddressCodec.decode(ADDRESS).publicKeys());
    private static final String HASH = "11".repeat(32);
    private static final String TX = "22".repeat(32);

    @Test void retainsBothImmatureAndUnlockedFactsWithoutInventingSpendability() {
        var facts = facts();
        assertDoesNotThrow(() -> verify(facts, false));
        output(facts).put("unlocked", true).put("spent", true);
        assertDoesNotThrow(() -> verify(facts, false));
    }

    @Test void rejectsMissingLockOrOriginalPositionFacts() {
        for (String field : List.of("unlocked", "spent", "frozen", "coinbase", "unlockTime", "outputIndex", "confirmations", "change", "sourceAddress")) {
            var facts = facts(); output(facts).remove(field);
            assertThrows(IllegalArgumentException.class, () -> verify(facts, false), field);
        }
    }

    @Test void rejectsDuplicateOriginalPositionEvenWhenOutputKeysDiffer() {
        var facts = facts(); var duplicate = output(facts).deepCopy().put("outputKey", "44".repeat(32));
        facts.withArray("outputs").add(duplicate);
        assertThrows(IllegalArgumentException.class, () -> verify(facts, false));
    }

    @Test void rejectsDifferentCanonicalBlockAndFalseConfirmationDepth() {
        var facts = facts(); output(facts).put("blockHash", "55".repeat(32));
        assertThrows(IllegalArgumentException.class, () -> verify(facts, false));
        var wrongDepth = facts(); output(wrongDepth).put("confirmations", 10);
        assertThrows(IllegalArgumentException.class, () -> verify(wrongDepth, false));
    }

    @Test void rejectsInvalidOriginalPositionAndUnlockTimeTypes() {
        var facts = facts(); output(facts).put("outputIndex", 10000);
        assertThrows(IllegalArgumentException.class, () -> verify(facts, false));
        var negative = facts(); output(negative).put("unlockTime", -1);
        assertThrows(IllegalArgumentException.class, () -> verify(negative, false));
        var text = facts(); output(text).put("unlocked", "true");
        assertThrows(IllegalArgumentException.class, () -> verify(text, false));
    }

    @Test void requiresExplicitMinerMaturityForKnownTransactions() {
        var facts = facts();
        var transaction = facts.putObject("transaction").put("transactionHash", TX).put("known", true)
                .put("inPool", false).put("doubleSpendSeen", false).put("coinbase", true).put("unlockTime", 160)
                .put("blockHeight", 100).put("blockHash", HASH).put("confirmations", 11);
        assertDoesNotThrow(() -> verify(facts, true));
        transaction.remove("coinbase");
        assertThrows(IllegalArgumentException.class, () -> verify(facts, true));
    }

    @Test void changeRequiresTheActualRegisteredSourceAddress() {
        var facts = facts(); output(facts).put("sourceAddress", ADDRESS).put("change", true);
        assertDoesNotThrow(() -> verify(facts, false));
        output(facts).put("change", false);
        assertThrows(IllegalArgumentException.class, () -> verify(facts, false));
    }

    private static ObjectNode facts() {
        var facts = JSON.createObjectNode().put("kind", "SNAPSHOT").put("network", "regtest")
                .put("genesisHash", HASH).put("address", ADDRESS).put("accountIndex", 1)
                .put("anchorHeight", 100).put("anchorHash", HASH).put("viewHeight", 110).put("viewHash", HASH)
                .put("balanceAtomic", "1000000000000").put("unlockedBalanceAtomic", "0");
        facts.putArray("outputs").addObject().put("transactionHash", TX).put("outputKey", "33".repeat(32))
                .put("outputIndex", 1).put("globalIndex", 73).put("amountAtomic", "1000000000000")
                .put("blockHeight", 100).put("blockHash", HASH).put("confirmations", 11)
                .put("coinbase", false).put("unlockTime", 0).put("spent", false).put("frozen", false).put("unlocked", false)
                .put("change", false).putNull("sourceAddress");
        return facts;
    }

    private static ObjectNode output(ObjectNode facts) { return (ObjectNode) facts.path("outputs").get(0); }
    private static void verify(ObjectNode facts, boolean transaction) {
        var policy = JSON.createObjectNode().put("kind", "SNAPSHOT").put("network", "regtest").put("genesisHash", HASH)
                .put("sender", ADDRESS).put("fromHeight", 90).put("height", 100).put("blockHash", HASH);
        if (transaction) policy.put("transactionHash", TX);
        var request = new MpcClient.Request(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1,
                MpcClient.Operation.MONERO_OBSERVE, MpcClient.Algorithm.MONERO_NATIVE_2_OF_3,
                List.of("aa".repeat(32), "bb".repeat(32)), HexFormat.of().formatHex(JSON.writeValueAsBytes(policy)));
        MoneroMpcResultVerifier.verify(request, PUBLIC, ADDRESS, null, facts);
    }
}
