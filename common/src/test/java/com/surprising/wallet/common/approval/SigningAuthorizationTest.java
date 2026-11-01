package com.surprising.wallet.common.approval;

import com.surprising.wallet.common.signing.MpcClient;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SigningAuthorizationTest {
    private final Instant now = Instant.parse("2026-11-01T08:00:00Z");
    private final MpcClient.Request request = new MpcClient.Request(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1,
            MpcClient.Operation.SIGN, MpcClient.Algorithm.ECDSA_SECP256K1, List.of("a".repeat(64), "b".repeat(64)), "12".repeat(32));
    private SigningAuthorization.Claims claims() {
        return new SigningAuthorization.Claims(UUID.randomUUID(), UUID.randomUUID(), request.tenantId(), request.keyId(), 1,
                request.sessionId(), "SIGN", request.algorithm().name(), request.online(),
                SigningAuthorization.hash(HexFormat.of().parseHex(request.messageHex())), "ETH", "eip155:11155111",
                "c".repeat(64), "WITHDRAWAL", now, now.plusSeconds(120));
    }

    /** Public fixture output only, consumed by the native interoperability acceptance. */
    public static void main(String[] args) throws Exception {
        var json=new tools.jackson.databind.ObjectMapper();
        var request=json.readValue(java.nio.file.Files.readAllBytes(java.nio.file.Path.of(args[0])),MpcClient.Request.class);
        var pair=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        var claims=new SigningAuthorization.Claims(UUID.randomUUID(),UUID.randomUUID(),request.tenantId(),request.keyId(),request.epoch(),request.sessionId(),
                request.operation().name(),request.algorithm().name(),request.online(),SigningAuthorization.hash(HexFormat.of().parseHex(request.messageHex())),
                "ETH","regtest","e".repeat(64),"WITHDRAWAL",now,now.plusSeconds(60));
        byte[] der=pair.getPublic().getEncoded();
        System.out.println(json.writeValueAsString(java.util.Map.of("publicKeyHex",HexFormat.of().formatHex(der,der.length-32,der.length),
                "authorization",SigningAuthorization.sign(claims,pair.getPrivate()))));
    }

    @Test void authorizationIsBoundToExactIdentityPayloadAndParticipantOrder() throws Exception {
        var key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var claims = claims();
        var proof = SigningAuthorization.sign(claims, key.getPrivate());
        var der = key.getPublic().getEncoded();
        var pub = SigningAuthorization.publicKey(HexFormat.of().formatHex(der, der.length - 32, der.length));
        assertEquals(claims, SigningAuthorization.verify(proof, pub, request, now));
        for (var changed : List.of(
                new MpcClient.Request(UUID.randomUUID(), request.tenantId(), request.keyId(), 1, request.operation(), request.algorithm(), request.online(), request.messageHex()),
                new MpcClient.Request(request.sessionId(), UUID.randomUUID(), request.keyId(), 1, request.operation(), request.algorithm(), request.online(), request.messageHex()),
                new MpcClient.Request(request.sessionId(), request.tenantId(), UUID.randomUUID(), 1, request.operation(), request.algorithm(), request.online(), request.messageHex()),
                new MpcClient.Request(request.sessionId(), request.tenantId(), request.keyId(), 2, request.operation(), request.algorithm(), request.online(), request.messageHex()),
                new MpcClient.Request(request.sessionId(), request.tenantId(), request.keyId(), 1, request.operation(), request.algorithm(), request.online().reversed(), request.messageHex()),
                new MpcClient.Request(request.sessionId(), request.tenantId(), request.keyId(), 1, request.operation(), request.algorithm(), request.online(), "34".repeat(32)))) {
            assertThrows(IllegalArgumentException.class, () -> SigningAuthorization.verify(proof, pub, changed, now));
        }
        assertThrows(IllegalArgumentException.class, () -> SigningAuthorization.verify(proof,
                KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic(), request, now));
        assertThrows(IllegalArgumentException.class, () -> SigningAuthorization.verify(proof, pub, request, now.minusSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> SigningAuthorization.verify(proof, pub, request, now.plusSeconds(120)));
        assertEquals(claims, SigningAuthorization.verify(proof, pub, request, now.plusSeconds(119)));
    }

    @Test void alteredPayloadOrSignatureCannotBeAccepted() throws Exception {
        var key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var proof = SigningAuthorization.sign(claims(), key.getPrivate());
        var changedPayload = new String(HexFormat.of().parseHex(proof.payloadHex()), java.nio.charset.StandardCharsets.UTF_8)
                .replace("eip155:11155111", "eip155:1");
        var altered = new SigningAuthorization.Proof(HexFormat.of().formatHex(changedPayload.getBytes(java.nio.charset.StandardCharsets.UTF_8)), proof.signatureHex());
        assertThrows(IllegalArgumentException.class, () -> SigningAuthorization.verify(altered, key.getPublic(), request, now));
        var changedSignature = new SigningAuthorization.Proof(proof.payloadHex(),
                (proof.signatureHex().startsWith("00") ? "01" : "00") + proof.signatureHex().substring(2));
        assertThrows(IllegalArgumentException.class, () -> SigningAuthorization.verify(changedSignature, key.getPublic(), request, now));
    }
}
