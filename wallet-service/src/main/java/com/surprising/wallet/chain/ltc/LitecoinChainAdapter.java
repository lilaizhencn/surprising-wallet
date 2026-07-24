package com.surprising.wallet.chain.ltc;

import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.chain.TransferQuote;
import com.surprising.wallet.common.chain.TransferRequest;
import com.surprising.wallet.common.dto.TransactionDTO;
import com.surprising.wallet.common.pojo.Address;
import com.surprising.wallet.common.pojo.WithdrawTransaction;
import com.surprising.wallet.sdk.bitcoinj.core.P2wshFeeCalculator;
import com.surprising.wallet.sdk.bitcoinj.litecoin.LitecoinFeePolicy;
import com.surprising.wallet.chain.BlockchainAdapter;
import com.surprising.wallet.chain.utxo.BitcoinLikeChainRuntime;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Litecoin (LTC) 链适配器，实现 {@link BlockchainAdapter} 接口。
 *
 * <p>LTC 与 BTC 采用相似的 UTXO 模型，通过 {@link BitcoinLikeChainRuntime} 委托核心操作。
 * 与 BTC 的主要区别在于使用 Litecoin 独立网络参数
 * （{@link com.surprising.wallet.sdk.bitcoinj.litecoin.LitecoinNetworkParameters}）
 * 和专用的费率策略 {@link LitecoinFeePolicy}。
 * 地址使用 P2WSH 多签方案，手续费以 litoshis 为单位计算。
 *
 * <p>核心能力：原生报价、地址生成与校验、确认策略、粉尘策略、区块高度查询、
 * 区块交易扫描、确认数刷新、余额刷新、签名交易广播。
 */
@Component
public
class LitecoinChainAdapter implements BlockchainAdapter {

    /** LTC 链通用运行时，封装 UTXO 链的地址生成、扫描、余额等逻辑 */
    private final BitcoinLikeChainRuntime runtime;

    /**
     * @param runtime LTC 链运行时，不能为空
     */
    public LitecoinChainAdapter(BitcoinLikeChainRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public ChainType chainType() {
        return ChainType.LTC;
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
        return "litecoin";
    }

    @Override
    public String describe() {
        return "Litecoin BTC-like UTXO adapter with isolated network params, fee policy, and P2WSH signing.";
    }

    /**
     * 估算 LTC 原生转账手续费。
     *
     * <p>费率使用 {@link LitecoinFeePolicy} 进行钳位，默认费率来自
     * {@link LitecoinFeePolicy#DEFAULT_FEE_RATE_LITOSHI_PER_VBYTE}。
     *
     * @param request 转账请求，包含金额和可选的费率（litoshis/vByte）
     * @return 包含手续费估算的报价，金额单位为 LTC（8 位小数）
     */
    @Override
    public TransferQuote quoteNativeTransfer(TransferRequest request) {
        long feeRate = request.feeRateSatPerVByte() == null
                ? LitecoinFeePolicy.DEFAULT_FEE_RATE_LITOSHI_PER_VBYTE
                : LitecoinFeePolicy.clampFeeRate(request.feeRateSatPerVByte());
        long feeLitoshi = P2wshFeeCalculator.calculateFeeSat(1, 2, feeRate);
        BigDecimal fee = BigDecimal.valueOf(feeLitoshi).movePointLeft(8).setScale(8, RoundingMode.DOWN);
        return new TransferQuote(request.chainType(), request.assetSymbol(), request.fromAddress(), request.toAddress(),
                request.amount(), fee, 0L, 1L, feeLitoshi, 0L, null, true, "ltc p2wsh fee estimate");
    }

    /**
     * 生成 LTC 存款地址（P2WSH 格式）。
     *
     * @param chainType 链类型（固定为 LTC）
     * @param userId 用户 ID
     * @param biz 业务标识
     * @return 新生成的 LTC 地址对象
     */
    @Override
    public Address generateDepositAddress(ChainType chainType, long userId, int biz) {
        return runtime.generateDepositAddress(ChainType.LTC, userId, biz);
    }

    /**
     * 按指定派生索引生成 LTC 存款地址。
     *
     * @param chainType  链类型（固定为 LTC）
     * @param userId     用户 ID
     * @param biz        业务标识
     * @param childIndex BIP44 子索引
     * @return 指定索引的 LTC 地址对象
     */
    @Override
    public Address generateDepositAddressAtIndex(
            ChainType chainType, long userId, int biz, long childIndex) {
        return runtime.generateDepositAddressAtIndex(ChainType.LTC, userId, biz, childIndex);
    }

    /**
     * 校验 LTC 地址格式是否合法。
     *
     * @param chainType 链类型（固定为 LTC）
     * @param address   待校验的地址字符串
     * @return true 表示地址格式合法
     */
    @Override
    public boolean checkAddress(ChainType chainType, String address) {
        return runtime.checkAddress(ChainType.LTC, address);
    }

    /**
     * 获取 LTC 充值确认数阈值。
     *
     * @param chainType 链类型（固定为 LTC）
     * @return 充值所需的区块确认数
     */
    @Override
    public long depositConfirmationThreshold(ChainType chainType) {
        return runtime.depositConfirmationThreshold(ChainType.LTC);
    }

    /**
     * 获取 LTC 粉尘阈值（单位：litoshis）。
     *
     * @param chainType 链类型（固定为 LTC）
     * @return 最小可花费金额（litoshis）
     */
    @Override
    public long dustThresholdAtomic(ChainType chainType) {
        return runtime.dustThresholdAtomic(ChainType.LTC);
    }

    /**
     * 获取 LTC 链当前最佳区块高度。
     *
     * @param chainType 链类型（固定为 LTC）
     * @return 当前最新区块高度
     */
    @Override
    public long bestHeight(ChainType chainType) {
        return runtime.bestHeight(ChainType.LTC);
    }

    /**
     * 扫描指定高度的区块，查找与平台地址相关的 LTC 交易。
     *
     * @param chainType 链类型（固定为 LTC）
     * @param height    区块高度
     * @return 相关交易列表
     */
    @Override
    public List<TransactionDTO> findRelatedTransactions(ChainType chainType, long height) {
        return runtime.findRelatedTransactions(ChainType.LTC, height);
    }

    /**
     * 刷新 LTC 链上所有待确认交易的确认数。
     *
     * @param chainType 链类型（固定为 LTC）
     */
    @Override
    public void updateTransactionConfirmations(ChainType chainType) {
        runtime.updateTransactionConfirmations(ChainType.LTC);
    }

    /**
     * 刷新 LTC 链上所有地址的总余额。
     *
     * @param chainType 链类型（固定为 LTC）
     */
    @Override
    public void updateTotalBalance(ChainType chainType) {
        runtime.updateTotalBalance(ChainType.LTC);
    }

    /**
     * 广播已签名的 LTC 提现交易。
     *
     * @param chainType   链类型（固定为 LTC）
     * @param transaction 包含签名数据的提现交易
     * @return 交易哈希（txid）
     */
    @Override
    public String broadcastSignedTransaction(ChainType chainType, WithdrawTransaction transaction) {
        return runtime.broadcastSignedTransaction(ChainType.LTC, transaction);
    }
}
