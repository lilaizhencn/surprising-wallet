package com.surprising.wallet.service.chain.hypercore;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.TokenDefinition;
import com.surprising.wallet.service.config.AccountSecp256k1KeyService;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import lombok.RequiredArgsConstructor;
import org.bitcoinj.crypto.ECKey;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class HyperCoreTransactionService {
    private static final String CHAIN = "HYPERCORE";

    private final HyperCoreApiClient apiClient;
    private final HyperCoreSigner signer;
    private final HyperCoreRepository hyperCoreRepository;
    private final ChainJdbcRepository chainRepository;
    private final AccountSecp256k1KeyService keyService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public String sendUsd(AccountChainProfile profile, ChainAddressRecord from,
                          String destination, BigDecimal amount) {
        long nonce = System.currentTimeMillis();
        ObjectNode action = objectMapper.createObjectNode();
        action.put("destination", normalizeAddress(destination));
        action.put("amount", amountString(amount));
        action.put("time", nonce);
        action.put("type", "usdSend");
        ECKey key = keyService.key(profile, from);
        ObjectNode signature = signer.signUsdSend(action, key, isMainnet(profile));
        return submit("usdSend", "USDC", from.getAddress(), destination, amount, nonce, action, signature);
    }

    public String sendSpot(AccountChainProfile profile, ChainAddressRecord from,
                           TokenDefinition token, String destination, BigDecimal amount) {
        long nonce = System.currentTimeMillis();
        ObjectNode action = objectMapper.createObjectNode();
        action.put("destination", normalizeAddress(destination));
        action.put("amount", amountString(amount));
        action.put("token", wireToken(profile, token));
        action.put("time", nonce);
        action.put("type", "spotSend");
        ECKey key = keyService.key(profile, from);
        ObjectNode signature = signer.signSpotSend(action, key, isMainnet(profile));
        return submit("spotSend", token.getSymbol(), from.getAddress(), destination, amount, nonce, action, signature);
    }

    public boolean confirmWithdrawal(String orderNo, String actionId, String assetSymbol,
                                     String debitAccountId, BigDecimal debitAmount) {
        if (!hyperCoreRepository.actionAccepted(actionId)) {
            return false;
        }
        if (chainRepository.markWithdrawalConfirmed(CHAIN, orderNo, actionId) == 1) {
            chainRepository.settleLockedDebit(CHAIN, assetSymbol, debitAccountId, debitAmount);
            return true;
        }
        return false;
    }

    public boolean confirmCollection(String collectionNo, String actionId) {
        return hyperCoreRepository.actionAccepted(actionId)
                && chainRepository.markCollectionConfirmed(CHAIN, collectionNo, actionId) == 1;
    }

    private String submit(String actionType, String symbol, String fromAddress, String destination,
                          BigDecimal amount, long nonce, ObjectNode action, ObjectNode signature) {
        String actionId = "HC-" + actionType + "-" + fromAddress.toLowerCase(Locale.ROOT) + "-" + nonce;
        ObjectNode body = objectMapper.createObjectNode();
        body.set("action", action);
        body.put("nonce", nonce);
        body.set("signature", signature);
        String requestPayload = body.toString();
        hyperCoreRepository.createAction(actionId, actionType, symbol,
                fromAddress, normalizeAddress(destination), amount, nonce, requestPayload);
        try {
            JsonNode response = apiClient.postExchange(body);
            if (!"ok".equalsIgnoreCase(response.path("status").asText())) {
                throw new IllegalStateException("HyperCore exchange rejected action: " + response);
            }
            hyperCoreRepository.markActionAccepted(actionId, response.toString());
            return actionId;
        } catch (RuntimeException e) {
            hyperCoreRepository.markActionFailed(actionId, e.getMessage());
            throw e;
        }
    }

    private String wireToken(AccountChainProfile profile, TokenDefinition token) {
        return hyperCoreRepository.tokenNameBySymbol(profile.getNetwork(), token.getSymbol())
                .orElse(token.getSymbol());
    }

    private static boolean isMainnet(AccountChainProfile profile) {
        String network = profile.getNetwork() == null ? "" : profile.getNetwork().toLowerCase(Locale.ROOT);
        return network.equals("mainnet") || network.equals("main");
    }

    private static String amountString(BigDecimal amount) {
        return amount.stripTrailingZeros().toPlainString();
    }

    private static String normalizeAddress(String address) {
        return address == null ? "" : address.trim().toLowerCase(Locale.ROOT);
    }
}
