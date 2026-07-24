package com.surprising.wallet.service.chain.polkadot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainRpcNode;
import com.surprising.wallet.common.chain.TokenDefinition;
import com.surprising.wallet.service.config.ChainRpcNodeService;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
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

@Component
@RequiredArgsConstructor
public
class PolkadotRuntimeClient {
    static final String CHAIN = "DOT";    private static final String PURPOSE_NATIVE_RPC = "rpc";    private static final String PURPOSE_ASSET_RPC = "asset_rpc";    private static final String PURPOSE_RUNTIME = "runtime";    private final ChainJdbcRepository repository;    private final ChainRpcNodeService rpcNodeService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    public long latestFinalizedHeight() {
        return latestFinalizedHeight(PURPOSE_NATIVE_RPC);
    }
    public long latestAssetHubFinalizedHeight() {
        return latestFinalizedHeight(PURPOSE_ASSET_RPC);
    }
    public BigInteger nativeBalance(String address) {
        return nativeBalance(address, PURPOSE_NATIVE_RPC);
    }
    public BigInteger assetHubNativeBalance(String address) {
        return nativeBalance(address, PURPOSE_ASSET_RPC);
    }
    public BigInteger assetBalance(String assetId, String address) {
        ObjectNode body = baseBody();
        body.put("ss58Prefix", ss58Prefix(profile()));
        body.put("assetId", normalizeAssetId(assetId));
        body.put("address", address);
        return amountPlanck(callRuntime("/v1/polkadot/asset-balance", PURPOSE_ASSET_RPC, body)
                .path("balance"));
    }
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

    public List<TransferEvent> scanNativeTransfers(long fromBlock, long toBlock,
                                                   Collection<String> addresses) {
        return scanTransfers(PURPOSE_NATIVE_RPC, fromBlock, toBlock, addresses, List.of(), true, false);
    }

    public List<TransferEvent> scanAssetTransfers(long fromBlock, long toBlock,
                                                  Collection<String> addresses,
                                                  Map<String, TokenDefinition> tokensByAssetId) {
        return scanTransfers(PURPOSE_ASSET_RPC, fromBlock, toBlock, addresses,
                tokensByAssetId.keySet(), false, true);
    }
    private long latestFinalizedHeight(String rpcPurpose) {
        return callRuntime("/v1/polkadot/latest-finalized", rpcPurpose, baseBody()).path("height").asLong();
    }
    private BigInteger nativeBalance(String address, String rpcPurpose) {
        ObjectNode body = baseBody();
        body.put("ss58Prefix", ss58Prefix(profile()));
        body.put("address", address);
        return amountPlanck(callRuntime("/v1/polkadot/native-balance", rpcPurpose, body)
                .path("free"));
    }

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

    public SubmittedTransaction sendNative(String secretSeedHex, String expectedFrom,
                                           String toAddress, BigInteger amountPlanck) {
        return sendNative(secretSeedHex, expectedFrom, toAddress, amountPlanck, true);
    }

    public SubmittedTransaction sendNative(String secretSeedHex, String expectedFrom,
                                           String toAddress, BigInteger amountPlanck,
                                           boolean keepAlive) {
        return sendNative(secretSeedHex, expectedFrom, toAddress, amountPlanck, keepAlive, PURPOSE_NATIVE_RPC);
    }

    public SubmittedTransaction sendAssetHubNative(String secretSeedHex, String expectedFrom,
                                                   String toAddress, BigInteger amountPlanck,
                                                   boolean keepAlive) {
        return sendNative(secretSeedHex, expectedFrom, toAddress, amountPlanck, keepAlive, PURPOSE_ASSET_RPC);
    }

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

    public SubmittedTransaction sendAsset(String secretSeedHex, String expectedFrom,
                                          String assetId, String toAddress, BigInteger amountAtomic) {
        return sendAsset(secretSeedHex, expectedFrom, assetId, toAddress, amountAtomic, true);
    }

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
    public boolean transactionFinalized(String txHash, int maxRecentBlocks) {
        return transactionFinalized(txHash, maxRecentBlocks, PURPOSE_NATIVE_RPC);
    }
    public boolean assetTransactionFinalized(String txHash, int maxRecentBlocks) {
        return transactionFinalized(txHash, maxRecentBlocks, PURPOSE_ASSET_RPC);
    }
    private boolean transactionFinalized(String txHash, int maxRecentBlocks, String rpcPurpose) {
        ObjectNode body = baseBody();
        body.put("txHash", txHash);
        body.put("maxRecentBlocks", maxRecentBlocks);
        return callRuntime("/v1/polkadot/transaction-status", rpcPurpose, body)
                .path("finalized").asBoolean(false);
    }
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
    private ObjectNode baseBody() {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("chain", CHAIN);
        return body;
    }
    private AccountChainProfile profile() {
        return repository.findProfileByChain(CHAIN)
                .orElseThrow(() -> new IllegalStateException("missing enabled chain_profile for " + CHAIN));
    }
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
    static String normalizeAssetId(String value) {
        String assetId = trim(value);
        return assetId.isBlank() ? "" : assetId;
    }
    static BigInteger amountPlanck(JsonNode node) {
        String value = node == null || node.isMissingNode() || node.isNull() ? "0" : node.asText("0");
        return new BigInteger(value);
    }
    private static SubmittedTransaction submitted(JsonNode result) {
        return new SubmittedTransaction(result.path("txHash").asText(),
                result.path("blockHeight").asLong(0L),
                result.path("status").asText("FINALIZED"),
                result.toString());
    }
    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
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
