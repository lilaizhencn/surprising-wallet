package com.surprising.wallet.chain.ton;

import com.surprising.wallet.sdk.signing.ton.MpcTonTransactionCodec;

import java.math.BigInteger;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.ton.ton4j.address.Address;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

class MpcTonTransactionCodecTest {
    private static final HexFormat HEX = HexFormat.of();
    private JsonNode fixtures() throws Exception {
        try (var input = getClass().getResourceAsStream("/mpc/ton-codec-reference.json")) {
            return new ObjectMapper().readTree(input).path("vectors");
        }
    }
    private static MpcTonTransactionCodec.Unsigned unsigned(JsonNode v) {
        return new MpcTonTransactionCodec.Unsigned(v.path("network").asText(),v.path("sender").asText(),
                v.path("recipient").asText(),new BigInteger(v.path("amountAtomic").asText()),
                v.path("jettonMaster").isNull()?null:v.path("jettonMaster").asText(),
                v.path("sourceJettonWallet").isNull()?null:v.path("sourceJettonWallet").asText(),
                v.path("recipientJettonWallet").isNull()?null:v.path("recipientJettonWallet").asText(),
                v.path("sequence").asLong(),v.path("validUntil").asLong(),v.path("queryId").asLong(),
                v.path("memo").asText(),v.path("publicKeyHex").asText());
    }
    @Test void nativeJettonAndInitialStateInitMatchIndependentPublicReference() throws Exception {
        for (var v : fixtures()) {
            var u = unsigned(v);
            assertEquals(v.path("walletId").asLong(),MpcTonTransactionCodec.walletId(u.network()));
            assertNull(MpcTonTransactionCodec.wallet(u.network(),HEX.parseHex(u.publicKeyHex())).getKeyPair());
            assertEquals(v.path("bodyHashHex").asText(),HEX.formatHex(MpcTonTransactionCodec.signingBytes(u)));
            var selected = MpcTonTransactionCodec.assemble(u,HEX.parseHex(v.path("signatureHex").asText()));
            assertEquals(v.path("messageHashHex").asText(),selected.messageHashHex());
            // BOC permits different topological cell order; the independently computed root hash binds every cell.
            assertArrayEquals(org.ton.ton4j.cell.Cell.fromBocBase64(v.path("bocBase64").asText()).hash(),
                    org.ton.ton4j.cell.Cell.fromBocBase64(selected.bocBase64()).hash());
            assertDoesNotThrow(()->MpcTonTransactionCodec.verifySelected(u,selected));
        }
    }
    @Test void changedPaymentSequenceExpiryMemoAndSignatureCannotReuseWitness() throws Exception {
        var v = fixtures().path(1);var u = unsigned(v);
        byte[] signature = HEX.parseHex(v.path("signatureHex").asText());
        for (var changed : List.of(
                new MpcTonTransactionCodec.Unsigned(u.network(),u.sender(),u.recipient(),u.amountAtomic().add(BigInteger.ONE),null,null,null,u.sequence(),u.validUntil(),0,u.memo(),u.publicKeyHex()),
                new MpcTonTransactionCodec.Unsigned(u.network(),u.sender(),u.recipient(),u.amountAtomic(),null,null,null,u.sequence()+1,u.validUntil(),0,u.memo(),u.publicKeyHex()),
                new MpcTonTransactionCodec.Unsigned(u.network(),u.sender(),u.recipient(),u.amountAtomic(),null,null,null,u.sequence(),u.validUntil()+1,0,u.memo(),u.publicKeyHex()),
                new MpcTonTransactionCodec.Unsigned(u.network(),u.sender(),u.recipient(),u.amountAtomic(),null,null,null,u.sequence(),u.validUntil(),0,u.memo()+"x",u.publicKeyHex()))) {
            assertThrows(IllegalArgumentException.class,()->MpcTonTransactionCodec.assemble(changed,signature));
        }
        signature[0]^=1;
        assertThrows(IllegalArgumentException.class,()->MpcTonTransactionCodec.assemble(u,signature));
        assertThrows(IllegalArgumentException.class,()->MpcTonTransactionCodec.assemble(u,new byte[63]));
    }
    @Test void networksChangeSignedWalletIdAndContractIdentity() throws Exception {
        var u = unsigned(fixtures().path(0));byte[] publicKey = HEX.parseHex(u.publicKeyHex());
        var addresses = List.of("mainnet","testnet","regtest").stream()
                .map(n->Address.of(MpcTonTransactionCodec.address(n,publicKey)).toRaw()).toList();
        assertEquals(3,addresses.stream().distinct().count());
        assertThrows(IllegalArgumentException.class,()->new MpcTonTransactionCodec.Unsigned("mainnet",u.sender(),u.recipient(),
                u.amountAtomic(),null,null,null,0,u.validUntil(),0,u.memo(),u.publicKeyHex()));
        assertThrows(IllegalArgumentException.class,()->MpcTonTransactionCodec.walletId("unrecognized"));
    }
    @Test void selectedOuterMessageHashAndTrailingWireChangesAreRejected() throws Exception {
        var v = fixtures().path(0);var u = unsigned(v);
        var selected = MpcTonTransactionCodec.assemble(u,HEX.parseHex(v.path("signatureHex").asText()));
        assertThrows(IllegalArgumentException.class,()->MpcTonTransactionCodec.verifySelected(u,
                new MpcTonTransactionCodec.Signed(selected.bocBase64(),"00".repeat(32))));
        byte[] bytes = java.util.Base64.getDecoder().decode(selected.bocBase64());
        var trailing = java.util.Base64.getEncoder().encodeToString(java.util.Arrays.copyOf(bytes,bytes.length+1));
        assertThrows(RuntimeException.class,()->MpcTonTransactionCodec.verifySelected(u,
                new MpcTonTransactionCodec.Signed(trailing,selected.messageHashHex())));
    }
}
