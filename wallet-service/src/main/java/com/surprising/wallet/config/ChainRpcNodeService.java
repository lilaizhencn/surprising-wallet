package com.surprising.wallet.config;

import com.surprising.wallet.common.chain.ChainRpcNode;
import com.surprising.wallet.deposit.repository.ChainJdbcRepository;
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

/**
 * 链 RPC 节点服务，提供节点发现、故障转移、并发限制与速率控制。
 *
 * <p>核心功能：</p>
 * <ul>
 *   <li>从数据库加载已启用的 RPC 节点列表</li>
 *   <li>按优先级排序后依次尝试，遇到失败自动切换到下一个节点（故障转移）</li>
 *   <li>对同一 provider 的并发请求做信号量限流</li>
 *   <li>基于最小请求间隔的速率节流</li>
 *   <li>自动拼接认证头（Bearer Token 或 Basic Auth）</li>
 * </ul>
 *
 * @see ChainJdbcRepository
 * @see ChainRpcNode
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChainRpcNodeService {
    private final ChainJdbcRepository repository;

    /** 按 provider key 记录上次请求时间戳，用于速率节流 */
    private final Map<String, AtomicLong> lastRequestMillisByProvider = new ConcurrentHashMap<>();

    /** 按 provider key 维护的并发信号量 */
    private final Map<String, Semaphore> providerLimiters = new ConcurrentHashMap<>();

    /** 当前部署环境名称 */
    @Value("${sw.app.env.name:dev}")
    private String environmentName;

    /** 每个 provider 最大并发请求数 */
    @Value("${sw.rpc.provider.max-concurrent-requests:1}")
    private int maxConcurrentRequestsPerProvider;

    /**
     * 获取指定链/网络的所有已启用 RPC 节点（通用用途）。
     *
     * @param chain   链名称
     * @param network 网络名称
     * @return 已启用的节点列表，按优先级排序
     */
    public List<ChainRpcNode> enabledNodes(String chain, String network) {
        return repository.listEnabledRpcNodes(chain, network, environmentName).stream()
                .filter(node -> node.getRpcUrl() != null && !node.getRpcUrl().isBlank())
                .toList();
    }

    /**
     * 获取指定链/网络/用途的所有已启用 RPC 节点。
     *
     * @param chain   链名称
     * @param network 网络名称
     * @param purpose 用途（如 rpc, scan, broadcast 等）
     * @return 已启用的节点列表，按优先级排序
     */
    public List<ChainRpcNode> enabledNodes(String chain, String network, String purpose) {
        return repository.listEnabledRpcNodes(chain, network, environmentName, purpose).stream()
                .filter(node -> node.getRpcUrl() != null && !node.getRpcUrl().isBlank())
                .toList();
    }
    /**
     * 获取主 RPC URL（优先级最高的已启用节点）。
     *
     * @param chain   链名称
     * @param network 网络名称
     * @return 主 RPC URL
     * @throws IllegalStateException 如果没有可用节点
     */
    public String primaryRpcUrl(String chain, String network) {
        List<ChainRpcNode> nodes = enabledNodes(chain, network);
        if (nodes.isEmpty()) {
            throw new IllegalStateException("missing enabled rpc node for " + chain + "/" + network);
        }
        return nodes.get(0).getRpcUrl();
    }
    /**
     * 带故障转移执行 RPC 请求（默认用途），依次尝试所有可用节点直到成功。
     *
     * @param chain   链名称
     * @param network 网络名称
     * @param request 请求函数，接收节点并返回结果
     * @param <T>     返回类型
     * @return 请求结果
     * @throws IllegalStateException 如果所有节点都失败
     */
    public <T> T withFailover(String chain, String network, Function<ChainRpcNode, T> request) {
        return withFailover(chain, network, "rpc", request);
    }

    /**
     * 带故障转移执行 RPC 请求（指定用途），依次尝试所有可用节点直到成功。
     *
     * @param chain   链名称
     * @param network 网络名称
     * @param purpose 用途
     * @param request 请求函数
     * @param <T>     返回类型
     * @return 请求结果
     * @throws IllegalStateException 如果所有节点都失败
     */
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
    /**
     * 对单个 provider 执行请求，包含并发限制和速率节流保护。
     *
     * @param node    目标 RPC 节点
     * @param request 请求函数
     * @param <T>     返回类型
     * @return 请求结果
     */
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

    /**
     * Provider 限流请求函数式接口。
     *
     * @param <T> 返回类型
     */
    @FunctionalInterface
    public interface ProviderLimitedRequest<T> {
        T execute() throws Exception;
    }

    /**
     * 构造认证请求头，支持 Bearer Token 和 Basic Auth 两种方式。
     *
     * @param node RPC 节点
     * @return 认证请求头映射
     */
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
    /**
     * 将认证头附加到 HTTP 请求构建器。
     *
     * @param builder HTTP 请求构建器
     * @param node    RPC 节点
     * @return 更新后的构建器
     */
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
