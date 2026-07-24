package com.surprising.wallet.service.chain.hypercore;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public
class HyperCoreDepositScanner {
    private static final String CHAIN = "HYPERCORE";
    private final HyperCoreApiClient apiClient;
    private final HyperCoreRepository hyperCoreRepository;
    private final ChainJdbcRepository chainRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();
    public void scanAndCredit(AccountChainProfile profile) {
        syncSpotMetadata(profile.getNetwork());
        List<ChainAddressRecord> addresses = chainRepository.listChainAddresses(CHAIN).stream()
                .filter(ChainAddressRecord::getEnabled)
                .filter(address -> address.getUserId() != null && address.getUserId() != 0L)
                .filter(address -> "DEPOSIT".equalsIgnoreCase(address.getWalletRole()))
                .toList();
        Map<String, JsonNode> stateByAddress = new LinkedHashMap<>();
        for (ChainAddressRecord address : addresses) {
            JsonNode state = stateByAddress.computeIfAbsent(
                    address.getAddress().toLowerCase(Locale.ROOT),
                    ignored -> spotState(address.getAddress()));
            Map<String, BigDecimal> balances = balancesBySymbol(state);
            BigDecimal observed = balances.getOrDefault(
                    address.getAssetSymbol().toUpperCase(Locale.ROOT), BigDecimal.ZERO);
            hyperCoreRepository.recordObservedBalance(
                    address,
                    address.getAssetSymbol(),
                    observed,
                    state.toString())
                    .ifPresent(delta -> log.info("HyperCore deposit credited chain={} symbol={} address={} amount={}",
                            CHAIN, address.getAssetSymbol(), address.getAddress(), delta));
        }
    }
    public JsonNode spotState(String address) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("type", "spotClearinghouseState");
        body.put("user", address);
        return apiClient.postInfo(body);
    }
    public void syncSpotMetadata(String network) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("type", "spotMeta");
        JsonNode meta = apiClient.postInfo(body);
        for (JsonNode token : meta.path("tokens")) {
            JsonNode evm = token.path("evmContract");
            String evmContract = null;
            if (evm.isObject()) {
                evmContract = evm.path("address").asText(null);
            } else if (evm.isTextual()) {
                evmContract = evm.asText();
            }
            hyperCoreRepository.upsertTokenMetadata(
                    network,
                    token.path("name").asText(),
                    token.path("index").isMissingNode() ? null : token.path("index").asInt(),
                    token.path("tokenId").asText(null),
                    token.path("szDecimals").isMissingNode() ? null : token.path("szDecimals").asInt(),
                    token.path("weiDecimals").isMissingNode() ? null : token.path("weiDecimals").asInt(),
                    token.path("isCanonical").asBoolean(false),
                    evmContract,
                    token.path("fullName").asText(null));
        }
        for (JsonNode spot : meta.path("universe")) {
            JsonNode tokens = spot.path("tokens");
            Integer base = tokens.isArray() && tokens.size() > 0 ? tokens.get(0).asInt() : null;
            Integer quote = tokens.isArray() && tokens.size() > 1 ? tokens.get(1).asInt() : null;
            hyperCoreRepository.upsertSpotAsset(
                    network,
                    spot.path("index").isMissingNode() ? null : spot.path("index").asInt(),
                    spot.path("name").asText(),
                    base,
                    quote,
                    spot.path("isCanonical").asBoolean(false));
        }
    }
    private Map<String, BigDecimal> balancesBySymbol(JsonNode state) {
        Map<String, BigDecimal> balances = new LinkedHashMap<>();
        for (JsonNode balance : state.path("balances")) {
            String symbol = balance.path("coin").asText(balance.path("token").asText(""));
            if (symbol == null || symbol.isBlank()) {
                continue;
            }
            BigDecimal total = decimal(balance.path("total").asText(
                    balance.path("free").asText("0")));
            balances.put(symbol.toUpperCase(Locale.ROOT), total);
        }
        return balances;
    }
    private static BigDecimal decimal(String value) {
        if (value == null || value.isBlank()) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(value);
    }
}
