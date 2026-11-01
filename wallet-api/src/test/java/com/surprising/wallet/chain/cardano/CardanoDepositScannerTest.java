package com.surprising.wallet.chain.cardano;

import com.surprising.wallet.sdk.signing.cardano.MpcCardanoTransactionCodec;

import co.nstant.in.cbor.model.UnsignedInteger;
import java.math.BigInteger;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import utxorpc.v1beta.sync.Sync;
import static org.junit.jupiter.api.Assertions.*;

/** Public original-output decoding; these checks do not replace native business acceptance. */
class CardanoDepositScannerTest {
    @Test void actualOriginalOutputSlicesPreserveIndicesAndBalances() throws Exception {
        var json = new ObjectMapper();
        try (var resource = getClass().getResourceAsStream("/mpc/cardano-original-block-reference.json")) {
            for (var vector : json.readTree(resource).path("vectors")) {
                var source = Sync.AnyChainBlock.parseFrom(Base64.getDecoder().decode(vector.path("blockBase64").asText()));
                var tx = CardanoExecutionReceipt.verifyBlock(source).transactions().getFirst();
                var outputs = CardanoExecutionReceipt.outputs(tx);
                assertEquals(tx.body().getOutputs().size(), outputs.size());
                for (int i = 0; i < outputs.size(); i++) {
                    var output = outputs.get(i); assertEquals(i, output.index());
                    assertEquals(tx.body().getOutputs().get(i).getAddress(), output.value().getAddress());
                    assertEquals(tx.body().getOutputs().get(i).getValue(), output.value().getValue());
                    assertFalse(output.originalCbor().isBlank());
                }
            }
        }
    }

    @Test void failedPlutusBodyDoesNotCreateNormalOutputsAndCollateralKeepsAppendedIndex() throws Exception {
        var json = new ObjectMapper();
        try (var resource = getClass().getResourceAsStream("/mpc/cardano-original-block-reference.json")) {
            var vector = json.readTree(resource).path("vectors").get(0);
            var source = Sync.AnyChainBlock.parseFrom(Base64.getDecoder().decode(vector.path("blockBase64").asText()));
            var tx = CardanoExecutionReceipt.verifyBlock(source).transactions().getFirst();
            var failed = new CardanoExecutionReceipt.Transaction(tx.index(), tx.hash(), tx.body(), false, tx.auxiliary(), tx.bodyBytes(), tx.witnessBytes());
            assertTrue(CardanoExecutionReceipt.outputs(failed).isEmpty());
            var body = (co.nstant.in.cbor.model.Map) MpcCardanoTransactionCodec.decode(tx.bodyBytes());
            var returned = tx.body().getOutputs().getFirst();
            body.put(new UnsignedInteger(16), returned.serialize());
            var withReturn = new CardanoExecutionReceipt.Transaction(tx.index(), tx.hash(), tx.body(), false, false,
                    MpcCardanoTransactionCodec.encode(body), tx.witnessBytes());
            var outputs = CardanoExecutionReceipt.outputs(withReturn);
            assertEquals(1, outputs.size()); assertEquals(tx.body().getOutputs().size(), outputs.getFirst().index());
            assertEquals(returned.getAddress(), outputs.getFirst().value().getAddress());
            assertTrue(outputs.getFirst().value().getValue().getCoin().compareTo(BigInteger.ZERO) > 0);
        }
    }
}
