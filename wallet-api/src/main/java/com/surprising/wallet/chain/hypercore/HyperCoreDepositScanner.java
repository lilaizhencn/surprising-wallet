package com.surprising.wallet.chain.hypercore;

import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.repository.ChainAddressRepository;
import com.surprising.wallet.repository.HyperCoreTokenMetadataRepository;
import com.surprising.wallet.repository.HyperCoreSpotAssetRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Scan actual spot ledger transfers. Network reads stay outside the deposit/cursor transaction.
 *
 * @see HyperCoreApiClient
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HyperCoreDepositScanner {

    private static final String CHAIN = "HYPERCORE";

    private final HyperCoreApiClient apiClient;

    private final HyperCoreDepositService deposits;
    private final HyperCoreTokenMetadataRepository tokenMetadata;
    private final HyperCoreSpotAssetRepository spotAssets;
    private final ChainAddressRepository chainAddressRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    public void scanAndCredit(AccountChainProfile profile) {
        syncSpotMetadata(profile.getNetwork());
        List<ChainAddressRecord> addresses = chainAddressRepository.listMpcAddresses(CHAIN).stream()
                .filter(address -> address.getUserId() != null && address.getUserId() != 0L)
                .filter(address -> "DEPOSIT".equalsIgnoreCase(address.getWalletRole()))
                .toList();
        for (ChainAddressRecord address : addresses) {
          for(String symbol:deposits.assets(profile.getNetwork())) {
            for(int page=0;page<10;page++) {
                long cursor=deposits.position(address,profile.getNetwork(),symbol);
                JsonNode records=apiClient.postInfo(objectMapper.createObjectNode().put("type","userNonFundingLedgerUpdates")
                        .put("user",address.getAddress()).put("startTime",cursor));
                if(!deposits.recordPage(address,profile.getNetwork(),symbol,cursor,records) || records.size()<HyperCoreDepositService.LEDGER_PAGE_THRESHOLD)break;
            }
          }
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
            tokenMetadata.upsert(
                    network,
                    token.path("name").asText(),
                    token.path("index").isMissingNode() ? null : token.path("index").asInt(),
                    token.path("tokenId").asText(null),
                    token.path("szDecimals").isMissingNode() ? null : token.path("szDecimals").asInt(),
                    token.path("weiDecimals").isMissingNode() ? null : token.path("weiDecimals").asInt(),
                    token.path("isCanonical").asBoolean(false),
                    evmContract,
                    token.path("fullName").asText(null), Timestamp.from(Instant.now()));
        }
        for (JsonNode spot : meta.path("universe")) {
            JsonNode tokens = spot.path("tokens");
            Integer base = tokens.isArray() && tokens.size() > 0 ? tokens.get(0).asInt() : null;
            Integer quote = tokens.isArray() && tokens.size() > 1 ? tokens.get(1).asInt() : null;
            spotAssets.upsert(
                    network,
                    spot.path("index").isMissingNode() ? null : spot.path("index").asInt(),
                    spot.path("name").asText(),
                    base,
                    quote,
                    spot.path("isCanonical").asBoolean(false), Timestamp.from(Instant.now()));
        }
    }

}
