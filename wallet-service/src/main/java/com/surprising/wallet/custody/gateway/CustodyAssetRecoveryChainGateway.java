package com.surprising.wallet.custody.gateway;

import com.surprising.wallet.common.chain.ChainAddressRecord;

import java.math.BigDecimal;
/**
 * 托管资产找回链网关接口，定义将误转入钱包地址的资产找回至目标地址的标准化流程。
 *
 * <p>找回流程分三步：
 * <ol>
 *   <li>{@link #verify(VerificationRequest)} — 验证源交易确实向平台地址转入指定资产</li>
 *   <li>{@link #execute(ExecutionRequest)} — 构造并广播找回交易，返回 txHash</li>
 *   <li>{@link #confirmed(String, String)} — 等待链上确认</li>
 * </ol>
 *
 * <p>每个链族（EVM、TRON 等）提供独立的实现，通过 {@link #supports(String)} 声明支持范围。
 *
 * @see com.surprising.wallet.custody.gateway.EvmAssetRecoveryChainGateway
 */
public interface CustodyAssetRecoveryChainGateway {

    /**
     * 判断是否支持给定的链。
     *
     * @param chain 链名称
     * @return true 表示支持该链的资产找回
     */
    boolean supports(String chain);

    /**
     * 验证源交易确实向平台地址转入了指定资产。
     *
     * @param request 验证请求（包含 txHash、目标地址、声称金额）
     * @return 验证结果（实际金额、区块信息、确认数）
     */
    Verification verify(VerificationRequest request);

    /**
     * 构造并广播资产找回交易。
     *
     * @param request 执行请求（包含源地址、资产信息、找回目标地址、金额）
     * @return 交易哈希
     */
    String execute(ExecutionRequest request);

    /**
     * 检查找回交易是否已确认。
     *
     * @param chain  链名称
     * @param txHash 交易哈希
     * @return true 表示已获得足够确认
     */
    boolean confirmed(String chain, String txHash);

    /** 交易永久失败异常（如链上 revert），不可重试 */
    final class PermanentlyFailedTransactionException extends RuntimeException {
        public PermanentlyFailedTransactionException(String message) {
            super(message);
        }
    }

    /** 验证请求，包含源交易和预期资产信息 */
    record VerificationRequest(
            String chain, String assetSymbol, String tokenContract, String txHash,
            Long requestedLogIndex, String destinationAddress, BigDecimal claimedAmount) {
    }

    /** 验证结果，包含链上确认的实际转入金额和区块信息 */
    record Verification(
            String tokenContract, Integer tokenDecimals, long logIndex, BigDecimal amount,
            long blockHeight, String blockHash, int confirmations, String detailsJson) {
    }

    /** 执行请求，包含找回资产的完整参数 */
    record ExecutionRequest(
            String chain, String assetSymbol, String tokenContract, int tokenDecimals,
            BigDecimal amount, ChainAddressRecord source, String recoveryAddress) {
    }
}
