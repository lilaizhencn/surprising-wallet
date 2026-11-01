package com.surprising.wallet.chain.signing;

import com.surprising.wallet.common.signing.MpcClient;
import com.surprising.wallet.model.mpc.MpcNodeProperties;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** Owns the configured mTLS clients for the fixed three-party signing group. */
@Component
public class MpcNodes implements AutoCloseable {
    private final List<MpcClient> clients;
    private final List<String> parties;
    private final Duration operationTimeout;

    public MpcNodes(MpcNodeProperties properties) {
        Objects.requireNonNull(properties.getCaFile(), "MPC CA file is required");
        Objects.requireNonNull(properties.getCertificateFile(), "MPC client certificate is required");
        Objects.requireNonNull(properties.getPrivateKeyFile(), "MPC client transport key is required");
        var configured = Objects.requireNonNull(properties.getNodes(), "MPC nodes are required").stream()
                .map(node -> new MpcClient.Node(node.partyId(), node.endpoint())).toList();
        if (configured.size() != 3 || configured.stream().map(MpcClient.Node::partyId).distinct().count() != 3
                || configured.stream().map(MpcClient.Node::endpoint).distinct().count() != 3) {
            throw new IllegalArgumentException("MPC requires three distinct node identities and endpoints");
        }
        operationTimeout = Objects.requireNonNull(properties.getOperationTimeout(), "MPC operation timeout is required");
        if (!operationTimeout.isPositive() || operationTimeout.compareTo(Duration.ofMinutes(2)) > 0) {
            throw new IllegalArgumentException("MPC operation timeout must be positive and at most two minutes");
        }
        var connected = new ArrayList<MpcClient>();
        try {
            for (var node : configured) connected.add(MpcClient.connect(node, properties.getCaFile(),
                    properties.getCertificateFile(), properties.getPrivateKeyFile(), properties.getCallTimeout()));
            clients = List.copyOf(connected);
        } catch (RuntimeException failure) {
            connected.forEach(MpcClient::close);
            throw failure;
        }
        parties = configured.stream().map(MpcClient.Node::partyId).toList();
    }

    public List<MpcClient> clients() { return clients; }
    public List<String> parties() { return parties; }
    public List<String> online() { return parties.subList(0, 2); }
    public Duration operationTimeout() { return operationTimeout; }
    @Override @PreDestroy public void close() { clients.forEach(MpcClient::close); }
}
