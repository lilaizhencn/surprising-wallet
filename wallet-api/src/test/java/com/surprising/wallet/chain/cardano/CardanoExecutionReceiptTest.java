package com.surprising.wallet.chain.cardano;

import com.surprising.wallet.sdk.signing.cardano.MpcCardanoTransactionCodec;

import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.google.protobuf.ByteString;
import java.math.BigInteger;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import utxorpc.v1beta.sync.Sync;
import static org.junit.jupiter.api.Assertions.*;

class CardanoExecutionReceiptTest {
    private final ObjectMapper json = new ObjectMapper();
    private JsonNode vectors() throws Exception {
        try (var resource = getClass().getResourceAsStream("/mpc/cardano-original-block-reference.json")) {
            assertNotNull(resource); return json.readTree(resource).path("vectors");
        }
    }
    private Sync.AnyChainBlock block(JsonNode vector) throws Exception {
        return Sync.AnyChainBlock.parseFrom(Base64.getDecoder().decode(vector.path("blockBase64").asText()));
    }
    private MpcCardanoTransactionCodec.Unsigned unsigned(JsonNode vector) { return json.treeToValue(vector.path("unsigned"), MpcCardanoTransactionCodec.Unsigned.class); }
    private MpcCardanoTransactionCodec.Signed selected(JsonNode vector) { return json.treeToValue(vector.path("selected"), MpcCardanoTransactionCodec.Signed.class); }

    @Test void actualNativeAdaAndTokenBlocksProveExactOriginalWireAndFee() throws Exception {
        for (var vector : vectors()) {
            var source = block(vector);
            var outcome = CardanoExecutionReceipt.verify(source, unsigned(vector), selected(vector));
            assertEquals(vector.path("feeLovelace").bigIntegerValue(), outcome.feeLovelace());
            assertEquals(source.getCardano().getHeader().getHeight(), outcome.blockHeight());
            assertEquals(source.getCardano().getHeader().getSlot(), outcome.slot());
            assertEquals(1, CardanoExecutionReceipt.verifyBlock(source).transactions().size());
        }
    }

    @Test void forgedParsedIdentityOrExecutionResultCannotReplaceOriginalFacts() throws Exception {
        var vector = vectors().get(0); var source = block(vector);
        var changedHeight = source.toBuilder().setCardano(source.getCardano().toBuilder().setHeader(
                source.getCardano().getHeader().toBuilder().setHeight(source.getCardano().getHeader().getHeight() + 1))).build();
        assertThrows(IllegalArgumentException.class, () -> CardanoExecutionReceipt.verifyBlock(changedHeight));
        var changedStatus = source.toBuilder().setCardano(source.getCardano().toBuilder().setBody(source.getCardano().getBody().toBuilder()
                .setTx(0, source.getCardano().getBody().getTx(0).toBuilder().setSuccessful(false)))).build();
        assertThrows(IllegalArgumentException.class, () -> CardanoExecutionReceipt.verifyBlock(changedStatus));
        var changedHash = source.toBuilder().setCardano(source.getCardano().toBuilder().setBody(source.getCardano().getBody().toBuilder()
                .setTx(0, source.getCardano().getBody().getTx(0).toBuilder().setHash(ByteString.copyFrom(new byte[32]))))).build();
        assertThrows(IllegalArgumentException.class, () -> CardanoExecutionReceipt.verifyBlock(changedHash));
    }

    @Test void changingOriginalBodyWithoutHeaderCommitmentIsRejected() throws Exception {
        var vector = vectors().get(1); var source = block(vector);
        var root = (Array) MpcCardanoTransactionCodec.decode(source.getNativeBytes().toByteArray());
        var components = (Array) root.getDataItems().get(1);
        var body = (co.nstant.in.cbor.model.Map) ((Array) components.getDataItems().get(1)).getDataItems().getFirst();
        body.put(new UnsignedInteger(2), new UnsignedInteger(BigInteger.valueOf(999)));
        var tampered = source.toBuilder().setNativeBytes(ByteString.copyFrom(MpcCardanoTransactionCodec.encode(root))).build();
        var error = assertThrows(IllegalArgumentException.class, () -> CardanoExecutionReceipt.verifyBlock(tampered));
        assertTrue(error.getMessage().contains("commitment") || error.getMessage().contains("size"));
    }

    @Test void proofForAnotherActualPaymentCannotSettleSelectedIntent() throws Exception {
        var rows = vectors(); var first = rows.get(0); var other = rows.get(1);
        assertThrows(IllegalArgumentException.class, () -> CardanoExecutionReceipt.verify(block(other), unsigned(first), selected(first)));
    }

    @Test void originalBytesAreMandatoryAndTrailingDataIsRejected() throws Exception {
        var source = block(vectors().get(2));
        assertThrows(IllegalArgumentException.class, () -> CardanoExecutionReceipt.verifyBlock(source.toBuilder().clearNativeBytes().build()));
        var extra = source.toBuilder().setNativeBytes(source.getNativeBytes().concat(ByteString.copyFrom(new byte[] {0}))).build();
        assertThrows(IllegalArgumentException.class, () -> CardanoExecutionReceipt.verifyBlock(extra));
    }

    @Test void openValidityAndAnUnsearchedEndPointCannotProveZeroFeeExpiry() throws Exception {
        var vector = vectors().get(0); var source = block(vector);
        var point = CardanoRpcClient.point(CardanoRpcClient.reference(source.getCardano()));
        assertThrows(IllegalArgumentException.class, () -> CardanoExecutionReceipt.expiredUnspent(source, unsigned(vector),
                selected(vector), point, utxorpc.v1beta.query.Query.ReadUtxosResponse.getDefaultInstance(), 3));
        var changed = new com.surprising.wallet.common.chain.ChainScanPoint(point.height() + 1, point.slot() + 1, point.hash());
        assertThrows(IllegalArgumentException.class, () -> CardanoExecutionReceipt.expiredUnspent(source, unsigned(vector),
                selected(vector), changed, utxorpc.v1beta.query.Query.ReadUtxosResponse.getDefaultInstance(), 3));
    }

    private JsonNode expired() throws Exception {
        try (var resource = getClass().getResourceAsStream("/mpc/cardano-expired-unspent-reference.json")) {
            assertNotNull(resource); return json.readTree(resource);
        }
    }
    private utxorpc.v1beta.query.Query.ReadUtxosResponse unspent(JsonNode vector) throws Exception {
        return utxorpc.v1beta.query.Query.ReadUtxosResponse.parseFrom(Base64.getDecoder().decode(vector.path("unspentBase64").asText()));
    }

    @Test void actualRejectedExpiredPaymentProvesKnownZeroFeeWithUnspentOriginalInputs() throws Exception {
        var vector = expired();
        var point = json.treeToValue(vector.path("searched"), com.surprising.wallet.common.chain.ChainScanPoint.class);
        var outcome = CardanoExecutionReceipt.expiredUnspent(block(vector), unsigned(vector), selected(vector), point, unspent(vector), 3);
        assertEquals(BigInteger.ZERO, outcome.feeLovelace());
        assertTrue(outcome.slot() >= MpcCardanoTransactionCodec.expiry(unsigned(vector)));
    }

    @Test void expiryCannotReleaseFundsWhenAnOriginalInputIsSpentMissingOrChanged() throws Exception {
        var vector = expired(); var inputs = unspent(vector);
        var point = json.treeToValue(vector.path("searched"), com.surprising.wallet.common.chain.ChainScanPoint.class);
        assertThrows(IllegalArgumentException.class, () -> CardanoExecutionReceipt.expiredUnspent(block(vector), unsigned(vector), selected(vector),
                point, inputs.toBuilder().clearItems().build(), 3));
        var item = inputs.getItems(0);
        var changed = inputs.toBuilder().setItems(0, item.toBuilder().setTxoRef(item.getTxoRef().toBuilder().setIndex(item.getTxoRef().getIndex() + 1))).build();
        assertThrows(IllegalArgumentException.class, () -> CardanoExecutionReceipt.expiredUnspent(block(vector), unsigned(vector), selected(vector), point, changed, 3));
        var bytesChanged = inputs.toBuilder().setItems(0, item.toBuilder().setNativeBytes(ByteString.copyFrom(new byte[] {(byte) 0x80}))).build();
        assertThrows(IllegalArgumentException.class, () -> CardanoExecutionReceipt.expiredUnspent(block(vector), unsigned(vector), selected(vector), point, bytesChanged, 3));
    }

    @Test void expiryRequiresFreshLedgerContextAndConfiguredConfirmationDepth() throws Exception {
        var vector = expired(); var inputs = unspent(vector);
        var point = json.treeToValue(vector.path("searched"), com.surprising.wallet.common.chain.ChainScanPoint.class);
        var stale = inputs.toBuilder().setLedgerTip(inputs.getLedgerTip().toBuilder().setHeight(point.height() - 1)).build();
        assertThrows(IllegalArgumentException.class, () -> CardanoExecutionReceipt.expiredUnspent(block(vector), unsigned(vector), selected(vector), point, stale, 3));
        assertThrows(IllegalArgumentException.class, () -> CardanoExecutionReceipt.expiredUnspent(block(vector), unsigned(vector), selected(vector), point, inputs, Integer.MAX_VALUE));
    }
}
