package com.surprising.wallet.common.approval;

import com.surprising.wallet.common.signing.MpcClient;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;

/** Independently signed, exact-session authority. Contains no MPC share or signing private key. */
public final class SigningAuthorization {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HexFormat HEX = HexFormat.of();
    private static final byte[] DOMAIN = "SW-MPC-AUTHORIZATION-V1\n".getBytes(StandardCharsets.US_ASCII);
    private static final Set<String> PURPOSES = Set.of("WITHDRAWAL", "COLLECTION", "RECOVERY", "FUNDING", "ADDRESS_PREPARATION", "DEV_FAUCET");
    private SigningAuthorization() { }

    public record Claims(UUID authorizationId, UUID requestId, UUID tenantId, UUID keyId, int epoch,
                         UUID sessionId, String operation, String algorithm, java.util.List<String> online,
                         String messageHash, String chain, String network, String businessHash,
                         String purpose, Instant issuedAt, Instant expiresAt) {
        public Claims {
            Objects.requireNonNull(authorizationId); Objects.requireNonNull(requestId);
            Objects.requireNonNull(tenantId); Objects.requireNonNull(keyId); Objects.requireNonNull(sessionId);
            Objects.requireNonNull(issuedAt); Objects.requireNonNull(expiresAt);
            require(epoch > 0 && epoch <= 1_000_000, "authorization epoch is invalid");
            require(Set.of("SIGN", "MONERO_RELAY").contains(operation), "authorization operation is invalid");
            MpcClient.Algorithm.valueOf(algorithm);
            online = java.util.List.copyOf(online);
            require(online.size() == (operation.equals("MONERO_RELAY") ? 1 : 2)
                    && online.stream().distinct().count() == online.size()
                    && online.stream().allMatch(p -> p.matches("[0-9a-f]{64}")), "authorization participants are invalid");
            require(messageHash != null && messageHash.matches("[0-9a-f]{64}")
                    && businessHash != null && businessHash.matches("[0-9a-f]{64}"), "authorization hash is invalid");
            require(chain != null && chain.matches("[A-Z][A-Z0-9_]{0,31}")
                    && network != null && network.matches("[a-zA-Z0-9_.:-]{1,128}")
                    && PURPOSES.contains(purpose), "authorization domain is invalid");
            require(issuedAt.getNano() == 0 && expiresAt.getNano() == 0 && expiresAt.isAfter(issuedAt)
                    && !expiresAt.isAfter(issuedAt.plusSeconds(300)), "authorization lifetime exceeds five minutes");
        }

        public void requireRequest(MpcClient.Request request) {
            require(request.tenantId().equals(tenantId) && request.keyId().equals(keyId)
                    && request.epoch() == epoch && request.sessionId().equals(sessionId)
                    && request.operation().name().equals(operation) && request.algorithm().name().equals(algorithm)
                    && request.online().equals(online)
                    && hash(HEX.parseHex(request.messageHex())).equals(messageHash), "authorization request differs");
        }
    }

    public record Proof(String payloadHex, String signatureHex) {
        public Proof {
            require(payloadHex != null && payloadHex.matches("[0-9a-f]{2,8192}") && payloadHex.length() % 2 == 0,
                    "authorization payload encoding is invalid");
            require(signatureHex != null && signatureHex.matches("[0-9a-f]{128}"), "authorization signature encoding is invalid");
        }
    }

    /** Called only by the separately deployed approval authority, using its own private key. */
    public static Proof sign(Claims claims, PrivateKey key) {
        byte[] payload = canonical(claims);
        try {
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(key); signer.update(DOMAIN); signer.update(payload);
            return new Proof(HEX.formatHex(payload), HEX.formatHex(signer.sign()));
        } catch (GeneralSecurityException failure) { throw new IllegalStateException("authorization signing failed", failure); }
    }

    /** Freshness is checked for first admission; an admitted session recovers using its persisted original proof. */
    public static Claims verify(Proof proof, PublicKey key, MpcClient.Request request, Instant now) {
        var claims=verifyOriginalProof(proof,key,request);
        require(!now.isBefore(claims.issuedAt()) && now.isBefore(claims.expiresAt()), "authorization is outside its validity period");
        return claims;
    }

    /** Signature and original execution only. This never admits a new session; holders independently enforce first-admission freshness. */
    public static Claims verifyOriginalProof(Proof proof,PublicKey key,MpcClient.Request request) {
        try {
            byte[] payload = HEX.parseHex(proof.payloadHex());
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(key); verifier.update(DOMAIN); verifier.update(payload);
            require(verifier.verify(HEX.parseHex(proof.signatureHex())), "authorization signature differs");
            var node = JSON.readTree(payload);
            require(node.isObject() && node.size() == 17 && node.path("v").asInt() == 1, "authorization version differs");
            var claims = new Claims(UUID.fromString(node.path("authorizationId").asText()), UUID.fromString(node.path("requestId").asText()),
                    UUID.fromString(node.path("tenantId").asText()), UUID.fromString(node.path("keyId").asText()), node.path("epoch").asInt(),
                    UUID.fromString(node.path("sessionId").asText()), node.path("operation").asText(), node.path("algorithm").asText(),
                    JSON.convertValue(node.path("online"), new tools.jackson.core.type.TypeReference<java.util.List<String>>() {}),
                    node.path("messageHash").asText(), node.path("chain").asText(), node.path("network").asText(),
                    node.path("businessHash").asText(), node.path("purpose").asText(),
                    Instant.ofEpochSecond(node.path("issuedAt").asLong()), Instant.ofEpochSecond(node.path("expiresAt").asLong()));
            require(MessageDigest.isEqual(payload, canonical(claims)), "authorization payload is not canonical");
            claims.requireRequest(request);
            return claims;
        } catch (GeneralSecurityException failure) { throw new IllegalArgumentException("authorization verification failed", failure); }
    }

    public static PublicKey publicKey(String rawHex) {
        require(rawHex != null && rawHex.matches("[0-9a-f]{64}"), "authorization public key is invalid");
        try { return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(HEX.parseHex("302a300506032b6570032100" + rawHex))); }
        catch (GeneralSecurityException failure) { throw new IllegalArgumentException("authorization public key is invalid", failure); }
    }

    public static String hash(byte[] bytes) {
        try { return HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (GeneralSecurityException impossible) { throw new IllegalStateException(impossible); }
    }

    private static byte[] canonical(Claims c) {
        var body = new TreeMap<String, Object>();
        body.put("v", 1); body.put("authorizationId", c.authorizationId().toString());
        body.put("requestId", c.requestId().toString()); body.put("tenantId", c.tenantId().toString());
        body.put("keyId", c.keyId().toString()); body.put("epoch", c.epoch());
        body.put("sessionId", c.sessionId().toString()); body.put("operation", c.operation());
        body.put("algorithm", c.algorithm()); body.put("online", c.online()); body.put("messageHash", c.messageHash());
        body.put("chain", c.chain()); body.put("network", c.network()); body.put("businessHash", c.businessHash());
        body.put("purpose", c.purpose()); body.put("issuedAt", c.issuedAt().getEpochSecond());
        body.put("expiresAt", c.expiresAt().getEpochSecond());
        return JSON.writeValueAsBytes(body);
    }

    private static void require(boolean value, String message) { if (!value) throw new IllegalArgumentException(message); }
}
