package com.surprising.wallet.common.signing;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import tools.jackson.databind.ObjectMapper;

/** Invoked by the isolated native-node fixture, not by an unconfigured unit-test run. */
public final class MpcClientNativeProbe {
    private MpcClientNativeProbe() { }

    public static void main(String[] arguments) throws Exception {
        var json = new ObjectMapper();
        var root = Path.of(arguments[0]);
        var configuration = json.readTree(Files.readAllBytes(root.resolve("java-probe.json")));
        UUID tenant = UUID.fromString(configuration.path("tenantId").asText());
        var clients = new ArrayList<MpcClient>();
        try {
            for (var peer : configuration.path("peers")) {
                var node = new MpcClient.Node(peer.path("identity").asText(),
                        URI.create("https://localhost:" + peer.path("port").asInt()));
                clients.add(MpcClient.connect(node, root.resolve("ca.pem"), root.resolve("client.pem"),
                        root.resolve("client.key"), Duration.ofSeconds(5)));
            }
            var parties = clients.stream().map(client -> client.node().partyId()).toList();
            var results = new ArrayList<Map<String, String>>();
            var random = new SecureRandom();
            for (var algorithm : List.of(MpcClient.Algorithm.ECDSA_SECP256K1,
                    MpcClient.Algorithm.ED25519, MpcClient.Algorithm.SCHNORR_BIP340)) {
                UUID key = UUID.randomUUID();
                var creation = new MpcClient.Request(UUID.randomUUID(), tenant, key, 1,
                        MpcClient.Operation.CREATE_KEY, algorithm, parties, "");
                var generated = execute(clients, creation);
                String publicKey = generated.getFirst().publicKeyHex();
                if (generated.stream().anyMatch(result -> !publicKey.equals(result.publicKeyHex()))) {
                    throw new AssertionError("Java client observed inconsistent DKG public keys");
                }
                for (var pair : List.of(List.of(0, 1), List.of(0, 2), List.of(1, 2))) {
                    var online = pair.stream().map(clients::get).toList();
                    byte[] message = new byte[algorithm == MpcClient.Algorithm.ED25519 ? 73 : 32];
                    random.nextBytes(message);
                    String messageHex = HexFormat.of().formatHex(message);
                    var signing = new MpcClient.Request(UUID.randomUUID(), tenant, key, 1,
                            MpcClient.Operation.SIGN, algorithm,
                            online.stream().map(client -> client.node().partyId()).toList(), messageHex);
                    var signed = execute(online, signing);
                    var result = signed.getFirst();
                    if (!result.equals(online.getFirst().submit(signing))) throw new AssertionError("Java idempotent result differs");
                    results.add(Map.of("algorithm", algorithm.name(), "publicKeyHex", publicKey,
                            "messageHex", messageHex, "signatureHex", result.signatureHex()));
                }
            }
            var pinProbe = new MpcClient.Request(UUID.randomUUID(), tenant, UUID.randomUUID(), 1,
                    MpcClient.Operation.CREATE_KEY, MpcClient.Algorithm.ED25519, parties, "");
            // The endpoint and its CA/hostname are valid, but its SPKI belongs
            // to a different registered peer. It must fail before HTTP submit.
            var wrongNode = new MpcClient.Node(parties.get(1), clients.getFirst().node().endpoint());
            try (var wrong = MpcClient.connect(wrongNode, root.resolve("ca.pem"), root.resolve("client.pem"),
                    root.resolve("client.key"), Duration.ofSeconds(5))) {
                try {
                    wrong.submit(pinProbe);
                    throw new AssertionError("Java client accepted a different peer SPKI");
                } catch (MpcClient.CallException expected) {
                    if (clients.getFirst().result(pinProbe).state() != MpcClient.State.ABSENT) {
                        throw new AssertionError("SPKI pinning happened after request submission");
                    }
                }
            }
            System.out.println(json.writeValueAsString(results));
        } finally {
            clients.forEach(MpcClient::close);
        }
    }

    private static List<MpcClient.Result> execute(List<MpcClient> clients, MpcClient.Request request) throws Exception {
        var submissions = clients.stream().map(client -> CompletableFuture.supplyAsync(() -> client.submit(request))).toList();
        for (var submission : submissions) submission.join();
        long deadline = System.nanoTime() + Duration.ofSeconds(25).toNanos();
        while (System.nanoTime() < deadline) {
            var results = clients.stream().map(client -> client.result(request)).toList();
            if (results.stream().allMatch(result -> result.state() == MpcClient.State.COMPLETED)) return results;
            if (results.stream().anyMatch(result -> result.state() != MpcClient.State.RUNNING
                    && result.state() != MpcClient.State.COMPLETED)) {
                throw new AssertionError("Java MPC session did not complete");
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Java MPC result timed out");
    }
}
