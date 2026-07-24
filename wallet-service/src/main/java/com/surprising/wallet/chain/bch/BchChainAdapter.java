package com.surprising.wallet.chain.bch;

import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.chain.TransferQuote;
import com.surprising.wallet.common.chain.TransferRequest;
import com.surprising.wallet.common.dto.TransactionDTO;
import com.surprising.wallet.common.pojo.Address;
import com.surprising.wallet.common.pojo.WithdrawTransaction;
import com.surprising.wallet.sdk.bitcoinj.bitcoincash.BitcoinCashFeePolicy;
import com.surprising.wallet.sdk.bitcoinj.core.P2wshFeeCalculator;
import com.surprising.wallet.chain.BlockchainAdapter;
import com.surprising.wallet.chain.utxo.BitcoinLikeChainRuntime;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Bitcoin Cash (BCH) 链适配器，实现 {@link BlockchainAdapter} 接口。
 *
 * <p>BCH 采用 UTXO 模型，通过 {@link BitcoinLikeChainRuntime} 委托核心操作。
 * 地址格式支持 CashAddr，内部自动进行 Legacy 与 CashAddr 之间的转换。
 * 手续费估算使用 {@link BitcoinCashFeePolicy} 确保不低于粉尘阈值，
 * 底层的交易签名仍复用 {@link P2wshFeeCalculator} 计算基础费用。
 *
 * <p>核心能力：原生报价、地址生成与校验、确认策略、粉尘策略、区块高度查询、
 * 区块交易扫描、确认数刷新、余额刷新、签名交易广播。
 */
@Component
public class BchChainAdapter implements BlockchainAdapter {

    /** BCH 链通用运行时，封装 UTXO 链的地址生成、扫描、余额等逻辑 */
    private final BitcoinLikeChainRuntime runtime;

    /**
     * @param runtime BCH 链运行时，不能为空
     */
    public BchChainAdapter(BitcoinLikeChainRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public ChainType chainType() {
        return ChainType.BCH;
    }

    @Override
    public java.util.Set<Capability> capabilities() {
        return java.util.EnumSet.of(
                Capability.NATIVE_QUOTE, Capability.ADDRESS_GENERATION,
                Capability.ADDRESS_VALIDATION, Capability.CONFIRMATION_POLICY,
                Capability.DUST_POLICY, Capability.BEST_HEIGHT,
                Capability.BLOCK_TRANSACTION_SCAN, Capability.CONFIRMATION_REFRESH,
                Capability.BALANCE_REFRESH, Capability.SIGNED_TRANSACTION_BROADCAST);
    }

    @Override
    public String family() {
        return "bitcoin-cash";
    }

    @Override
    public String describe() {
        return "Bitcoin Cash UTXO adapter with CashAddr normalization and RPC-backed scanning.";
    }

    /**
     * 估算 BCH 原生转账手续费。
     *
     * <p>手续费不低于 {@link BitcoinCashFeePolicy#DUST_THRESHOLD_SAT}。
     *
     * @param request 转账请求，包含金额和可选的费率（sat/vByte），未提供时默认 1 sat/vByte
     * @return 包含手续费估算的报价，金额单位为 BCH（8 位小数）
     */
    @Override
    public TransferQuote quoteNativeTransfer(TransferRequest request) {
        long feeRate = request.feeRateSatPerVByte() == null ? 1L : request.feeRateSatPerVByte();
        long feeSat = Math.max(BitcoinCashFeePolicy.DUST_THRESHOLD_SAT,
                P2wshFeeCalculator.calculateFeeSat(1, 2, feeRate));
        BigDecimal fee = BigDecimal.valueOf(feeSat).movePointLeft(8).setScale(8, RoundingMode.DOWN);
        return new TransferQuote(request.chainType(), request.assetSymbol(), request.fromAddress(), request.toAddress(),
                request.amount(), fee, 0L, 1L, feeSat, 0L, null, true, "bch p2sh fee estimate");
    }

    /**
     * 生成 BCH 存款地址（CashAddr 格式）。
     *
     * @param chainType 链类型（固定为 BCH）
     * @param userId 用户 ID
     * @param biz 业务标识
     * @return 新生成的 BCH 地址对象
     */
    @Override
    public Address generateDepositAddress(ChainType chainType, long userId, int biz) {
        return runtime.generateDepositAddress(ChainType.BCH, userId, biz);
    }

    /**
     * 按指定派生索引生成 BCH 存款地址。
     *
     * @param chainType  链类型（固定为 BCH）
     * @param userId     用户 ID
     * @param biz        业务标识
     * @param childIndex BIP44 子索引
     * @return 指定索引的 BCH 地址对象
     */
    @Override
    public Address generateDepositAddressAtIndex(
            ChainType chainType, long userId, int biz, long childIndex) {
        return runtime.generateDepositAddressAtIndex(ChainType.BCH, userId, biz, childIndex);
    }

    /**
     * 校验 BCH 地址格式是否合法（支持 CashAddr 和 Legacy 格式）。
     *
     * @param chainType 链类型（固定为 BCH）
     * @param address   待校验的地址字符串
     * @return true 表示地址格式合法
     */
    @Override
    public boolean checkAddress(ChainType chainType, String address) {
        return runtime.checkAddress(ChainType.BCH, address);
    }

    /**
     * 获取 BCH 充值确认数阈值。
     *
     * @param chainType 链类型（固定为 BCH）
     * @return 充值所需的区块确认数
     */
    @Override
    public long depositConfirmationThreshold(ChainType chainType) {
        return runtime.depositConfirmationThreshold(ChainType.BCH);
    }

    /**
     * 获取 BCH 粉尘阈值（单位：satoshis）。
     *
     * @param chainType 链类型（固定为 BCH）
     * @return 最小可花费金额（satoshis）
     */
    @Override
    public long dustThresholdAtomic(ChainType chainType) {
        return runtime.dustThresholdAtomic(ChainType.BCH);
    }

    /**
     * 获取 BCH 链当前最佳区块高度。
     *
     * @param chainType 链类型（固定为 BCH）
     * @return 当前最新区块高度
     */
    @Override
    public long bestHeight(ChainType chainType) {
        return runtime.bestHeight(ChainType.BCH);
    }

    /**
     * 扫描指定高度的区块，查找与平台地址相关的 BCH 交易。
     *
     * @param chainType 链类型（固定为 BCH）
     * @param height    区块高度
     * @return 相关交易列表
     */
    @Override
    public List<TransactionDTO> findRelatedTransactions(ChainType chainType, long height) {
        return runtime.findRelatedTransactions(ChainType.BCH, height);
    }

    /**
     * 刷新 BCH 链上所有待确认交易的确认数。
     *
     * @param chainType 链类型（固定为 BCH）
     */
    @Override
    public void updateTransactionConfirmations(ChainType chainType) {
        runtime.updateTransactionConfirmations(ChainType.BCH);
    }

    /**
     * 刷新 BCH 链上所有地址的总余额。
     *
     * @param chainType 链类型（固定为 BCH）
     */
    @Override
    public void updateTotalBalance(ChainType chainType) {
        runtime.updateTotalBalance(ChainType.BCH);
    }

    /**
     * 广播已签名的 BCH 提现交易。
     *
     * @param chainType   链类型（固定为 BCH）
     * @param transaction 包含签名数据的提现交易
     * @return 交易哈希（txid）
     */
    @Override
    public String broadcastSignedTransaction(ChainType chainType, WithdrawTransaction transaction) {
        return runtime.broadcastSignedTransaction(ChainType.BCH, transaction);
    }
}
