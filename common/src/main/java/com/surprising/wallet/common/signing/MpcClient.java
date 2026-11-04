package com.surprising.wallet.common.signing;

import com.surprising.wallet.sdk.signing.ThresholdSignatureVerifier;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import com.surprising.wallet.common.approval.ApprovalKeyQuarantine;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Authenticated client for one independent signer. It never receives a key share. */
public final class MpcClient implements AutoCloseable {
    public enum Algorithm { ECDSA_SECP256K1, ED25519, SCHNORR_BIP340, MONERO_NATIVE_2_OF_3 }
    public enum Operation { CREATE_KEY, REFRESH, SIGN, MONERO_RELAY, MONERO_OBSERVE }
    public enum State { ABSENT, BUSY, RUNNING, COMPLETED, FAILED, INDETERMINATE, INTERRUPTED }

    public record Node(String partyId, URI endpoint) {
        public Node {
            require(partyId != null && partyId.matches("[0-9a-f]{64}"), "MPC party requires a SHA256 SPKI identity");
            Objects.requireNonNull(endpoint, "MPC endpoint is required");
            require("https".equals(endpoint.getScheme()) && endpoint.getHost() != null
                            && endpoint.getUserInfo() == null && endpoint.getQuery() == null
                            && endpoint.getFragment() == null
                            && (endpoint.getPath().isEmpty() || "/".equals(endpoint.getPath())),
                    "MPC endpoint must be an HTTPS origin");
        }
    }

    public record Request(UUID sessionId, UUID tenantId, UUID keyId, int epoch,
                          Operation operation, Algorithm algorithm, List<String> online, String messageHex) {
        public Request {
            Objects.requireNonNull(sessionId, "sessionId is required");
            Objects.requireNonNull(tenantId, "tenantId is required");
            Objects.requireNonNull(keyId, "keyId is required");
            Objects.requireNonNull(operation, "operation is required");
            Objects.requireNonNull(algorithm, "algorithm is required");
            require(epoch > 0 && epoch <= 1_000_000, "MPC epoch is outside limits");
            online = List.copyOf(online);
            require(online.size() == (operation == Operation.CREATE_KEY || operation == Operation.REFRESH ? 3 : operation == Operation.MONERO_RELAY ? 1 : 2)
                            && online.stream().distinct().count() == online.size()
                            && online.stream().allMatch(party -> party.matches("[0-9a-f]{64}")),
                    "MPC execution requires distinct registered party identities");
            require(messageHex != null && messageHex.length() % 2 == 0
                            && messageHex.matches("[0-9a-f]*"), "MPC message must be lowercase hex");
            if (algorithm == Algorithm.MONERO_NATIVE_2_OF_3) {
                require(operation != Operation.REFRESH && epoch == 1 && !messageHex.isEmpty() && messageHex.length() <= 8192, "invalid native Monero policy or epoch");
            } else {
                require(operation == Operation.CREATE_KEY || operation == Operation.REFRESH || operation == Operation.SIGN, "native Monero operation requires its native protocol");
                if (operation == Operation.CREATE_KEY) require(epoch == 1 && messageHex.isEmpty(), "DKG starts at epoch one without a signing message");
                else if (operation == Operation.REFRESH) require(epoch > 1 && messageHex.isEmpty(), "refresh requires a new epoch without a signing message");
                else if (algorithm != Algorithm.ED25519) require(messageHex.length() == 64, "MPC digest must be 32 bytes");
            }
        }
    }

    public record Result(State state, UUID sessionId, UUID tenantId, UUID keyId, int epoch,
                         String intentHash, String publicKeyHex, String signatureHex, String publicAddress, JsonNode monero) {
        public Result { monero = monero == null ? null : monero.deepCopy(); }
        @Override public JsonNode monero() { return monero == null ? null : monero.deepCopy(); }
    }
    public record QuarantineReceipt(UUID tenantId, UUID keyId, String descriptorHash,
                                    boolean quarantined, String partyId) { }

    /** A call failure never proves that a signing request was not accepted. */
    public static final class CallException extends RuntimeException {
        public CallException(String message, Throwable cause) { super(message, cause); }
    }

    private final Node node;
    private final HttpClient http;
    private final Duration timeout;
    private final ObjectMapper json = new ObjectMapper();

    public static MpcClient connect(Node node, Path caFile, Path certificateFile, Path privateKeyFile,
                                    Duration timeout) {
        Objects.requireNonNull(node, "MPC node is required");
        return new MpcClient(node, MpcTls.context(node.partyId(), caFile, certificateFile, privateKeyFile), timeout);
    }

    private MpcClient(Node node, SSLContext tls, Duration timeout) {
        require(timeout != null && timeout.isPositive() && timeout.compareTo(Duration.ofSeconds(30)) <= 0,
                "MPC call timeout must be between zero and 30 seconds");
        this.node = node;
        this.timeout = timeout;
        SSLParameters parameters = new SSLParameters();
        parameters.setProtocols(new String[]{"TLSv1.3"});
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        http = HttpClient.newBuilder().sslContext(tls).sslParameters(parameters)
                .connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1).build();
    }

    public Node node() { return node; }

    public Result submit(Request request) {
        return invoke("/v1/sessions", request, canonical(request));
    }

    /** An independent authority proof is verified and consumed by every receiving holder. */
    public Result submit(Request request, com.surprising.wallet.common.approval.SigningAuthorization.Proof proof) {
        Objects.requireNonNull(proof, "independent signing authorization is required");
        require(request.operation() == Operation.SIGN || request.operation() == Operation.MONERO_RELAY,
                "payment authorization requires a signing or original Monero relay operation");
        var body = new TreeMap<String, Object>(canonical(request));
        body.put("authorization", proof);
        return invoke("/v1/sessions", request, body);
    }

    public Result result(Request request) {
        return invoke("/v1/results", request, Map.of("sessionId", request.sessionId().toString(),
                "tenantId", request.tenantId().toString(), "keyId", request.keyId().toString()));
    }

    /** Durable one-way fence on this holder; repeating the original event is safe. */
    public QuarantineReceipt quarantineKey(ApprovalKeyQuarantine event) {
        Objects.requireNonNull(event, "key quarantine event is required");
        var body = Map.of("tenantId", event.tenantId().toString(), "keyId", event.keyId().toString(),
                "descriptorHash", event.hash());
        try {
            var request = HttpRequest.newBuilder(node.endpoint().resolve("/v1/key-quarantines"))
                    .timeout(timeout).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body))).build();
            var response = http.send(request, HttpResponse.BodyHandlers.limiting(
                    HttpResponse.BodyHandlers.ofByteArray(), 4096));
            if (response.statusCode() != 200) throw new CallException("MPC holder rejected key quarantine", null);
            var receipt = json.readValue(response.body(), QuarantineReceipt.class);
            require(receipt.quarantined() && event.tenantId().equals(receipt.tenantId())
                    && event.keyId().equals(receipt.keyId()) && event.hash().equals(receipt.descriptorHash())
                    && node.partyId().equals(receipt.partyId()), "MPC holder quarantine receipt differs");
            return receipt;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CallException("MPC holder quarantine interrupted", interrupted);
        } catch (IOException failure) {
            throw new CallException("MPC holder quarantine unavailable", failure);
        }
    }

    private Result invoke(String path, Request expected, Map<String, ?> body) {
        require(expected.online().contains(node.partyId()), "MPC node is outside the execution group");
        byte[] encoded = json.writeValueAsBytes(body);
        require(encoded.length <= 1024 * 1024, "MPC request is too large");
        HttpRequest request = HttpRequest.newBuilder(node.endpoint().resolve(path))
                .timeout(timeout).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(encoded)).build();
        try {
            var response = http.send(request, HttpResponse.BodyHandlers.limiting(
                    HttpResponse.BodyHandlers.ofByteArray(), expected.algorithm() == Algorithm.MONERO_NATIVE_2_OF_3 ? 1024 * 1024 : 16 * 1024));
            if (response.statusCode() != 200) throw new CallException("MPC node rejected call with HTTP " + response.statusCode(), null);
            JsonNode value = json.readTree(response.body());
            State state = State.valueOf(value.path("state").asText());
            UUID session = UUID.fromString(value.path("sessionId").asText());
            require(expected.sessionId().equals(session), "MPC result session differs");
            if (state != State.COMPLETED) {
                return new Result(state, session, expected.tenantId(), expected.keyId(), expected.epoch(), null, null, null, null, null);
            }
            UUID tenant = UUID.fromString(value.path("tenantId").asText());
            UUID key = UUID.fromString(value.path("keyId").asText());
            int epoch = value.path("epoch").asInt();
            String intent = value.path("intentHash").asText();
            require(expected.tenantId().equals(tenant) && expected.keyId().equals(key) && expected.epoch() == epoch
                            && intentHash(expected).equals(intent), "MPC result identity or intent differs");
            String publicKey = value.path("publicKeyHex").asText();
            int publicKeyLength = expected.algorithm() == Algorithm.ECDSA_SECP256K1 ? 66
                    : expected.algorithm() == Algorithm.MONERO_NATIVE_2_OF_3 ? 128 : 64;
            require(publicKey.matches("[0-9a-f]{" + publicKeyLength + "}"), "MPC public key encoding differs");
            String signature = value.has("signatureHex") ? value.path("signatureHex").asText() : null;
            String publicAddress = null;
            JsonNode monero = null;
            if (expected.algorithm() == Algorithm.MONERO_NATIVE_2_OF_3) {
                publicAddress = value.path("publicAddress").asText();
                monero = value.get("monero");
                MoneroMpcResultVerifier.verify(expected, publicKey, publicAddress, signature, monero);
            } else if (expected.operation() == Operation.SIGN) {
                require(signature != null && signature.length() % 2 == 0 && signature.matches("[0-9a-f]*"),
                        "MPC signature encoding differs");
                if (expected.online().getFirst().equals(node.partyId())) {
                    require(expected.algorithm() == Algorithm.ECDSA_SECP256K1
                                    ? signature.length() >= 16 && signature.length() <= 160 : signature.length() == 128,
                            "MPC signature length differs");
                    byte[] publicBytes = HexFormat.of().parseHex(publicKey);
                    byte[] messageBytes = HexFormat.of().parseHex(expected.messageHex());
                    byte[] signatureBytes = HexFormat.of().parseHex(signature);
                    require(switch (expected.algorithm()) {
                        case ECDSA_SECP256K1 -> ThresholdSignatureVerifier.ecdsa(publicBytes, messageBytes, signatureBytes);
                        case ED25519 -> ThresholdSignatureVerifier.ed25519(publicBytes, messageBytes, signatureBytes);
                        case SCHNORR_BIP340 -> ThresholdSignatureVerifier.bip340(publicBytes, messageBytes, signatureBytes);
                        case MONERO_NATIVE_2_OF_3 -> throw new IllegalArgumentException("native Monero is not a curve signature");
                    }, "MPC signature failed independent verification");
                } else require(signature.isEmpty(), "MPC signature returned on unexpected party");
            }
            return new Result(state, session, tenant, key, epoch, intent, publicKey, signature, publicAddress, monero);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CallException("MPC call interrupted; reconcile the existing session", interrupted);
        } catch (IOException | RuntimeException failure) {
            if (failure instanceof CallException known) throw known;
            throw new CallException("MPC response unknown or invalid; reconcile the existing session", failure);
        }
    }

    public String intentHash(Request request) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(canonical(request))));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    static Map<String, Object> canonical(Request request) {
        var body = new TreeMap<String, Object>();
        body.put("sessionId", request.sessionId().toString());
        body.put("tenantId", request.tenantId().toString());
        body.put("keyId", request.keyId().toString());
        body.put("epoch", request.epoch());
        body.put("operation", request.operation().name());
        body.put("algorithm", request.algorithm().name());
        body.put("online", request.online());
        body.put("messageHex", request.messageHex());
        return body;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

    @Override
    public void close() { http.close(); }
}
