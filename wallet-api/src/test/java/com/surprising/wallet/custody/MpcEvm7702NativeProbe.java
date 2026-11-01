package com.surprising.wallet.custody;

import com.surprising.wallet.sdk.signing.evm7702.MpcEvm7702Codec;
import com.surprising.wallet.sdk.signing.evm7702.Evm7702PayoutRequest;
import com.surprising.wallet.sdk.signing.evm7702.Evm7702PayoutItem;
import com.surprising.wallet.sdk.signing.evm7702.Evm7702PayoutSigner;
import com.surprising.wallet.sdk.signing.evm7702.Evm7702PayoutCodec;
import com.surprising.wallet.sdk.signing.evm7702.Evm7702CollectionRequest;
import com.surprising.wallet.sdk.signing.evm7702.Evm7702OperationSigner;
import com.surprising.wallet.sdk.signing.evm7702.Evm7702ContractCodec;

import com.surprising.wallet.chain.evm.*;
import com.surprising.wallet.common.signing.MpcClient;
import com.surprising.wallet.sdk.signing.MpcEvmTransactionCodec;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bitcoinj.crypto.ECKey;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.datatypes.Address;
import org.web3j.crypto.AuthorizationTuple;
import org.web3j.crypto.Hash;
import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;
import org.web3j.utils.Numeric;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Actual MPC/Prague codec acceptance. This does not exercise wallet batch or principal accounting. */
public final class MpcEvm7702NativeProbe {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private static final BigInteger CHAIN = BigInteger.valueOf(31337);
    private static final BigInteger PRICE = BigInteger.valueOf(2_000_000_000L);
    private static final BigInteger AMOUNT = BigInteger.TEN.pow(16);
    private static final String RECIPIENT = "0x" + "ce".repeat(20);
    private static final String NATIVE = "0x" + "00".repeat(20);
    private MpcEvm7702NativeProbe() { }

    public static void main(String[] arguments) throws Exception {
        Path root = Path.of(arguments[0]);
        int number = Integer.parseInt(arguments[1]);
        JsonNode fixture = JSON.readTree(Files.readAllBytes(root.resolve("7702-fixture-public.json")));
        String endpoint = fixture.path("evmRpcUrl").asText();
        require("0x7a69".equals(rpc(endpoint, "eth_chainId", List.of()).asText()), "wrong isolated EVM chain");
        UUID tenant = UUID.fromString(fixture.path("tenantId").asText());
        var clients = new ArrayList<MpcClient>();
        try {
            for (var peer : fixture.path("peers")) clients.add(MpcClient.connect(new MpcClient.Node(peer.path("identity").asText(),
                    URI.create("https://localhost:" + peer.path("port").asInt())), root.resolve("ca.pem"),
                    root.resolve("client.pem"), root.resolve("client.key"), Duration.ofSeconds(5)));
            var indices = List.of(List.of(0, 1), List.of(0, 2), List.of(1, 2)).get(number);
            var online = indices.stream().map(clients::get).toList();
            UUID authorityKey = UUID.fromString(fixture.path("authorities").get(number).path("keyId").asText());
            byte[] authorityPublic = hex(fixture.path("authorities").get(number).path("publicKeyHex").asText());
            UUID relayerKey = UUID.fromString(fixture.path("relayer").path("keyId").asText());
            byte[] relayerPublic = hex(fixture.path("relayer").path("publicKeyHex").asText());
            String authority = owner(authorityPublic), relayer = owner(relayerPublic);
            if (number == 0) rpc(endpoint, "anvil_setBalance", List.of(relayer, Numeric.encodeQuantity(BigInteger.TEN.pow(20))));
            String admin = rpc(endpoint, "eth_accounts", List.of()).get(0).asText();
            Path artifacts = Path.of(fixture.path("artifacts").asText());
            String collector = deploy(endpoint, admin, artifacts.resolve("Eip7702Collection.sol/Eip7702BatchCollector.json"), List.of(admin, relayer));
            String delegate = deploy(endpoint, admin, artifacts.resolve("Eip7702Collection.sol/Eip7702CollectionDelegate.json"), List.of(collector));
            String payout = deploy(endpoint, admin, artifacts.resolve("Eip7702Payout.sol/Eip7702PayoutDelegate.json"), List.of(relayer));
            rpc(endpoint, "anvil_setBalance", List.of(authority, Numeric.encodeQuantity(BigInteger.TEN.pow(18))));
            BigInteger sourceBefore = balance(endpoint, authority), relayerBefore = balance(endpoint, relayer), recipientBefore = balance(endpoint, RECIPIENT);
            BigInteger fee = BigInteger.ZERO;
            var transactions = new ArrayList<Map<String, Object>>();
            for (int step = 0; step < 4; step++) {
                boolean collection = step < 2;
                boolean activate = (step & 1) == 0;
                byte[] batchId = Hash.sha3(("mpc-7702-component-" + number + "-" + step).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                BigInteger deadline = Numeric.decodeQuantity(rpc(endpoint, "eth_getBlockByNumber", List.of("latest", false)).path("timestamp").asText()).add(BigInteger.valueOf(300));
                List<AuthorizationTuple> authorizations = List.of();
                if (activate) {
                    BigInteger nonce = Numeric.decodeQuantity(rpc(endpoint, "eth_getTransactionCount", List.of(authority, "pending")).asText());
                    var accepted = new MpcEvm7702Codec.Authorization(CHAIN, authority, collection ? delegate : payout, nonce);
                    byte[] signature = sign(online, tenant, authorityKey, authorityPublic, MpcEvm7702Codec.digest(accepted));
                    authorizations = List.of(MpcEvm7702Codec.authorization(accepted, authorityPublic, signature));
                }
                String calldata;
                if (collection) {
                    var accepted = new Evm7702CollectionRequest(batchId, BigInteger.ZERO, authority, collector, NATIVE, RECIPIENT,
                            AMOUNT, BigInteger.valueOf(step & 1), deadline, BigInteger.valueOf(180_000));
                    byte[] signature = sign(online, tenant, authorityKey, authorityPublic, new Evm7702OperationSigner().digest(CHAIN, accepted));
                    calldata = new Evm7702ContractCodec().encodeCollectBatch(List.of(accepted), List.of(
                            MpcEvm7702Codec.collectionSignature(CHAIN, accepted, authorityPublic, signature)));
                } else {
                    var accepted = new Evm7702PayoutRequest(batchId, authority, relayer, List.of(new Evm7702PayoutItem(batchId,
                            BigInteger.ZERO, NATIVE, RECIPIENT, AMOUNT, BigInteger.valueOf(120_000))), BigInteger.valueOf(step & 1), deadline);
                    byte[] signature = sign(online, tenant, authorityKey, authorityPublic, new Evm7702PayoutSigner().digest(CHAIN, accepted));
                    calldata = new Evm7702PayoutCodec().encode(accepted, MpcEvm7702Codec.payoutSignature(CHAIN, accepted, authorityPublic, signature));
                }
                BigInteger relayerNonce = Numeric.decodeQuantity(rpc(endpoint, "eth_getTransactionCount", List.of(relayer, "pending")).asText());
                var raw = MpcEvm7702Codec.batch(CHAIN.longValueExact(), relayerNonce, BigInteger.valueOf(1_000_000_000), PRICE,
                        BigInteger.valueOf(700_000), collection ? collector : authority, calldata, authorizations);
                byte[] payload = MpcEvmTransactionCodec.unsignedPayload(raw, CHAIN.longValueExact());
                byte[] signature = sign(online, tenant, relayerKey, relayerPublic, Hash.sha3(payload));
                String wire = Numeric.toHexString(MpcEvmTransactionCodec.assemble(payload, CHAIN.longValueExact(), relayerPublic, signature));
                String hash = Hash.sha3(wire);
                require(hash.equalsIgnoreCase(rpc(endpoint, "eth_sendRawTransaction", List.of(wire)).asText()), "consensus selected another hash");
                JsonNode receipt = receipt(endpoint, hash);
                require("0x1".equals(receipt.path("status").asText()), "EIP-7702 component execution failed");
                require(receipt.path("logs").size() >= 2, "component execution has no item/batch proof");
                require(balance(endpoint, RECIPIENT).subtract(recipientBefore).equals(AMOUNT.multiply(BigInteger.valueOf(step + 1))), "recipient amount differs or duplicated");
                try { rpc(endpoint, "eth_sendRawTransaction", List.of(wire)); } catch (RpcRejected expected) { }
                require(balance(endpoint, RECIPIENT).subtract(recipientBefore).equals(AMOUNT.multiply(BigInteger.valueOf(step + 1))), "same-wire replay paid twice");
                require(receipt(endpoint, hash).path("blockHash").equals(receipt.path("blockHash")), "replay changed original canonical receipt");
                require(rpc(endpoint, "eth_getCode", List.of(authority, "latest")).asText().equalsIgnoreCase("0xef0100" + (collection ? delegate : payout).substring(2)), "delegation differs from signed target");
                BigInteger actualFee = Numeric.decodeQuantity(receipt.path("gasUsed").asText()).multiply(Numeric.decodeQuantity(receipt.path("effectiveGasPrice").asText()));
                fee = fee.add(actualFee);
                transactions.add(Map.of("kind", collection ? "COLLECTION" : "PAYOUT", "type", activate ? 4 : 2,
                        "transactionHash", hash, "blockHash", receipt.path("blockHash").asText(), "feeAtomic", actualFee.toString()));
            }
            require(sourceBefore.subtract(balance(endpoint, authority)).equals(AMOUNT.multiply(BigInteger.valueOf(4))), "authority did not pay exact principal");
            require(relayerBefore.subtract(balance(endpoint, relayer)).equals(fee), "relayer physical balance differs from actual fees");
            require(Numeric.decodeQuantity(rpc(endpoint, "eth_getTransactionCount", List.of(authority, "latest")).asText()).equals(BigInteger.TWO), "authority activation nonce changed unexpectedly");
            Files.writeString(root.resolve("7702-component-" + number + "-public.json"), JSON.writeValueAsString(Map.of(
                    "scope", "Actual MPC codec/consensus only; formal wallet batch and principal accounting remain separate",
                    "passed", true, "pair", indices, "authority", authority, "relayer", relayer, "transactions", transactions,
                    "principalAtomic", AMOUNT.multiply(BigInteger.valueOf(4)).toString(), "feeAtomic", fee.toString())));
            System.out.println("EIP-7702 actual native codec pair=" + indices + ": authority/operation/relayer MPC, type4/type2 collection+payout, exact principal/fees and same-wire replay passed.");
        } finally { clients.forEach(MpcClient::close); }
    }

    private static byte[] sign(List<MpcClient> clients, UUID tenant, UUID key, byte[] publicKey, byte[] digest) throws Exception {
        var request = new MpcClient.Request(UUID.randomUUID(), tenant, key, 1, MpcClient.Operation.SIGN,
                MpcClient.Algorithm.ECDSA_SECP256K1, clients.stream().map(client -> client.node().partyId()).toList(), HexFormat.of().formatHex(digest));
        for (var future : clients.stream().map(client -> CompletableFuture.supplyAsync(() -> client.submit(request))).toList()) future.join();
        long until = System.nanoTime() + Duration.ofSeconds(25).toNanos();
        while (System.nanoTime() < until) {
            var results = clients.stream().map(client -> client.result(request)).toList();
            if (results.stream().allMatch(result -> result.state() == MpcClient.State.COMPLETED)) {
                var result = results.getFirst();
                require(HexFormat.of().formatHex(publicKey).equals(result.publicKeyHex()), "native joint key changed");
                require(result.equals(clients.getFirst().submit(request)), "idempotent original SIGN result changed");
                return hex(result.signatureHex());
            }
            require(results.stream().allMatch(result -> result.state() == MpcClient.State.RUNNING || result.state() == MpcClient.State.COMPLETED), "native signature became unknown");
            Thread.sleep(20);
        }
        throw new AssertionError("actual native component signature timed out");
    }

    private static String deploy(String endpoint, String admin, Path artifact, List<String> addresses) throws Exception {
        String bytecode = JSON.readTree(Files.readAllBytes(artifact)).path("bytecode").asText();
        require(bytecode.startsWith("0x") && bytecode.length() > 2, "missing compiled contract bytecode");
        String arguments = FunctionEncoder.encodeConstructor(addresses.stream().map(Address::new).map(v -> (org.web3j.abi.datatypes.Type)v).toList());
        String hash = rpc(endpoint, "eth_sendTransaction", List.of(Map.of("from", admin, "data", bytecode + Numeric.cleanHexPrefix(arguments), "gas", "0x7a1200"))).asText();
        JsonNode receipt = receipt(endpoint, hash);
        require("0x1".equals(receipt.path("status").asText()), "fixture contract deployment failed");
        return receipt.path("contractAddress").asText();
    }

    private static JsonNode receipt(String endpoint, String hash) throws Exception {
        long until = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < until) {
            JsonNode receipt = rpc(endpoint, "eth_getTransactionReceipt", List.of(hash));
            if (!receipt.isNull()) return receipt;
            Thread.sleep(20);
        }
        throw new AssertionError("isolated EVM receipt timed out");
    }
    private static BigInteger balance(String endpoint, String address) throws Exception {
        return Numeric.decodeQuantity(rpc(endpoint, "eth_getBalance", List.of(address, "latest")).asText());
    }
    private static JsonNode rpc(String endpoint, String method, List<?> params) throws Exception {
        var response = HTTP.send(HttpRequest.newBuilder(URI.create(endpoint)).timeout(Duration.ofSeconds(5)).header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(Map.of("jsonrpc", "2.0", "id", 1, "method", method, "params", params)))).build(), HttpResponse.BodyHandlers.ofByteArray());
        JsonNode result = JSON.readTree(response.body());
        require(response.statusCode() == 200, "isolated EVM transport failed: " + method);
        if (result.has("error")) throw new RpcRejected("isolated EVM rejected " + method);
        return result.path("result");
    }
    private static final class RpcRejected extends IllegalStateException { RpcRejected(String message) { super(message); } }
    private static byte[] hex(String value) { return HexFormat.of().parseHex(value); }
    private static String owner(byte[] key) { return "0x" + Keys.getAddress(Sign.publicFromPoint(ECKey.fromPublicOnly(key).decompress().getPubKey())); }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
