package com.surprising.wallet.chain.hypercore;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.TokenDefinition;
import com.surprising.wallet.service.AccountSecp256k1KeyService;
import com.surprising.wallet.repository.ChainJdbcRepository;
import com.surprising.wallet.repository.HyperCoreRepository;
import lombok.RequiredArgsConstructor;
import org.bitcoinj.crypto.ECKey;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Locale;

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
public
class HyperCoreTransactionService {

    /** 链标识 */
    private static final String CHAIN = "HYPERCORE";

    /** API 客户端 */
    private final HyperCoreApiClient apiClient;

    /** EIP-712 签名器 */
    private final HyperCoreSigner signer;

    /** HyperCore 数据仓库 */
    private final HyperCoreRepository hyperCoreRepository;

    /** 链通用数据库仓库 */
    private final ChainJdbcRepository chainRepository;

    /** Secp256k1 密钥服务 */
    private final AccountSecp256k1KeyService keyService;

    /**
     * 保存 {@code objectMapper}，用于保存业务集合或索引状态。
     */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 发送或广播 {@code sendUsd} 对应的链上请求，并返回节点处理结果。
     */
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

    /**
     * 发送或广播 {@code sendSpot} 对应的链上请求，并返回节点处理结果。
     */
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

    /**
     * 处理 {@code confirmWithdrawal} 对应的链上或钱包业务流程，并维护状态、幂等和错误边界。
     */
    public boolean confirmWithdrawal(java.util.UUID tenantId, String orderNo,
                                     String actionId, String assetSymbol,
                                     String debitAccountId, BigDecimal debitAmount) {
        if (!hyperCoreRepository.actionAccepted(actionId)) {
            return false;
        }
        return chainRepository.confirmWithdrawalAndSettle(tenantId, CHAIN, orderNo, actionId,
                assetSymbol, debitAccountId, debitAmount);
    }
    /**
     * 处理 {@code confirmCollection} 对应的链上或钱包业务流程，并维护状态、幂等和错误边界。
     */
    public boolean confirmCollection(java.util.UUID tenantId, String collectionNo, String actionId) {
        return hyperCoreRepository.actionAccepted(actionId)
                && chainRepository.markCollectionConfirmed(
                        tenantId, CHAIN, collectionNo, actionId) == 1;
    }

    /**
     * 发送或广播 {@code submit} 对应的链上请求，并返回节点处理结果。
     */
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
    /**
     * 执行 {@code wireToken} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private String wireToken(AccountChainProfile profile, TokenDefinition token) {
        return hyperCoreRepository.tokenNameBySymbol(profile.getNetwork(), token.getSymbol())
                .orElse(token.getSymbol());
    }
    /**
     * 执行 {@code nextNonce} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private long nextNonce(ChainAddressRecord from) {
        return chainRepository.reserveAccountSequence(
                CHAIN,
                normalizeAddress(from.getAddress()),
                System.currentTimeMillis());
    }
    /**
     * 判断 {@code isMainnet} 对应的条件是否成立，并返回明确的布尔结果。
     */
    private static boolean isMainnet(AccountChainProfile profile) {
        String network = profile.getNetwork() == null ? "" : profile.getNetwork().toLowerCase(Locale.ROOT);
        return network.equals("mainnet") || network.equals("main");
    }
    /**
     * 转换或计算 {@code amountString} 对应的值，统一金额、格式和边界规则。
     */
    private static String amountString(BigDecimal amount) {
        return amount.stripTrailingZeros().toPlainString();
    }
    /**
     * 转换或计算 {@code normalizeAddress} 对应的值，统一金额、格式和边界规则。
     */
    private static String normalizeAddress(String address) {
        return address == null ? "" : address.trim().toLowerCase(Locale.ROOT);
    }
}
