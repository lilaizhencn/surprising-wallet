package com.surprising.wallet.chain.cardano;

import com.bloxbean.cardano.client.api.exception.ApiException;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.backend.api.BackendService;
import com.bloxbean.cardano.client.backend.blockfrost.common.Constants;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainRpcNode;
import com.surprising.wallet.config.ChainRpcNodeService;
import com.surprising.wallet.deposit.repository.ChainJdbcRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Cardano 链后端 API 客户端，封装对 Blockfrost API 的调用。
 *
 * <p>使用 <code>com.bloxbean.cardano.client</code> 库的 {@link com.bloxbean.cardano.client.backend.api.BackendService}
 * 接口，自动根据网络（mainnet/preview/preprod）选择对应的 Blockfrost URL。</p>
 *
 * <p>提供统一的结果校验方法 {@link #requireSuccess} 和 404 判断方法 {@link #isNotFound}。</p>
 */
@Component
@RequiredArgsConstructor
public
class CardanoBackendClient {

    /** 链标识常量 */
    static final String CHAIN = "ADA";

    /** 数据库仓库 */
    private final ChainJdbcRepository repository;

    /** RPC 节点故障转移服务 */
    private final ChainRpcNodeService rpcNodeService;
    public <T> T withBackend(BackendRequest<T> request) {
        AccountChainProfile profile = repository.findProfileByChain(CHAIN)
                .orElseThrow(() -> new IllegalStateException("missing enabled chain_profile for ADA"));
        return rpcNodeService.withFailover(CHAIN, profile.getNetwork(), node -> {
            BackendService backend = backend(node, profile.getNetwork());
            try {
                return request.execute(backend, node, profile);
            } catch (ApiException e) {
                throw new IllegalStateException("Cardano backend API failed: " + e.getMessage(), e);
            }
        });
    }
    public static <T> T requireSuccess(Result<T> result, String operation) {
        if (result == null) {
            throw new IllegalStateException("Cardano " + operation + " returned no result");
        }
        if (!result.isSuccessful()) {
            throw new IllegalStateException("Cardano " + operation + " failed: " + result.getResponse());
        }
        return result.getValue();
    }
    static boolean isNotFound(Result<?> result) {
        return result != null && !result.isSuccessful() && result.code() == 404;
    }
    private BackendService backend(ChainRpcNode node, String network) {
        String apiKey = node.getApiKey() == null ? "" : node.getApiKey().trim();
        if (apiKey.isBlank()) {
            throw new IllegalStateException("Cardano Blockfrost node is missing api_key: " + node.getNodeLabel());
        }
        return new BFBackendService(blockfrostUrl(node.getRpcUrl(), network), apiKey);
    }
    private static String blockfrostUrl(String configuredUrl, String network) {
        String value = configuredUrl == null ? "" : configuredUrl.trim();
        if (!value.isBlank()) {
            return value;
        }
        return switch ((network == null ? "" : network).toLowerCase(Locale.ROOT)) {
            case "mainnet" -> Constants.BLOCKFROST_MAINNET_URL;
            case "preview" -> Constants.BLOCKFROST_PREVIEW_URL;
            case "preprod", "testnet" -> Constants.BLOCKFROST_PREPROD_URL;
            default -> throw new IllegalStateException("unsupported Cardano network: " + network);
        };
    }

    @FunctionalInterface
    public interface BackendRequest<T> {
        T execute(BackendService backend, ChainRpcNode node, AccountChainProfile profile) throws ApiException;
    }
}
