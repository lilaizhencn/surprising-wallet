package com.surprising.wallet.service.config;

import com.surprising.wallet.common.chain.ChainRpcNode;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChainRpcNodeService {
    private final ChainJdbcRepository repository;
    private final Map<String, AtomicLong> lastRequestMillisByProvider = new ConcurrentHashMap<>();
    private final Map<String, Semaphore> providerLimiters = new ConcurrentHashMap<>();

    @Value("${sw.app.env.name:dev}")
    private String environmentName;

    @Value("${sw.rpc.provider.max-concurrent-requests:1}")
    private int maxConcurrentRequestsPerProvider;

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
                return withProviderLimit(node, request);
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

    private <T> T withProviderLimit(ChainRpcNode node, Function<ChainRpcNode, T> request) {
        try {
            return withProviderLimit(node, () -> request.apply(node));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("RPC provider limited request failed", e);
        }
    }

    public <T> T withProviderLimit(ChainRpcNode node, ProviderLimitedRequest<T> request) throws Exception {
        String providerKey = providerKey(node);
        Semaphore limiter = providerLimiters.computeIfAbsent(
                providerKey,
                ignored -> new Semaphore(Math.max(1, maxConcurrentRequestsPerProvider), true));
        acquireProviderPermit(providerKey, limiter);
        try {
            throttle(providerKey, node);
            return request.execute();
        } finally {
            limiter.release();
        }
    }

    private static void acquireProviderPermit(String providerKey, Semaphore limiter) {
        try {
            limiter.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("RPC provider concurrency wait interrupted: " + providerKey, e);
        }
    }

    @FunctionalInterface
    public interface ProviderLimitedRequest<T> {
        T execute() throws Exception;
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

    private void throttle(String providerKey, ChainRpcNode node) {
        int intervalMs = node.getMinRequestIntervalMs() == null ? 0 : node.getMinRequestIntervalMs();
        if (intervalMs <= 0) {
            return;
        }
        AtomicLong lastRequestMillis = lastRequestMillisByProvider.computeIfAbsent(
                providerKey, ignored -> new AtomicLong(0L));
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

    private static String providerKey(ChainRpcNode node) {
        String fromLabel = providerFromText(node.getNodeLabel());
        if (!fromLabel.isBlank()) {
            return fromLabel;
        }
        String fromUrl = providerFromText(node.getRpcUrl());
        if (!fromUrl.isBlank()) {
            return fromUrl;
        }
        String host = host(node.getRpcUrl());
        if (host.isBlank()) {
            return "node:" + (node.getId() == null ? "unknown" : node.getId());
        }
        return "host:" + rootDomain(host);
    }

    private static String providerFromText(String value) {
        String text = trim(value).toLowerCase(Locale.ROOT);
        if (text.isBlank()) {
            return "";
        }
        if (text.contains("alchemy")) {
            return "alchemy";
        }
        if (text.contains("infura")) {
            return "infura";
        }
        if (text.contains("publicnode")) {
            return "publicnode";
        }
        if (text.contains("nownodes")) {
            return "nownodes";
        }
        if (text.contains("getblock")) {
            return "getblock";
        }
        if (text.contains("blockpi")) {
            return "blockpi";
        }
        if (text.contains("drpc")) {
            return "drpc";
        }
        if (text.contains("quicknode")) {
            return "quicknode";
        }
        if (text.contains("chainstack")) {
            return "chainstack";
        }
        if (text.contains("ankr")) {
            return "ankr";
        }
        if (text.contains("blastapi")) {
            return "blastapi";
        }
        if (text.contains("llamarpc")) {
            return "llamarpc";
        }
        if (text.contains("cloudflare")) {
            return "cloudflare";
        }
        if (text.contains("tatum")) {
            return "tatum";
        }
        if (text.contains("monero")) {
            return "monero";
        }
        if (text.contains("xrpl") || text.contains("ripple")) {
            return "xrpl";
        }
        if (text.contains("toncenter")) {
            return "toncenter";
        }
        if (text.contains("aptoslabs")) {
            return "aptoslabs";
        }
        if (text.contains("trongrid")) {
            return "trongrid";
        }
        return "";
    }

    private static String host(String rpcUrl) {
        String value = trim(rpcUrl);
        if (value.isBlank()) {
            return "";
        }
        try {
            URI uri = URI.create(value);
            String host = uri.getHost();
            if (host == null && !value.contains("://")) {
                host = URI.create("https://" + value).getHost();
            }
            return host == null ? "" : host.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    private static String rootDomain(String host) {
        String normalized = host.startsWith("www.") ? host.substring(4) : host;
        String[] parts = normalized.split("\\.");
        if (parts.length <= 2 || normalized.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) {
            return normalized;
        }
        return parts[parts.length - 2] + "." + parts[parts.length - 1];
    }
}
