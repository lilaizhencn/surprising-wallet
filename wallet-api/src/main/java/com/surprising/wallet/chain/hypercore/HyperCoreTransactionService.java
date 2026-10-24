package com.surprising.wallet.chain.hypercore;

import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.TokenDefinition;
import com.surprising.wallet.repository.AccountSequenceRepository;
import com.surprising.wallet.repository.CollectionRecordRepository;
import com.surprising.wallet.repository.HyperCoreActionRepository;
import com.surprising.wallet.repository.HyperCoreTokenMetadataRepository;
import java.sql.Timestamp;
import java.time.Instant;
import com.surprising.wallet.service.AccountSecp256k1KeyService;
import com.surprising.wallet.service.WithdrawalSettlementService;
import java.math.BigDecimal;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.bitcoinj.crypto.ECKey;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * HyperCore 链交易服务，负责 EIP-712 签名和 exchange 接口提交。
 *
 * <p>支持 usdSend（USDC 转账）和 spotSend（现货代币转账）两种交易类型。
 * 交易通过 {@link HyperCoreSigner} 进行 EIP-712 签名后提交到 /exchange 接口。</p>
 *
 * <p>提现确认：通过检查 action 是否已被 exchange 接受（accepted）来判断。</p>
 *
 * @see HyperCoreApiClient
 * @see HyperCoreSigner
 */
@Service
@RequiredArgsConstructor
public class HyperCoreTransactionService {

    private static final String CHAIN = "HYPERCORE";

    private final HyperCoreApiClient apiClient;

    private final HyperCoreSigner signer;

    private final HyperCoreActionRepository actions;
    private final HyperCoreTokenMetadataRepository tokenMetadata;
    private final CollectionRecordRepository collectionRecordRepository;
    private final AccountSequenceRepository accountSequenceRepository;

    private final AccountSecp256k1KeyService keyService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final WithdrawalSettlementService settlements;

    public String sendUsd(AccountChainProfile profile, ChainAddressRecord from,
                          String destination, BigDecimal amount) {
        long nonce = nextNonce(from);
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
        long nonce = nextNonce(from);
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

    public boolean confirmWithdrawal(java.util.UUID tenantId, String orderNo, String actionId) {
        if (!actions.accepted(actionId)) {
            return false;
        }
        return settlements.confirm(tenantId, CHAIN, orderNo, actionId);
    }

    public boolean confirmCollection(java.util.UUID tenantId, String collectionNo, String actionId) {
        return actions.accepted(actionId)
                && collectionRecordRepository.markConfirmed(tenantId, CHAIN, collectionNo, actionId) == 1;
    }

    private String submit(String actionType, String symbol, String fromAddress, String destination,
                          BigDecimal amount, long nonce, ObjectNode action, ObjectNode signature) {
        String actionId = "HC-" + actionType + "-" + fromAddress.toLowerCase(Locale.ROOT) + "-" + nonce;
        ObjectNode body = objectMapper.createObjectNode();
        body.set("action", action);
        body.put("nonce", nonce);
        body.set("signature", signature);
        String requestPayload = body.toString();
        actions.create(actionId, actionType, CHAIN, symbol,
                fromAddress, normalizeAddress(destination), amount, nonce, requestPayload, Timestamp.from(Instant.now()));
        try {
            JsonNode response = apiClient.postExchange(body);
            if (!"ok".equalsIgnoreCase(response.path("status").asText())) {
                throw new IllegalStateException("HyperCore exchange rejected action: " + response);
            }
            actions.markAccepted(actionId, response.toString(), Timestamp.from(Instant.now()));
            return actionId;
        } catch (RuntimeException e) {
            actions.markFailed(actionId, e.getMessage(), Timestamp.from(Instant.now()));
            throw e;
        }
    }

    private String wireToken(AccountChainProfile profile, TokenDefinition token) {
        return tokenMetadata.findName(profile.getNetwork(), token.getSymbol())
                .orElse(token.getSymbol());
    }

    private long nextNonce(ChainAddressRecord from) {
        return accountSequenceRepository.reserve(CHAIN, normalizeAddress(from.getAddress()), System.currentTimeMillis());
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
