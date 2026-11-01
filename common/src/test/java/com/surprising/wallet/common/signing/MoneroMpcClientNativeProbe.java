package com.surprising.wallet.common.signing;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Runs the real Java transport and public verifier against isolated native Monero nodes. */
public final class MoneroMpcClientNativeProbe {
    private MoneroMpcClientNativeProbe() { }
    public static void main(String[] arguments) throws Exception {
        var json = new ObjectMapper();
        var root = Path.of(arguments[0]);
        var configuration = json.readTree(Files.readAllBytes(Path.of(arguments[1])));
        var request = json.readValue(json.writeValueAsBytes(configuration.path("request")), MpcClient.Request.class);
        if (request.algorithm() != MpcClient.Algorithm.MONERO_NATIVE_2_OF_3) throw new AssertionError("native Monero protocol required");
        var clients = new ArrayList<MpcClient>();
        try {
            for (var index : configuration.path("nodes")) {
                var peer = configuration.path("peers").get(index.asInt());
                var node = new MpcClient.Node(peer.path("identity").asText(), URI.create("https://localhost:" + peer.path("port").asInt()));
                clients.add(MpcClient.connect(node, root.resolve("ca.pem"), root.resolve("client.pem"), root.resolve("client.key"), Duration.ofSeconds(5)));
            }
            var submissions = clients.stream().map(client -> CompletableFuture.supplyAsync(() -> client.submit(request))).toList();
            for (var submission : submissions) submission.join();
            long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
            while (System.nanoTime() < deadline) {
                var results = new LinkedHashMap<String, MpcClient.Result>();
                for (var client : clients) results.put(client.node().partyId(), client.result(request));
                if (MoneroMpcResultVerifier.complete(request, results)) {
                    for (var client : clients) {
                        if (!client.submit(request).equals(results.get(client.node().partyId()))) throw new AssertionError("Java native receipt replay changed");
                    }
                    if (request.operation() == MpcClient.Operation.SIGN) {
                        var first = results.get(request.online().getFirst());
                        var incomplete = new LinkedHashMap<String, MpcClient.Result>();
                        incomplete.put(request.online().getFirst(), first);
                        if (MoneroMpcResultVerifier.complete(request, incomplete)) throw new AssertionError("one result satisfied native signing quorum");
                        var artifact = (ObjectNode) json.readTree(HexFormat.of().parseHex(first.signatureHex()));
                        ((ObjectNode) artifact.path("description").path("recipients").get(0)).put("amount", 1);
                        try {
                            MoneroMpcResultVerifier.artifact(request, first.publicKeyHex(), HexFormat.of().formatHex(json.writeValueAsBytes(artifact)));
                            throw new AssertionError("altered native recipient amount passed public verification");
                        } catch (IllegalArgumentException expected) { }
                    }
                    var output = json.createArrayNode();
                    for (var result : results.values()) {
                        ObjectNode value = json.valueToTree(result);
                        for (String field : List.of("signatureHex", "publicAddress", "monero")) if (value.path(field).isNull()) value.remove(field);
                        output.add(value);
                    }
                    System.out.println(json.writeValueAsString(output));
                    return;
                }
                if (results.values().stream().anyMatch(result -> result.state() != MpcClient.State.RUNNING && result.state() != MpcClient.State.COMPLETED))
                    throw new AssertionError("Java native Monero session did not complete");
                Thread.sleep(25);
            }
            throw new AssertionError("Java native Monero session timed out");
        } finally { clients.forEach(MpcClient::close); }
    }
}
