package com.surprising.wallet.chain.btc;

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
 * Bitcoin (BTC) 链适配器，实现 {@link BlockchainAdapter} 接口。
 *
 * <p>BTC 采用 UTXO 模型，使用 P2WSH（Pay-to-Witness-Script-Hash）多签地址方案，
 * 通过 {@link BitcoinLikeChainRuntime} 委托核心操作（地址生成、余额扫描、交易广播等）。
 * 手续费估算基于 SegWit 费率模型，使用 {@link P2wshFeeCalculator} 按 1 输入 2 输出计算。
 *
 * <p>核心能力：原生报价、地址生成与校验、确认策略、粉尘策略、区块高度查询、
 * 区块交易扫描、确认数刷新、余额刷新、签名交易广播。
 */
@Component
public
class BtcChainAdapter implements BlockchainAdapter {

    /** BTC 链通用运行时，封装 UTXO 链的地址生成、扫描、余额等逻辑 */
    private final BitcoinLikeChainRuntime runtime;

    /**
     * @param runtime BTC 链运行时，不能为空
     */
    public BtcChainAdapter(BitcoinLikeChainRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public ChainType chainType() {
        return ChainType.BTC;
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
        return "bitcoin";
    }

    @Override
    public String describe() {
        return "Isolated BTC UTXO adapter with SegWit fee estimation and multisig-safe planning.";
    }

    /**
     * 估算 BTC 原生转账手续费。
     *
     * @param request 转账请求，包含金额和可选的费率（sat/vByte），未提供时默认 10 sat/vByte
     * @return 包含手续费估算的报价，金额单位为 BTC（8 位小数）
     */
    @Override
    public TransferQuote quoteNativeTransfer(TransferRequest request) {
        long feeRate = request.feeRateSatPerVByte() == null ? 10L : request.feeRateSatPerVByte();
        long amountSat = request.amount().movePointRight(8).setScale(0, RoundingMode.DOWN).longValueExact();
        long feeSat = P2wshFeeCalculator.calculateFeeSat(1, 2, feeRate);
        long feeBtcSat = feeSat;
        BigDecimal fee = BigDecimal.valueOf(feeBtcSat).movePointLeft(8);
        return new TransferQuote(request.chainType(), request.assetSymbol(), request.fromAddress(), request.toAddress(),
                request.amount(), fee, 0L, 1L, feeBtcSat, 0L, null, true, "btc utxo fee estimate");
    }

    /**
     * 生成 BTC 存款地址。
     *
     * @param chainType 链类型（固定为 BTC）
     * @param userId 用户 ID
     * @param biz 业务标识
     * @return 新生成的地址对象
     */
    @Override
    public Address generateDepositAddress(ChainType chainType, long userId, int biz) {
        return runtime.generateDepositAddress(ChainType.BTC, userId, biz);
    }

    /**
     * 按指定派生索引生成 BTC 存款地址。
     *
     * @param chainType  链类型（固定为 BTC）
     * @param userId     用户 ID
     * @param biz        业务标识
     * @param childIndex BIP44 子索引
     * @return 指定索引的地址对象
     */
    @Override
    public Address generateDepositAddressAtIndex(
            ChainType chainType, long userId, int biz, long childIndex) {
        return runtime.generateDepositAddressAtIndex(ChainType.BTC, userId, biz, childIndex);
    }

    /**
     * 校验 BTC 地址格式是否合法。
     *
     * @param chainType 链类型（固定为 BTC）
     * @param address   待校验的地址字符串
     * @return true 表示地址格式合法
     */
    @Override
    public boolean checkAddress(ChainType chainType, String address) {
        return runtime.checkAddress(ChainType.BTC, address);
    }

    /**
     * 获取 BTC 充值确认数阈值。
     *
     * @param chainType 链类型（固定为 BTC）
     * @return 充值所需的区块确认数
     */
    @Override
    public long depositConfirmationThreshold(ChainType chainType) {
        return runtime.depositConfirmationThreshold(ChainType.BTC);
    }

    /**
     * 获取 BTC 粉尘阈值（单位：satoshis）。
     *
     * @param chainType 链类型（固定为 BTC）
     * @return 最小可花费金额（satoshis）
     */
    @Override
    public long dustThresholdAtomic(ChainType chainType) {
        return runtime.dustThresholdAtomic(ChainType.BTC);
    }

    /**
     * 获取 BTC 链当前最佳区块高度。
     *
     * @param chainType 链类型（固定为 BTC）
     * @return 当前最新区块高度
     */
    @Override
    public long bestHeight(ChainType chainType) {
        return runtime.bestHeight(ChainType.BTC);
    }

    /**
     * 扫描指定高度的区块，查找与平台地址相关的 BTC 交易。
     *
     * @param chainType 链类型（固定为 BTC）
     * @param height    区块高度
     * @return 相关交易列表
     */
    @Override
    public List<TransactionDTO> findRelatedTransactions(ChainType chainType, long height) {
        return runtime.findRelatedTransactions(ChainType.BTC, height);
    }

    /**
     * 刷新 BTC 链上所有待确认交易的确认数。
     *
     * @param chainType 链类型（固定为 BTC）
     */
    @Override
    public void updateTransactionConfirmations(ChainType chainType) {
        runtime.updateTransactionConfirmations(ChainType.BTC);
    }

    /**
     * 刷新 BTC 链上所有地址的总余额。
     *
     * @param chainType 链类型（固定为 BTC）
     */
    @Override
    public void updateTotalBalance(ChainType chainType) {
        runtime.updateTotalBalance(ChainType.BTC);
    }

    /**
     * 广播已签名的 BTC 提现交易。
     *
     * @param chainType   链类型（固定为 BTC）
     * @param transaction 包含签名数据的提现交易
     * @return 交易哈希（txid）
     */
    @Override
    public String broadcastSignedTransaction(ChainType chainType, WithdrawTransaction transaction) {
        return runtime.broadcastSignedTransaction(ChainType.BTC, transaction);
    }
}
