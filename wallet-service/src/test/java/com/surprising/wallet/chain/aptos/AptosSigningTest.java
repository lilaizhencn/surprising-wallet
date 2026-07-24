package com.surprising.wallet.chain.aptos;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AptosSigningTest {
    private static final String MASTER_SEED =
            "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f";

    @Test
    void buildsSignedNativeTransferJsonFromBcsRawTransaction() {
        AptosKeyService keys = new AptosKeyService(MASTER_SEED);
        AptosTransactionSigner signer = new AptosTransactionSigner(keys);
        String sender = keys.address(7);
        String recipient = keys.address(8);

        AptosTransactionSigner.SignedTransaction signed = signer.nativeTransfer(
                7, sender, 3, recipient, 12345, 2_000, 100, 4);
        JsonNode json = signed.json();

        assertEquals(sender, json.path("sender").asText());
        assertEquals("3", json.path("sequence_number").asText());
        assertEquals("0x1::aptos_account::transfer", json.path("payload").path("function").asText());
        assertEquals(recipient, json.path("payload").path("arguments").get(0).asText());
        assertEquals("12345", json.path("payload").path("arguments").get(1).asText());
        assertEquals("ed25519_signature", json.path("signature").path("type").asText());
        assertTrue(json.path("signature").path("public_key").asText().matches("0x[0-9a-f]{64}"));
        assertTrue(json.path("signature").path("signature").asText().matches("0x[0-9a-f]{128}"));
        assertTrue(signed.rawTransaction().length > 0);
    }

    @Test
    void buildsFungibleAssetTransferWithMetadataObject() {
        AptosKeyService keys = new AptosKeyService(MASTER_SEED);
        AptosTransactionSigner signer = new AptosTransactionSigner(keys);
        String sender = keys.address(12);
        String metadata = keys.address(13);
        String recipient = keys.address(14);

        AptosTransactionSigner.SignedTransaction signed = signer.fungibleAssetTransfer(
                12, sender, 4, metadata, recipient, 1_000_000, 2_000, 100, 4);
        JsonNode payload = signed.json().path("payload");

        assertEquals("0x1::primary_fungible_store::transfer", payload.path("function").asText());
        assertEquals("0x1::fungible_asset::Metadata", payload.path("type_arguments").get(0).asText());
        assertEquals(metadata, payload.path("arguments").get(0).asText());
        assertEquals(recipient, payload.path("arguments").get(1).asText());
        assertEquals("1000000", payload.path("arguments").get(2).asText());
        assertTrue(signed.rawTransaction().length > 0);
    }

}
