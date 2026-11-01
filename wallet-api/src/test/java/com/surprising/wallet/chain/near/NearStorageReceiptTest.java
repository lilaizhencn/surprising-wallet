package com.surprising.wallet.chain.near;

import java.math.BigInteger;
import java.util.*;
import org.bitcoinj.base.Base58;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;
import static org.junit.jupiter.api.Assertions.*;

/** Actual public MPC/nearcore outcomes, including the original template's logged but absent refund. */
class NearStorageReceiptTest {
    private final ObjectMapper json = new ObjectMapper();

    private JsonNode fixtures() throws Exception {
        try (var input = getClass().getResourceAsStream("/mpc/near-actual-storage-proofs.json")) {
            return json.readTree(input);
        }
    }
    private MpcNearTransactionCodec.Registration unsigned(JsonNode item) {
        return json.treeToValue(item.path("unsigned"), MpcNearTransactionCodec.Registration.class);
    }
    private MpcNearTransactionCodec.Signed selected(JsonNode item) {
        if (item.has("selected")) return json.treeToValue(item.path("selected"), MpcNearTransactionCodec.Signed.class);
        String signature = item.path("proof").path("execution").path("transaction").path("signature").asText();
        assertTrue(signature.startsWith("ed25519:"));
        return MpcNearTransactionCodec.assemble(unsigned(item), Base58.decode(signature.substring(8)));
    }
    private NearStorageReceipt.Outcome verify(JsonNode item, JsonNode proof) {
        return NearStorageReceipt.verify(json.treeToValue(proof, NearStorageReceipt.Proof.class), unsigned(item), selected(item));
    }
    private ObjectNode copy(JsonNode item) { return (ObjectNode) item.path("proof").deepCopy(); }
    private JsonNode scenario(String name) throws Exception {
        for (var item : fixtures().path("registrations")) if (name.equals(item.path("scenario").asText())) return item;
        throw new AssertionError("missing actual scenario " + name);
    }
    private static ObjectNode receipt(ObjectNode proof, String predecessor) {
        for (var full : proof.path("receipts")) if (predecessor.equals(full.path("predecessor_id").asText())) return (ObjectNode) full;
        throw new AssertionError("missing actual receipt from " + predecessor);
    }
    private void reject(JsonNode item, JsonNode proof) { assertThrows(RuntimeException.class, () -> verify(item, proof)); }

    @Test void allThreeActualQuorumsProveRegistrationRefundsAndExactNativeExpense() throws Exception {
        var items = fixtures().path("registrations");
        assertEquals(12, items.size());
        var hashes = new HashSet<String>();
        var quorums = new HashMap<String, Integer>();
        for (var item : items) {
            var u = unsigned(item);
            var signed = selected(item);
            var result = verify(item, item.path("proof"));
            assertTrue(hashes.add(signed.txHash()));
            quorums.merge(item.path("quorum").asText(), 1, Integer::sum);
            assertEquals(!"failed-full-refund".equals(item.path("scenario").asText()), result.registered());
            assertEquals(new BigInteger(item.path("feeYocto").asText()), result.feeYocto());
            assertEquals(new BigInteger(item.path("returnedDepositYocto").asText()), result.returnedDepositYocto());
            assertEquals(new BigInteger(item.path("expenseYocto").asText()), result.expenseYocto());
            assertEquals(result.feeYocto().add(u.depositAtomic()).subtract(result.returnedDepositYocto()), result.expenseYocto());
            if (item.path("scenario").asText().contains("full-refund")) {
                assertEquals(u.depositAtomic(), result.returnedDepositYocto());
                assertEquals(result.feeYocto(), result.expenseYocto());
            } else assertEquals(BigInteger.ZERO, result.returnedDepositYocto());
            if ("gas-self-collection".equals(item.path("scenario").asText())) assertEquals(u.sender(), u.beneficiary());
        }
        assertEquals(Map.of("AB", 4, "AC", 4, "BC", 4), quorums);
    }

    @Test void refundLogWithoutAnActualTransferCannotReduceThePayersExpense() throws Exception {
        var item = fixtures().path("loggedRefundWithoutTransfer");
        var result = verify(item, item.path("proof"));
        assertTrue(result.registered());
        assertTrue(item.path("proof").path("execution").path("receipts_outcome").toString().contains("refunding the deposit"));
        assertEquals(BigInteger.ZERO, result.returnedDepositYocto());
        assertEquals(result.feeYocto().add(unsigned(item).depositAtomic()), result.expenseYocto());
    }

    @Test void missingFullReceiptOrCanonicalHeaderRequiresReconciliation() throws Exception {
        var item = scenario("duplicate-full-refund");
        for (String field : List.of("receipts", "headers")) {
            var changed = copy(item);
            var values = (ObjectNode) changed.path(field);
            values.remove(values.properties().iterator().next().getKey());
            reject(item, changed);
        }
    }

    @Test void originalSignatureNonceContractAndFixedActionRemainBound() throws Exception {
        var item = scenario("fresh-withdrawal");
        for (String field : List.of("signature", "public_key", "signer_id", "receiver_id", "nonce")) {
            var changed = copy(item);
            ((ObjectNode) changed.path("execution").path("transaction")).put(field, "changed");
            reject(item, changed);
        }
        for (String field : List.of("args", "method_name", "deposit", "gas")) {
            var changed = copy(item);
            ((ObjectNode) changed.path("execution").path("transaction").path("actions").get(0).path("FunctionCall")).put(field, "changed");
            reject(item, changed);
        }
    }

    @Test void duplicateRefundCannotRedirectThePaymentOrChangeItsSigner() throws Exception {
        var item = scenario("duplicate-full-refund");
        for (String field : List.of("receiver_id", "predecessor_id")) {
            var changed = copy(item);
            receipt(changed, unsigned(item).contract()).put(field, "other.near");
            reject(item, changed);
        }
        for (String field : List.of("signer_id", "signer_public_key", "refund_to")) {
            var changed = copy(item);
            ((ObjectNode) receipt(changed, unsigned(item).contract()).path("receipt").path("Action")).put(field, "other.near");
            reject(item, changed);
        }
        var excess = copy(item);
        ((ObjectNode) receipt(excess, unsigned(item).contract()).path("receipt").path("Action").path("actions").get(0).path("Transfer"))
                .put("deposit", unsigned(item).depositAtomic().add(BigInteger.ONE).toString());
        reject(item, excess);
    }

    @Test void failedRegistrationMustActuallyRefundTheWholeOriginalDeposit() throws Exception {
        var item = scenario("failed-full-refund");
        var changed = copy(item);
        boolean found = false;
        for (var full : changed.path("receipts")) {
            var a = full.path("receipt").path("Action");
            if ("system".equals(a.path("signer_id").asText())) {
                ((ObjectNode) a.path("actions").get(0).path("Transfer")).put("deposit", unsigned(item).depositAtomic().subtract(BigInteger.ONE).toString());
                found = true;
            }
        }
        assertTrue(found);
        reject(item, changed);
    }

    @Test void nonFinalDuplicatedOrCyclicOutcomesCannotSettle() throws Exception {
        var item = scenario("duplicate-full-refund");
        var pending = copy(item);
        pending.withObject("execution").put("final_execution_status", "EXECUTED");
        reject(item, pending);
        var duplicate = copy(item);
        var outcomes = duplicate.withObject("execution").withArray("receipts_outcome");
        outcomes.add(outcomes.get(0));
        reject(item, duplicate);
        var cycle = copy(item);
        var primary = cycle.path("execution").path("receipts_outcome").get(0);
        ((ObjectNode) primary.path("outcome")).withArray("receipt_ids").add(primary.path("id").asText());
        reject(item, cycle);
    }
}
