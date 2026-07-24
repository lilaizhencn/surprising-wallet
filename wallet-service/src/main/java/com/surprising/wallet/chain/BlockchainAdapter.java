package com.surprising.wallet.chain;

import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.chain.DepositEvent;
import com.surprising.wallet.common.chain.TransferQuote;
import com.surprising.wallet.common.chain.TransferRequest;
import com.surprising.wallet.common.dto.TransactionDTO;
import com.surprising.wallet.common.pojo.Address;
import com.surprising.wallet.common.pojo.WithdrawTransaction;

import java.util.List;
import java.util.Set;

/**
 * 区块链适配器统一接口，所有链的适配器实现必须实现此接口。
 *
 * <p>采用能力模型（capability model）：每个适配器声明自己支持的能力，
 * 调用方通过 {@link #capabilities()} 检查适配器是否提供某个功能。
 * 不支持的能力调用会抛出 {@link IllegalStateException}。
 *
 * <p>支持的链类型通过 {@link #chainType()} 声明，
 * 支持的资产通过 {@link ChainType} 的子类型细化。
 *
 * @see ChainType
 */
public interface BlockchainAdapter {

    /**
     * 返回适配器支持的链类型。
     *
     * @return 链类型枚举
     */
    ChainType chainType();

    /**
     * 返回适配器提供的能力集合。
     *
     * @return 能力集合
     */
    Set<Capability> capabilities();

    /**
     * 判断适配器是否支持给定的链类型。
     *
     * @param chainType 链类型
     * @return true 如果支持
     */
    default boolean supports(ChainType chainType) {
        return chainType() == chainType;
    }
    /**
     * 返回链族名称（如 EVM、Bitcoin-like、Solana 等）。
     *
     * @return 链族名称
     */
    String family();

    /**
     * 返回适配器的人类可读描述。
     *
     * @return 适配器描述
     */
    String describe();

    /**
     * 计算原生币转账的报价（手续费、预估时间等）。
     *
     * @param request 转账请求
     * @return 转账报价
     */
    TransferQuote quoteNativeTransfer(TransferRequest request);

    /**
     * 计算代币转账的报价，默认返回不支持。
     *
     * @param request 转账请求
     * @return 转账报价或 unsupported
     */
    default TransferQuote quoteTokenTransfer(TransferRequest request) {
        return TransferQuote.unsupported(request.chainType(), request.assetSymbol(),
                request.fromAddress(), request.toAddress(), request.amount(),
                "token transfer not supported by this adapter");
    }

    /**
     * 扫描指定高度的充值事件。
     *
     * @param height 区块高度
     * @return 充值事件列表
     */
    default List<DepositEvent> scanDeposits(long height) {
        throw missing(Capability.DEPOSIT_SCAN);
    }

    /**
     * 为指定用户生成充值地址。
     *
     * @param chainType 链类型
     * @param userId    用户 ID
     * @param biz       业务类型
     * @return 充值地址
     */
    default Address generateDepositAddress(ChainType chainType, long userId, int biz) {
        throw missing(Capability.ADDRESS_GENERATION);
    }

    /**
     * 为指定用户在给定索引位置生成充值地址。
     *
     * @param chainType  链类型
     * @param userId     用户 ID
     * @param biz        业务类型
     * @param childIndex 子索引
     * @return 充值地址
     */
    default Address generateDepositAddressAtIndex(
            ChainType chainType, long userId, int biz, long childIndex) {
        throw missing(Capability.ADDRESS_GENERATION);
    }

    /**
     * 校验地址格式是否合法。
     *
     * @param chainType 链类型
     * @param address   地址字符串
     * @return true 如果地址格式合法
     */
    default boolean checkAddress(ChainType chainType, String address) {
        throw missing(Capability.ADDRESS_VALIDATION);
    }

    /**
     * 返回充值确认所需的最小区块数。
     *
     * @param chainType 链类型
     * @return 确认区块数阈值
     */
    default long depositConfirmationThreshold(ChainType chainType) {
        throw missing(Capability.CONFIRMATION_POLICY);
    }

    /**
     * 返回粉尘阈值（最小单位）。
     *
     * @param chainType 链类型
     * @return 粉尘阈值
     */
    default long dustThresholdAtomic(ChainType chainType) {
        throw missing(Capability.DUST_POLICY);
    }

    /**
     * 返回当前最佳区块高度。
     *
     * @param chainType 链类型
     * @return 最佳区块高度
     */
    default long bestHeight(ChainType chainType) {
        throw missing(Capability.BEST_HEIGHT);
    }

    /**
     * 查找指定高度区块中的关联交易。
     *
     * @param chainType 链类型
     * @param height    区块高度
     * @return 交易列表
     */
    default List<TransactionDTO> findRelatedTransactions(ChainType chainType, long height) {
        throw missing(Capability.BLOCK_TRANSACTION_SCAN);
    }

    /**
     * 更新链上交易的确认数。
     *
     * @param chainType 链类型
     */
    default void updateTransactionConfirmations(ChainType chainType) {
        throw missing(Capability.CONFIRMATION_REFRESH);
    }

    /**
     * 更新链上总余额。
     *
     * @param chainType 链类型
     */
    default void updateTotalBalance(ChainType chainType) {
        throw missing(Capability.BALANCE_REFRESH);
    }

    /**
     * 广播已签名的交易到区块链网络。
     *
     * @param chainType   链类型
     * @param transaction 提现交易
     * @return 交易哈希
     */
    default String broadcastSignedTransaction(ChainType chainType, WithdrawTransaction transaction) {
        throw missing(Capability.SIGNED_TRANSACTION_BROADCAST);
    }

    /**
     * 构造不支持该能力的异常。
     *
     * @param capability 缺失的能力
     * @return IllegalStateException
     */
    private IllegalStateException missing(Capability capability) {
        return new IllegalStateException(
                chainType() + " adapter does not provide capability " + capability);
    }
    enum Capability {
        NATIVE_QUOTE,
        TOKEN_QUOTE,
        DEPOSIT_SCAN,
        ADDRESS_GENERATION,
        ADDRESS_VALIDATION,
        CONFIRMATION_POLICY,
        DUST_POLICY,
        BEST_HEIGHT,
        BLOCK_TRANSACTION_SCAN,
        CONFIRMATION_REFRESH,
        BALANCE_REFRESH,
        SIGNED_TRANSACTION_BROADCAST
    }
}
