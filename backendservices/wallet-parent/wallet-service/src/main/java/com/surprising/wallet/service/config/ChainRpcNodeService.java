package com.surprising.wallet.service.config;

import com.surprising.wallet.common.chain.ChainRpcNode;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChainRpcNodeService {
    private final ChainJdbcRepository repository;
    private final Map<Long, AtomicLong> lastRequestMillisByNode = new ConcurrentHashMap<>();

    @Value("${sw.app.env.name:dev}")
    private String environmentName;

    public List<ChainRpcNode> enabledNodes(String chain, String network) {
        return repository.listEnabledRpcNodes(chain, network, environmentName).stream()
                .filter(node -> node.getRpcUrl() != null && !node.getRpcUrl().isBlank())
                .toList();
    }

    public List<ChainRpcNode> enabledNodes(String chain, String network, String purpose) {
        return repository.listEnabledRpcNodes(chain, network, environmentName, purpose).stream()
                .filter(node -> node.getRpcUrl() != null && !node.getRpcUrl().isBlank())
                .toList();
    }

    public String primaryRpcUrl(String chain, String network) {
        List<ChainRpcNode> nodes = enabledNodes(chain, network);
        if (nodes.isEmpty()) {
            throw new IllegalStateException("missing enabled rpc node for " + chain + "/" + network);
        }
        return nodes.get(0).getRpcUrl();
    }

    public <T> T withFailover(String chain, String network, Function<ChainRpcNode, T> request) {
        return withFailover(chain, network, "rpc", request);
    }

    public <T> T withFailover(String chain, String network, String purpose, Function<ChainRpcNode, T> request) {
        List<ChainRpcNode> nodes = enabledNodes(chain, network, purpose);
        if (nodes.isEmpty()) {
            throw new IllegalStateException(
                    "missing enabled rpc node for " + chain + "/" + network + " purpose=" + purpose);
        }
        RuntimeException last = null;
        for (ChainRpcNode node : nodes) {
            try {
                throttle(node);
                return request.apply(node);
            } catch (RuntimeException e) {
                last = e;
                if (nodes.size() == 1) {
                    throw e;
                }
                log.warn("RPC node failed, trying next: chain={} network={} label={} url={} error={}",
                        chain, network, node.getNodeLabel(), node.getRpcUrl(), e.getMessage());
            }
        }
        throw last == null
                ? new IllegalStateException("all rpc nodes failed for " + chain + "/" + network)
                : last;
    }

    public Map<String, String> authHeaders(ChainRpcNode node) {
        Map<String, String> headers = new LinkedHashMap<>();
        String apiKey = trim(node.getApiKey());
        if (!apiKey.isBlank()) {
            String headerName = trim(node.getAuthHeaderName());
            if (headerName.isBlank()) {
                headerName = "Authorization";
            }
            String authType = trim(node.getAuthType());
            String value = "bearer".equalsIgnoreCase(authType) ? "Bearer " + apiKey : apiKey;
            headers.put(headerName, value);
        }
        String username = trim(node.getUsername());
        String password = trim(node.getPassword());
        if (!username.isBlank() || !password.isBlank()) {
            String token = Base64.getEncoder().encodeToString((username + ":" + password)
                    .getBytes(StandardCharsets.UTF_8));
            headers.put("Authorization", "Basic " + token);
        }
        return headers;
    }

    public HttpRequest.Builder applyAuthHeaders(HttpRequest.Builder builder, ChainRpcNode node) {
        authHeaders(node).forEach(builder::header);
        return builder;
    }

    private void throttle(ChainRpcNode node) {
        int intervalMs = node.getMinRequestIntervalMs() == null ? 0 : node.getMinRequestIntervalMs();
        if (intervalMs <= 0) {
            return;
        }
        Long nodeId = node.getId() == null ? Long.MIN_VALUE : node.getId();
        AtomicLong lastRequestMillis = lastRequestMillisByNode.computeIfAbsent(nodeId, ignored -> new AtomicLong(0L));
        while (true) {
            long now = System.currentTimeMillis();
            long previous = lastRequestMillis.get();
            long nextAllowed = previous + intervalMs;
            long waitMs = nextAllowed - now;
            if (waitMs > 0) {
                sleep(waitMs);
            }
            long candidate = Math.max(System.currentTimeMillis(), nextAllowed);
            if (lastRequestMillis.compareAndSet(previous, candidate)) {
                return;
            }
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("RPC rate limit sleep interrupted", e);
        }
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
