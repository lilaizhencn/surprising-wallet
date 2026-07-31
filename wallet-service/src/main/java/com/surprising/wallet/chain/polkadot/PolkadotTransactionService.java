package com.surprising.wallet.chain.polkadot;

import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.TokenDefinition;
import com.surprising.wallet.sdk.ed25519.Ed25519DerivedKey;
import com.surprising.wallet.config.WalletRuntimeConfigService;
import com.surprising.wallet.deposit.repository.ChainJdbcRepository;
import com.surprising.wallet.wallet.service.HotWalletAddressService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Polkadot 链交易服务，负责通过运行时服务构造和广播交易。
 *
 * <p>支持 DOT 原生转账和 Asset Hub 资产转账。在发送 Asset Hub 资产转账前，
 * 会自动检查发送方在 Asset Hub 上的 DOT 余额是否足够支付 Gas，不足时
 * 从热钱包自动补充（Gas top-up）。</p>
 *
 * @see PolkadotRuntimeClient
 * @see PolkadotKeyService
 */
@Service
@RequiredArgsConstructor
public
class PolkadotTransactionService {

    /** 链标识 */
    private static final String CHAIN = PolkadotRuntimeClient.CHAIN;

    /** 原生币符号 */
    private static final String SYMBOL = "DOT";

    /** DOT 小数位数（1 DOT = 10^10 Planck） */
    private static final int DOT_DECIMALS = 10;

    /** Asset Hub 上发送方最低 Gas 余额（Planck），默认 200 亿 Planck */
    private static final BigInteger DEFAULT_ASSET_HUB_MIN_GAS_PLANCK = new BigInteger("20000000000");

    /** Asset Hub Gas 补充金额（Planck），默认 1000 亿 Planck */
    private static final BigInteger DEFAULT_ASSET_HUB_GAS_TOPUP_PLANCK = new BigInteger("100000000000");

    /** 运行时客户端 */
    private final PolkadotRuntimeClient runtimeClient;

    /** Polkadot 密钥服务 */
    private final PolkadotKeyService keyService;

    /** 数据库仓库 */
    private final ChainJdbcRepository repository;

    /** 热钱包地址服务（用于 Gas 补充） */
    private final HotWalletAddressService hotWalletAddressService;

    /** 运行时配置服务（可选） */
    @Autowired(required = false)
    private WalletRuntimeConfigService runtimeConfigService;
    /**
     * 发送或广播 {@code sendNative} 对应的链上请求，并返回节点处理结果。
     */
    public String sendNative(ChainAddressRecord from, String toAddress, BigInteger amountPlanck) {
        return sendNative(from, toAddress, amountPlanck, true);
    }

    /**
     * 发送或广播 {@code sendNative} 对应的链上请求，并返回节点处理结果。
     */
    private String sendNative(ChainAddressRecord from, String toAddress, BigInteger amountPlanck,
                              boolean keepAlive) {
        PolkadotRuntimeClient.SubmittedTransaction tx = runtimeClient.sendNative(
                secretSeedHex(from), from.getAddress(), toAddress, amountPlanck, keepAlive);
        return tx.txHash();
    }
    /**
     * 发送或广播 {@code sendAsset} 对应的链上请求，并返回节点处理结果。
     */
    public String sendAsset(ChainAddressRecord from, TokenDefinition token, String toAddress, BigDecimal amount) {
        return sendAsset(from, token, toAddress, amount, true);
    }

    /**
     * 执行 {@code deployAsset} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public DeployAssetResult deployAsset(ChainAddressRecord deployer,
                                         String assetId,
                                         String name,
                                         String symbol,
                                         int decimals,
                                         BigInteger minBalance,
                                         BigInteger initialSupply,
                                         boolean mintable) {
        PolkadotRuntimeClient.AssetCreateResult result = runtimeClient.createAsset(
                secretSeedHex(deployer),
                deployer.getAddress(),
                assetId,
                name,
                symbol,
                decimals,
                minBalance,
                initialSupply,
                mintable);
        return new DeployAssetResult(result.txHash(), result.assetId(), result.blockHeight());
    }

    /**
     * 发送或广播 {@code sendAsset} 对应的链上请求，并返回节点处理结果。
     */
    private String sendAsset(ChainAddressRecord from, TokenDefinition token, String toAddress, BigDecimal amount,
                             boolean keepAlive) {
        String assetId = PolkadotRuntimeClient.normalizeAssetId(token.getContractAddress());
        if (assetId.isBlank()) {
            throw new IllegalStateException("missing DOT Asset Hub asset id for " + token.getSymbol());
        }
        ensureAssetHubGas(from);
        PolkadotRuntimeClient.SubmittedTransaction tx = runtimeClient.sendAsset(
                secretSeedHex(from), from.getAddress(), assetId, toAddress,
                toAtomic(amount, token.getDecimals()), keepAlive);
        return tx.txHash();
    }

    /**
     * 处理 {@code confirmWithdrawal} 对应的链上或钱包业务流程，并维护状态、幂等和错误边界。
     */
    public boolean confirmWithdrawal(java.util.UUID tenantId, AccountChainProfile profile,
                                     String orderNo, String txHash,
                                     String assetSymbol, String debitAccountId, BigDecimal debitAmount) {
        if (!transactionFinalized(assetSymbol, txHash, confirmationLookback(profile))) {
            return false;
        }
        return repository.confirmWithdrawalAndSettle(tenantId, CHAIN, orderNo, txHash,
                assetSymbol, debitAccountId, debitAmount);
    }

    /**
     * 处理 {@code collectNative} 对应的链上或钱包业务流程，并维护状态、幂等和错误边界。
     */
    public String collectNative(java.util.UUID tenantId, String collectionNo, ChainAddressRecord from,
                                String hotAddress, BigInteger amountPlanck) {
        requireTaskEnabled(WalletRuntimeConfigService.TASK_COLLECTION, "polkadot collectNative");
        return collect(tenantId, collectionNo, () -> sendNative(from, hotAddress, amountPlanck, false));
    }

    /**
     * 处理 {@code collectAsset} 对应的链上或钱包业务流程，并维护状态、幂等和错误边界。
     */
    public String collectAsset(java.util.UUID tenantId, String collectionNo, ChainAddressRecord from,
                               TokenDefinition token, String hotAddress, BigDecimal amount) {
        requireTaskEnabled(WalletRuntimeConfigService.TASK_COLLECTION, "polkadot collectAsset");
        return collect(tenantId, collectionNo, () -> sendAsset(from, token, hotAddress, amount, false));
    }

    /**
     * 处理 {@code confirmCollection} 对应的链上或钱包业务流程，并维护状态、幂等和错误边界。
     */
    public boolean confirmCollection(java.util.UUID tenantId, AccountChainProfile profile,
                                     String collectionNo, String assetSymbol) {
        String txHash = repository.findCollectionTxHash(tenantId, CHAIN, collectionNo).orElseThrow();
        if (transactionFinalized(assetSymbol, txHash, confirmationLookback(profile))) {
            return repository.markCollectionConfirmed(tenantId, CHAIN, collectionNo, txHash) == 1;
        }
        return false;
    }
    /**
     * 编码 {@code toPlanck} 对应的数据，生成链上或接口所需的表示。
     */
    public static BigInteger toPlanck(BigDecimal amount) {
        return toAtomic(amount, DOT_DECIMALS);
    }
    /**
     * 解析 {@code fromPlanck} 对应的输入，并转换为当前业务模型。
     */
    public static BigDecimal fromPlanck(BigInteger amount) {
        return new BigDecimal(amount == null ? BigInteger.ZERO : amount).movePointLeft(DOT_DECIMALS)
                .stripTrailingZeros();
    }
    /**
     * 处理 {@code collect} 对应的链上或钱包业务流程，并维护状态、幂等和错误边界。
     */
    private String collect(java.util.UUID tenantId, String collectionNo, TxSubmitter submitter) {
        Optional<String> existing = repository.findCollectionTxHash(tenantId, CHAIN, collectionNo);
        if (existing.isPresent()) {
            return existing.get();
        }
        if (repository.claimCollectionSigning(tenantId, CHAIN, collectionNo, null) != 1) {
            return repository.findCollectionTxHash(tenantId, CHAIN, collectionNo)
                    .orElseThrow(() -> new IllegalStateException("DOT collection is not retryable"));
        }
        try {
            String txHash = submitter.submit();
            repository.updateCollectionStatus(tenantId, CHAIN, collectionNo, "SENT", txHash, null, null);
            return txHash;
        } catch (RuntimeException e) {
            repository.updateCollectionStatus(tenantId, CHAIN, collectionNo,
                    "FAILED", null, e.getMessage(), null);
            throw e;
        }
    }
    /**
     * 转换或计算 {@code secretSeedHex} 对应的值，统一金额、格式和边界规则。
     */
    private String secretSeedHex(ChainAddressRecord from) {
        Ed25519DerivedKey key = keyService.derive(from.getUserId(), from.getBiz(), from.getAddressIndex());
        return HexFormat.of().formatHex(key.privateSeed());
    }
    /**
     * 校验 {@code ensureAssetHubGas} 对应的输入或状态，失败时抛出明确异常。
     */
    private void ensureAssetHubGas(ChainAddressRecord sender) {
        BigInteger minimum = systemPlanck("dot.asset_hub.min_sender_gas.planck",
                DEFAULT_ASSET_HUB_MIN_GAS_PLANCK);
        BigInteger balance = runtimeClient.assetHubNativeBalance(sender.getAddress());
        if (balance.compareTo(minimum) >= 0) {
            return;
        }
        ChainAddressRecord hot = hotWalletAddressService.findDefaultHotAddress(CHAIN, SYMBOL)
                .orElseThrow(() -> new IllegalStateException("missing DOT hot wallet for Asset Hub gas top-up"));
        if (sameAddress(hot.getAddress(), sender.getAddress())) {
            throw new IllegalStateException("DOT Asset Hub hot wallet balance below token gas reserve");
        }
        BigInteger topUp = systemPlanck("dot.asset_hub.token.gas_topup.planck",
                DEFAULT_ASSET_HUB_GAS_TOPUP_PLANCK);
        BigInteger shortfall = minimum.subtract(balance);
        BigInteger amount = topUp.max(shortfall);
        runtimeClient.sendAssetHubNative(secretSeedHex(hot), hot.getAddress(), sender.getAddress(), amount, true);
        BigInteger after = runtimeClient.assetHubNativeBalance(sender.getAddress());
        if (after.compareTo(minimum) < 0) {
            throw new IllegalStateException("DOT Asset Hub gas top-up did not reach minimum reserve");
        }
    }
    /**
     * 执行 {@code systemPlanck} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private BigInteger systemPlanck(String key, BigInteger fallback) {
        return repository.systemValue(key)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(BigInteger::new)
                .filter(value -> value.signum() > 0)
                .orElse(fallback);
    }
    /**
     * 执行 {@code sameAddress} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private static boolean sameAddress(String left, String right) {
        return left != null && right != null && left.equalsIgnoreCase(right);
    }
    /**
     * 处理 {@code confirmationLookback} 对应的链上或钱包业务流程，并维护状态、幂等和错误边界。
     */
    private static int confirmationLookback(AccountChainProfile profile) {
        Integer configured = profile.getWithdrawConfirmations();
        int confirmations = configured == null || configured <= 0 ? 12 : configured;
        return Math.max(512, confirmations * 20);
    }
    /**
     * 执行 {@code transactionFinalized} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private boolean transactionFinalized(String assetSymbol, String txHash, int maxRecentBlocks) {
        if (SYMBOL.equalsIgnoreCase(assetSymbol)) {
            return runtimeClient.transactionFinalized(txHash, maxRecentBlocks);
        }
        return runtimeClient.assetTransactionFinalized(txHash, maxRecentBlocks);
    }
    /**
     * 编码 {@code toAtomic} 对应的数据，生成链上或接口所需的表示。
     */
    private static BigInteger toAtomic(BigDecimal amount, int decimals) {
        return amount.movePointRight(decimals).setScale(0, RoundingMode.UNNECESSARY).toBigIntegerExact();
    }
    /**
     * 校验 {@code requireTaskEnabled} 对应的前置条件，不满足时抛出明确异常。
     */
    private void requireTaskEnabled(String task, String operation) {
        if (runtimeConfigService != null) {
            runtimeConfigService.requireTaskEnabled(CHAIN, task, operation);
        }
    }

    /**
     * 该类型封装所在链或钱包模块的配置、业务状态和校验逻辑。
     */
    @FunctionalInterface
    private interface TxSubmitter {
        /**
         * 发送或广播 {@code submit} 对应的链上请求，并返回节点处理结果。
         */
        String submit();
    }
    public record DeployAssetResult(String txHash, String assetId, long blockHeight) {
    }
}
