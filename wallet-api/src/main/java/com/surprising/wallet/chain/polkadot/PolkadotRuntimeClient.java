package com.surprising.wallet.chain.polkadot;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainRpcNode;
import com.surprising.wallet.common.chain.TokenDefinition;
import com.surprising.wallet.config.ChainRpcNodeService;
import com.surprising.wallet.deposit.repository.ChainJdbcRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Polkadot 运行时客户端，通过 Sidecar/Substrate 运行时 HTTP API 与链交互。
 *
 * <p>不直接调用 Substrate RPC，而是通过一个中间运行时服务（TypeScript/Deno 实现）
 * 完成交易签名和广播。支持三条目的线：原生 RPC（relay chain）、Asset Hub RPC、
 * 和运行时服务本身的故障转移。</p>
 *
 * <p>提供区块高度查询、余额查询、资产信息、原生/资产转账、转账扫描等功能。</p>
 */
@Component
@RequiredArgsConstructor
public
class PolkadotRuntimeClient {

    /** 链标识 */
    static final String CHAIN = "DOT";

    /** 中继链 RPC 目的标识 */
    private static final String PURPOSE_NATIVE_RPC = "rpc";

    /** Asset Hub RPC 目的标识 */
    private static final String PURPOSE_ASSET_RPC = "asset_rpc";

    /** 运行时服务目的标识 */
    private static final String PURPOSE_RUNTIME = "runtime";

    /** 数据库仓库 */
    private final ChainJdbcRepository repository;

    /** RPC 节点故障转移服务 */
    private final ChainRpcNodeService rpcNodeService;
    /**
     * 保存 {@code objectMapper}，用于保存业务集合或索引状态。
     */
    private final ObjectMapper objectMapper = new ObjectMapper();
    /**
     * 保存 {@code httpClient}，用于访问当前业务所依赖的仓储、客户端或服务。
     */
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    /**
     * 执行 {@code latestFinalizedHeight} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public long latestFinalizedHeight() {
        return latestFinalizedHeight(PURPOSE_NATIVE_RPC);
    }
    /**
     * 执行 {@code latestAssetHubFinalizedHeight} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public long latestAssetHubFinalizedHeight() {
        return latestFinalizedHeight(PURPOSE_ASSET_RPC);
    }
    /**
     * 执行 {@code nativeBalance} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public BigInteger nativeBalance(String address) {
        return nativeBalance(address, PURPOSE_NATIVE_RPC);
    }
    /**
     * 获取或查询 {@code assetHubNativeBalance} 对应的数据，并向调用方返回当前业务状态。
     */
    public BigInteger assetHubNativeBalance(String address) {
        return nativeBalance(address, PURPOSE_ASSET_RPC);
    }
    /**
     * 获取或查询 {@code assetBalance} 对应的数据，并向调用方返回当前业务状态。
     */
    public BigInteger assetBalance(String assetId, String address) {
        ObjectNode body = baseBody();
        body.put("ss58Prefix", ss58Prefix(profile()));
        body.put("assetId", normalizeAssetId(assetId));
        body.put("address", address);
        return amountPlanck(callRuntime("/v1/polkadot/asset-balance", PURPOSE_ASSET_RPC, body)
                .path("balance"));
    }
    /**
     * 获取或查询 {@code assetInfo} 对应的数据，并向调用方返回当前业务状态。
     */
    public AssetInfo assetInfo(String assetId) {
        ObjectNode body = baseBody();
        body.put("assetId", normalizeAssetId(assetId));
        JsonNode result = callRuntime("/v1/polkadot/asset-info", PURPOSE_ASSET_RPC, body);
        return new AssetInfo(result.path("assetId").asText(),
                result.path("exists").asBoolean(false),
                amountPlanck(result.path("supply")),
                amountPlanck(result.path("minBalance")),
                result.path("isSufficient").asBoolean(false),
                result.path("name").asText(""),
                result.path("symbol").asText(""),
                result.path("decimals").asInt(0));
    }

    /**
     * 构建或生成 {@code createAsset} 对应的结果，并执行输入和状态校验。
     */
    public AssetCreateResult createAsset(String secretSeedHex, String expectedFrom,
                                         String assetId, String name, String symbol,
                                         int decimals, BigInteger minBalance,
                                         BigInteger initialSupply, boolean mintable) {
        ObjectNode body = baseBody();
        body.put("ss58Prefix", ss58Prefix(profile()));
        body.put("secretSeedHex", secretSeedHex);
        body.put("expectedFrom", expectedFrom);
        body.put("assetId", normalizeAssetId(assetId));
        body.put("name", name);
        body.put("symbol", symbol);
        body.put("decimals", decimals);
        body.put("minBalance", minBalance.toString());
        body.put("initialSupply", initialSupply.toString());
        body.put("mintable", mintable);
        body.put("waitFinalized", true);
        JsonNode result = callRuntime("/v1/polkadot/asset-create", PURPOSE_ASSET_RPC, body);
        SubmittedTransaction submitted = submitted(result);
        return new AssetCreateResult(submitted.txHash(),
                submitted.blockHeight(),
                submitted.status(),
                result.path("assetId").asText(assetId),
                result.toString());
    }

    /**
     * 扫描或观察 {@code scanNativeTransfers} 对应的链上状态，并转换为业务可用结果。
     */
    public List<TransferEvent> scanNativeTransfers(long fromBlock, long toBlock,
                                                   Collection<String> addresses) {
        return scanTransfers(PURPOSE_NATIVE_RPC, fromBlock, toBlock, addresses, List.of(), true, false);
    }

    /**
     * 扫描或观察 {@code scanAssetTransfers} 对应的链上状态，并转换为业务可用结果。
     */
    public List<TransferEvent> scanAssetTransfers(long fromBlock, long toBlock,
                                                  Collection<String> addresses,
                                                  Map<String, TokenDefinition> tokensByAssetId) {
        return scanTransfers(PURPOSE_ASSET_RPC, fromBlock, toBlock, addresses,
                tokensByAssetId.keySet(), false, true);
    }
    /**
     * 执行 {@code latestFinalizedHeight} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private long latestFinalizedHeight(String rpcPurpose) {
        return callRuntime("/v1/polkadot/latest-finalized", rpcPurpose, baseBody()).path("height").asLong();
    }
    /**
     * 执行 {@code nativeBalance} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private BigInteger nativeBalance(String address, String rpcPurpose) {
        ObjectNode body = baseBody();
        body.put("ss58Prefix", ss58Prefix(profile()));
        body.put("address", address);
        return amountPlanck(callRuntime("/v1/polkadot/native-balance", rpcPurpose, body)
                .path("free"));
    }

    /**
     * 扫描或观察 {@code scanTransfers} 对应的链上状态，并转换为业务可用结果。
     */
    private List<TransferEvent> scanTransfers(String rpcPurpose, long fromBlock, long toBlock,
                                              Collection<String> addresses,
                                              Collection<String> assetIds,
                                              boolean includeNative,
                                              boolean includeAssets) {
        ObjectNode body = baseBody();
        body.put("fromBlock", fromBlock);
        body.put("toBlock", toBlock);
        body.put("ss58Prefix", ss58Prefix(profile()));
        body.put("includeNative", includeNative);
        body.put("includeAssets", includeAssets);
        ArrayNode addressArray = objectMapper.createArrayNode();
        addresses.forEach(addressArray::add);
        body.set("addresses", addressArray);
        ArrayNode tokenArray = objectMapper.createArrayNode();
        assetIds.forEach(tokenArray::add);
        body.set("assetIds", tokenArray);
        JsonNode result = callRuntime("/v1/polkadot/scan-transfers", rpcPurpose, body);
        List<TransferEvent> events = new ArrayList<>();
        for (JsonNode item : result.path("transfers")) {
            events.add(new TransferEvent(
                    item.path("txHash").asText(),
                    item.path("from").asText(),
                    item.path("to").asText(),
                    amountPlanck(item.path("amountPlanck")),
                    item.path("blockHeight").asLong(),
                    item.path("eventIndex").asLong(),
                    trim(item.path("assetId").asText(null)),
                    item.toString()));
        }
        return events;
    }

    /**
     * 发送或广播 {@code sendNative} 对应的链上请求，并返回节点处理结果。
     */
    public SubmittedTransaction sendNative(String secretSeedHex, String expectedFrom,
                                           String toAddress, BigInteger amountPlanck) {
        return sendNative(secretSeedHex, expectedFrom, toAddress, amountPlanck, true);
    }

    /**
     * 发送或广播 {@code sendNative} 对应的链上请求，并返回节点处理结果。
     */
    public SubmittedTransaction sendNative(String secretSeedHex, String expectedFrom,
                                           String toAddress, BigInteger amountPlanck,
                                           boolean keepAlive) {
        return sendNative(secretSeedHex, expectedFrom, toAddress, amountPlanck, keepAlive, PURPOSE_NATIVE_RPC);
    }

    /**
     * 发送或广播 {@code sendAssetHubNative} 对应的链上请求，并返回节点处理结果。
     */
    public SubmittedTransaction sendAssetHubNative(String secretSeedHex, String expectedFrom,
                                                   String toAddress, BigInteger amountPlanck,
                                                   boolean keepAlive) {
        return sendNative(secretSeedHex, expectedFrom, toAddress, amountPlanck, keepAlive, PURPOSE_ASSET_RPC);
    }

    /**
     * 发送或广播 {@code sendNative} 对应的链上请求，并返回节点处理结果。
     */
    private SubmittedTransaction sendNative(String secretSeedHex, String expectedFrom,
                                            String toAddress, BigInteger amountPlanck,
                                            boolean keepAlive, String rpcPurpose) {
        ObjectNode body = baseBody();
        body.put("ss58Prefix", ss58Prefix(profile()));
        body.put("secretSeedHex", secretSeedHex);
        body.put("expectedFrom", expectedFrom);
        body.put("to", toAddress);
        body.put("amountPlanck", amountPlanck.toString());
        body.put("keepAlive", keepAlive);
        body.put("waitFinalized", true);
        JsonNode result = callRuntime("/v1/polkadot/transfer", rpcPurpose, body);
        return submitted(result);
    }

    /**
     * 发送或广播 {@code sendAsset} 对应的链上请求，并返回节点处理结果。
     */
    public SubmittedTransaction sendAsset(String secretSeedHex, String expectedFrom,
                                          String assetId, String toAddress, BigInteger amountAtomic) {
        return sendAsset(secretSeedHex, expectedFrom, assetId, toAddress, amountAtomic, true);
    }

    /**
     * 发送或广播 {@code sendAsset} 对应的链上请求，并返回节点处理结果。
     */
    public SubmittedTransaction sendAsset(String secretSeedHex, String expectedFrom,
                                          String assetId, String toAddress, BigInteger amountAtomic,
                                          boolean keepAlive) {
        ObjectNode body = baseBody();
        body.put("ss58Prefix", ss58Prefix(profile()));
        body.put("secretSeedHex", secretSeedHex);
        body.put("expectedFrom", expectedFrom);
        body.put("assetId", assetId);
        body.put("to", toAddress);
        body.put("amount", amountAtomic.toString());
        body.put("keepAlive", keepAlive);
        body.put("waitFinalized", true);
        JsonNode result = callRuntime("/v1/polkadot/asset-transfer", PURPOSE_ASSET_RPC, body);
        return submitted(result);
    }
    /**
     * 执行 {@code transactionFinalized} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public boolean transactionFinalized(String txHash, int maxRecentBlocks) {
        return transactionFinalized(txHash, maxRecentBlocks, PURPOSE_NATIVE_RPC);
    }
    /**
     * 获取或查询 {@code assetTransactionFinalized} 对应的数据，并向调用方返回当前业务状态。
     */
    public boolean assetTransactionFinalized(String txHash, int maxRecentBlocks) {
        return transactionFinalized(txHash, maxRecentBlocks, PURPOSE_ASSET_RPC);
    }
    /**
     * 执行 {@code transactionFinalized} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private boolean transactionFinalized(String txHash, int maxRecentBlocks, String rpcPurpose) {
        ObjectNode body = baseBody();
        body.put("txHash", txHash);
        body.put("maxRecentBlocks", maxRecentBlocks);
        return callRuntime("/v1/polkadot/transaction-status", rpcPurpose, body)
                .path("finalized").asBoolean(false);
    }
    /**
     * 执行 {@code callRuntime} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private JsonNode callRuntime(String path, String rpcPurpose, ObjectNode body) {
        AccountChainProfile profile = profile();
        List<ChainRpcNode> substrateNodes = rpcNodeService.enabledNodes(CHAIN, profile.getNetwork(), rpcPurpose);
        if (substrateNodes.isEmpty()) {
            throw new IllegalStateException("missing enabled Polkadot substrate rpc node for purpose=" + rpcPurpose);
        }
        RuntimeException last = null;
        for (ChainRpcNode substrateNode : substrateNodes) {
            try {
                return rpcNodeService.withProviderLimit(substrateNode, () -> {
                    ObjectNode attemptBody = body.deepCopy();
                    attemptBody.put("rpcUrl", substrateNode.getRpcUrl());
                    return rpcNodeService.withFailover(CHAIN, profile.getNetwork(), PURPOSE_RUNTIME,
                            node -> execute(node, path, attemptBody));
                });
            } catch (RuntimeException e) {
                last = e;
            } catch (Exception e) {
                last = new IllegalStateException("Polkadot runtime substrate request failed", e);
            }
        }
        throw last == null
                ? new IllegalStateException("all Polkadot substrate rpc nodes failed for purpose=" + rpcPurpose)
                : last;
    }
    /**
     * 执行或处理 {@code execute} 对应的业务流程，并维护状态和异常边界。
     */
    private JsonNode execute(ChainRpcNode node, String path, ObjectNode body) {
        try {
            String baseUrl = trim(node.getRpcUrl());
            String requestBody = objectMapper.writeValueAsString(body);
            for (int attempt = 1; attempt <= 4; attempt++) {
                HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                        .timeout(Duration.ofSeconds(90))
                        .header("content-type", "application/json")
                        .header("accept", "application/json")
                        .header("user-agent", "surprising-wallet/1.0")
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody));
                rpcNodeService.applyAuthHeaders(builder, node);
                HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
                if ((response.statusCode() == 429 || response.statusCode() / 100 == 5) && attempt < 4) {
                    Thread.sleep(attempt * 1_000L);
                    continue;
                }
                if (response.statusCode() / 100 != 2) {
                    throw new IllegalStateException("Polkadot runtime HTTP " + response.statusCode()
                            + ": " + abbreviate(response.body()));
                }
                JsonNode json = objectMapper.readTree(response.body());
                if (!json.path("ok").asBoolean(false)) {
                    throw new IllegalStateException("Polkadot runtime failed: "
                            + abbreviate(json.path("error").asText(json.toString())));
                }
                return json.path("result");
            }
            throw new IllegalStateException("Polkadot runtime retry loop exhausted: " + path);
        } catch (IOException e) {
            throw new IllegalStateException("Polkadot runtime IO failed: " + path, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Polkadot runtime interrupted: " + path, e);
        }
    }
    /**
     * 执行 {@code baseBody} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private ObjectNode baseBody() {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("chain", CHAIN);
        return body;
    }
    /**
     * 获取或查询 {@code profile} 对应的数据，并向调用方返回当前业务状态。
     */
    private AccountChainProfile profile() {
        return repository.findProfileByChain(CHAIN)
                .orElseThrow(() -> new IllegalStateException("missing enabled chain_profile for " + CHAIN));
    }
    /**
     * 执行 {@code ss58Prefix} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    static int ss58Prefix(AccountChainProfile profile) {
        if (profile.getChainId() != null && profile.getChainId() >= 0 && profile.getChainId() <= 16_383) {
            return profile.getChainId().intValue();
        }
        String network = trim(profile.getNetwork()).toLowerCase(Locale.ROOT);
        if ("mainnet".equals(network) || "polkadot".equals(network)) {
            return 0;
        }
        return 42;
    }
    /**
     * 转换或计算 {@code normalizeAssetId} 对应的值，统一金额、格式和边界规则。
     */
    static String normalizeAssetId(String value) {
        String assetId = trim(value);
        return assetId.isBlank() ? "" : assetId;
    }
    /**
     * 转换或计算 {@code amountPlanck} 对应的值，统一金额、格式和边界规则。
     */
    static BigInteger amountPlanck(JsonNode node) {
        String value = node == null || node.isMissingNode() || node.isNull() ? "0" : node.asText("0");
        return new BigInteger(value);
    }
    /**
     * 发送或广播 {@code submitted} 对应的链上请求，并返回节点处理结果。
     */
    private static SubmittedTransaction submitted(JsonNode result) {
        return new SubmittedTransaction(result.path("txHash").asText(),
                result.path("blockHeight").asLong(0L),
                result.path("status").asText("FINALIZED"),
                result.toString());
    }
    /**
     * 执行 {@code trim} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
    /**
     * 执行 {@code abbreviate} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private static String abbreviate(String value) {
        if (value == null || value.isBlank()) {
            return "<empty>";
        }
        return value.length() <= 500 ? value : value.substring(0, 500) + "...";
    }

    public record TransferEvent(String txHash, String fromAddress, String toAddress,
                                BigInteger amountPlanck, long blockHeight, long eventIndex,
                                String assetId, String rawPayload) {
    }
    public record SubmittedTransaction(String txHash, long blockHeight, String status, String rawPayload) {
    }

    public record AssetInfo(String assetId, boolean exists, BigInteger supply, BigInteger minBalance,
                            boolean sufficient, String name, String symbol, int decimals) {
    }

    public record AssetCreateResult(String txHash, long blockHeight, String status, String assetId,
                                    String rawPayload) {
    }
}
