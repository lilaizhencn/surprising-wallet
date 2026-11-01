package com.surprising.wallet.chain.polkadot;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainRpcNode;
import com.surprising.wallet.common.chain.TokenDefinition;
import com.surprising.wallet.service.ChainRpcNodeService;
import com.surprising.wallet.repository.ChainProfileRepository;
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

/** Public metadata, balances, finalized events and externally signed wire transport. */
@Component
@RequiredArgsConstructor
public class PolkadotRuntimeClient {

    /** 链标识 */
    public static final String CHAIN = "DOT";

    /** 中继链 RPC 目的标识 */
    private static final String PURPOSE_NATIVE_RPC = "relay_rpc";

    /** Asset Hub RPC 目的标识 */
    private static final String PURPOSE_ASSET_RPC = "rpc";

    /** 运行时服务目的标识 */
    private static final String PURPOSE_RUNTIME = "runtime";

    /** 数据库仓库 */
    private final ChainProfileRepository profiles;

    /** RPC 节点故障转移服务 */
    private final ChainRpcNodeService rpcNodeService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    public long latestFinalizedHeight() {
        return latestFinalizedHeight(PURPOSE_ASSET_RPC);
    }
    public BigInteger nativeBalance(String address) {
        return nativeBalance(MpcPolkadotTransactionCodec.Role.ASSET_HUB,address);
    }
    /** Explicit relay access is a public protocol diagnostic; custody DOT always uses Asset Hub. */
    public BigInteger nativeBalance(MpcPolkadotTransactionCodec.Role role,String address) {
        return nativeBalance(address,purpose(role));
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

    public List<TransferEvent> scanTransfers(long fromBlock,long toBlock,Collection<String> addresses,
                                            Map<String,TokenDefinition> tokensByAssetId) {
        return scanTransfers(PURPOSE_ASSET_RPC,fromBlock,toBlock,addresses,tokensByAssetId.keySet(),true,!tokensByAssetId.isEmpty());
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
                    item.path("assetId").isNull()?null:trim(item.path("assetId").asText()),
                    item.path("blockHash").asText(),item.toString()));
        }
        return events;
    }

    public JsonNode networkInfo(MpcPolkadotTransactionCodec.Role role,String source,String assetId) {
        ObjectNode body=baseBody();body.put("address",source);if(assetId!=null)body.put("assetId",normalizeAssetId(assetId));
        return callRuntime("/v1/polkadot/network-info",purpose(role),body);
    }
    public BigInteger paymentInfo(MpcPolkadotTransactionCodec.Role role,String source,String recipient,
                                  BigInteger amount,String assetId) {
        ObjectNode body=baseBody();body.put("address",source);body.put("to",recipient);body.put("amountAtomic",amount.toString());
        if(assetId!=null)body.put("assetId",normalizeAssetId(assetId));
        return amountPlanck(callRuntime("/v1/polkadot/payment-info",purpose(role),body).path("feePlanck"));
    }
    public String broadcast(MpcPolkadotTransactionCodec.Role role,String extrinsicHex) {
        ObjectNode body=baseBody();body.put("extrinsicHex",extrinsicHex);
        return callRuntime("/v1/polkadot/broadcast-signed",purpose(role),body).path("txHash").asText();
    }
    public JsonNode proof(MpcPolkadotTransactionCodec.Unsigned u,String hash) {
        ObjectNode body=baseBody();body.put("txHash",hash);body.put("fromBlock",u.blockHeight());body.put("validUntilHeight",u.validUntilHeight());
        return callRuntime("/v1/polkadot/extrinsic-proof",purpose(u.role()),body);
    }
    public JsonNode finalized(MpcPolkadotTransactionCodec.Role role) {
        return callRuntime("/v1/polkadot/latest-finalized",purpose(role),baseBody());
    }
    private static String purpose(MpcPolkadotTransactionCodec.Role role) {
        return role==MpcPolkadotTransactionCodec.Role.RELAY?PURPOSE_NATIVE_RPC:PURPOSE_ASSET_RPC;
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
                if (!path.endsWith("/broadcast-signed") && (response.statusCode() == 429 || response.statusCode() / 100 == 5) && attempt < 4) {
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
        return profiles.findAccountByChain(CHAIN)
                .orElseThrow(() -> new IllegalStateException("missing enabled chain_profile for " + CHAIN));
    }
    public static int ss58Prefix(AccountChainProfile profile) {
        if (profile.getChainId() != null && profile.getChainId() >= 0 && profile.getChainId() <= 16_383) {
            return profile.getChainId().intValue();
        }
        String network = trim(profile.getNetwork()).toLowerCase(Locale.ROOT);
        if ("mainnet".equals(network) || "polkadot".equals(network)) {
            return 0;
        }
        return 42;
    }
    public static String normalizeAssetId(String value) {
        String assetId = trim(value);
        return assetId.isBlank() ? "" : assetId;
    }
    static BigInteger amountPlanck(JsonNode node) {
        String value=node==null?"":node.asText();
        if(!value.matches("0|[1-9][0-9]{0,38}"))throw new IllegalStateException("DOT public atomic balance is missing or malformed");
        var amount=new BigInteger(value);if(amount.bitLength()>128)throw new IllegalStateException("DOT public balance exceeds u128");return amount;
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
                                String assetId, String blockHash, String rawPayload) {
    }
    public record AssetInfo(String assetId, boolean exists, BigInteger supply, BigInteger minBalance,
                            boolean sufficient, String name, String symbol, int decimals) {
    }

}
