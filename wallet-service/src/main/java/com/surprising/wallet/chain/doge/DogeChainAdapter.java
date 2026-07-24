package com.surprising.wallet.chain.doge;

import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.chain.TransferQuote;
import com.surprising.wallet.common.chain.TransferRequest;
import com.surprising.wallet.common.dto.TransactionDTO;
import com.surprising.wallet.common.pojo.Address;
import com.surprising.wallet.common.pojo.WithdrawTransaction;
import com.surprising.wallet.sdk.bitcoinj.core.P2wshFeeCalculator;
import com.surprising.wallet.chain.BlockchainAdapter;
import com.surprising.wallet.chain.utxo.BitcoinLikeChainRuntime;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Dogecoin (DOGE) 链适配器，实现 {@link BlockchainAdapter} 接口。
 *
 * <p>DOGE 采用 UTXO 模型，通过 {@link BitcoinLikeChainRuntime} 委托核心操作。
 * 与 BTC 的主要区别在于地址使用 P2SH（Legacy 3开头多签）方案，而非 P2WSH，
 * 并采用 Dogecoin 独立网络参数
 * （{@link com.surprising.wallet.sdk.bitcoinj.dogecoin.DogecoinNetworkParameters}）。
 * 默认费率为 1000 koinu/vByte。
 *
 * <p>核心能力：原生报价、地址生成与校验、确认策略、粉尘策略、区块高度查询、
 * 区块交易扫描、确认数刷新、余额刷新、签名交易广播。
 */
@Component
public
class DogeChainAdapter implements BlockchainAdapter {

    /** 默认费率：1000 koinu/vByte */
    private static final long DEFAULT_FEE_RATE_KOINU_PER_VBYTE = 1_000L;

    /** DOGE 链通用运行时，封装 UTXO 链的地址生成、扫描、余额等逻辑 */
    private final BitcoinLikeChainRuntime runtime;

    /**
     * @param runtime DOGE 链运行时，不能为空
     */
    public DogeChainAdapter(BitcoinLikeChainRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public ChainType chainType() {
        return ChainType.DOGE;
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
        return "dogecoin";
    }

    @Override
    public String describe() {
        return "Dogecoin UTXO adapter with legacy P2SH multisig address derivation and RPC-backed scanning.";
    }

    /**
     * 估算 DOGE 原生转账手续费。
     *
     * @param request 转账请求，包含金额和可选的费率（koinu/vByte），未提供时默认 1000
     * @return 包含手续费估算的报价，金额单位为 DOGE（8 位小数）
     */
    @Override
    public TransferQuote quoteNativeTransfer(TransferRequest request) {
        long feeRate = request.feeRateSatPerVByte() == null
                ? DEFAULT_FEE_RATE_KOINU_PER_VBYTE
                : request.feeRateSatPerVByte();
        long feeKoinu = P2wshFeeCalculator.calculateFeeSat(1, 2, feeRate);
        BigDecimal fee = BigDecimal.valueOf(feeKoinu).movePointLeft(8).setScale(8, RoundingMode.DOWN);
        return new TransferQuote(request.chainType(), request.assetSymbol(), request.fromAddress(), request.toAddress(),
                request.amount(), fee, 0L, 1L, feeKoinu, 0L, null, true, "doge p2sh fee estimate");
    }

    /**
     * 生成 DOGE 存款地址（P2SH Legacy 格式）。
     *
     * @param chainType 链类型（固定为 DOGE）
     * @param userId 用户 ID
     * @param biz 业务标识
     * @return 新生成的 DOGE 地址对象
     */
    @Override
    public Address generateDepositAddress(ChainType chainType, long userId, int biz) {
        return runtime.generateDepositAddress(ChainType.DOGE, userId, biz);
    }

    /**
     * 按指定派生索引生成 DOGE 存款地址。
     *
     * @param chainType  链类型（固定为 DOGE）
     * @param userId     用户 ID
     * @param biz        业务标识
     * @param childIndex BIP44 子索引
     * @return 指定索引的 DOGE 地址对象
     */
    @Override
    public Address generateDepositAddressAtIndex(
            ChainType chainType, long userId, int biz, long childIndex) {
        return runtime.generateDepositAddressAtIndex(ChainType.DOGE, userId, biz, childIndex);
    }

    /**
     * 校验 DOGE 地址格式是否合法。
     *
     * @param chainType 链类型（固定为 DOGE）
     * @param address   待校验的地址字符串
     * @return true 表示地址格式合法
     */
    @Override
    public boolean checkAddress(ChainType chainType, String address) {
        return runtime.checkAddress(ChainType.DOGE, address);
    }

    /**
     * 获取 DOGE 充值确认数阈值。
     *
     * @param chainType 链类型（固定为 DOGE）
     * @return 充值所需的区块确认数
     */
    @Override
    public long depositConfirmationThreshold(ChainType chainType) {
        return runtime.depositConfirmationThreshold(ChainType.DOGE);
    }

    /**
     * 获取 DOGE 粉尘阈值（单位：koinu）。
     *
     * @param chainType 链类型（固定为 DOGE）
     * @return 最小可花费金额（koinu）
     */
    @Override
    public long dustThresholdAtomic(ChainType chainType) {
        return runtime.dustThresholdAtomic(ChainType.DOGE);
    }

    /**
     * 获取 DOGE 链当前最佳区块高度。
     *
     * @param chainType 链类型（固定为 DOGE）
     * @return 当前最新区块高度
     */
    @Override
    public long bestHeight(ChainType chainType) {
        return runtime.bestHeight(ChainType.DOGE);
    }

    /**
     * 扫描指定高度的区块，查找与平台地址相关的 DOGE 交易。
     *
     * @param chainType 链类型（固定为 DOGE）
     * @param height    区块高度
     * @return 相关交易列表
     */
    @Override
    public List<TransactionDTO> findRelatedTransactions(ChainType chainType, long height) {
        return runtime.findRelatedTransactions(ChainType.DOGE, height);
    }

    /**
     * 刷新 DOGE 链上所有待确认交易的确认数。
     *
     * @param chainType 链类型（固定为 DOGE）
     */
    @Override
    public void updateTransactionConfirmations(ChainType chainType) {
        runtime.updateTransactionConfirmations(ChainType.DOGE);
    }

    /**
     * 刷新 DOGE 链上所有地址的总余额。
     *
     * @param chainType 链类型（固定为 DOGE）
     */
    @Override
    public void updateTotalBalance(ChainType chainType) {
        runtime.updateTotalBalance(ChainType.DOGE);
    }

    /**
     * 广播已签名的 DOGE 提现交易。
     *
     * @param chainType   链类型（固定为 DOGE）
     * @param transaction 包含签名数据的提现交易
     * @return 交易哈希（txid）
     */
    @Override
    public String broadcastSignedTransaction(ChainType chainType, WithdrawTransaction transaction) {
        return runtime.broadcastSignedTransaction(ChainType.DOGE, transaction);
    }
}
