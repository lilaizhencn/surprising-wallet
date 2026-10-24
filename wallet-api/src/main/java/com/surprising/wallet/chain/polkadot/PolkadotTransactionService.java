package com.surprising.wallet.chain.polkadot;

import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.TokenDefinition;
import com.surprising.wallet.repository.CollectionRecordRepository;
import com.surprising.wallet.repository.WalletSystemConfigRepository;
import com.surprising.wallet.sdk.ed25519.Ed25519DerivedKey;
import com.surprising.wallet.service.HotWalletAddressService;
import com.surprising.wallet.service.WalletRuntimeConfigService;
import com.surprising.wallet.service.WithdrawalSettlementService;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

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
public class PolkadotTransactionService {

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
    private final CollectionRecordRepository collectionRecordRepository;
    private final WalletSystemConfigRepository walletSystemConfigRepository;


    /** 热钱包地址服务（用于 Gas 补充） */
    private final HotWalletAddressService hotWalletAddressService;
    private final WithdrawalSettlementService settlements;

    /** 运行时任务开关服务。 */
    @NonNull
    private final WalletRuntimeConfigService runtimeConfigService;
    public String sendNative(ChainAddressRecord from, String toAddress, BigInteger amountPlanck) {
        return sendNative(from, toAddress, amountPlanck, true);
    }

    private String sendNative(ChainAddressRecord from, String toAddress, BigInteger amountPlanck,
                              boolean keepAlive) {
        PolkadotRuntimeClient.SubmittedTransaction tx = runtimeClient.sendNative(
                secretSeedHex(from), from.getAddress(), toAddress, amountPlanck, keepAlive);
        return tx.txHash();
    }
    public String sendAsset(ChainAddressRecord from, TokenDefinition token, String toAddress, BigDecimal amount) {
        return sendAsset(from, token, toAddress, amount, true);
    }

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

    public boolean confirmWithdrawal(java.util.UUID tenantId, AccountChainProfile profile,
                                     String orderNo, String txHash, String assetSymbol) {
        if (!transactionFinalized(assetSymbol, txHash, confirmationLookback(profile))) {
            return false;
        }
        return settlements.confirm(tenantId, CHAIN, orderNo, txHash);
    }

    public String collectNative(java.util.UUID tenantId, String collectionNo, ChainAddressRecord from,
                                String hotAddress, BigInteger amountPlanck) {
        runtimeConfigService.requireTaskEnabled(CHAIN, WalletRuntimeConfigService.TASK_COLLECTION, "polkadot collectNative");
        return collect(tenantId, collectionNo, () -> sendNative(from, hotAddress, amountPlanck, false));
    }

    public String collectAsset(java.util.UUID tenantId, String collectionNo, ChainAddressRecord from,
                               TokenDefinition token, String hotAddress, BigDecimal amount) {
        runtimeConfigService.requireTaskEnabled(CHAIN, WalletRuntimeConfigService.TASK_COLLECTION, "polkadot collectAsset");
        return collect(tenantId, collectionNo, () -> sendAsset(from, token, hotAddress, amount, false));
    }

    public boolean confirmCollection(java.util.UUID tenantId, AccountChainProfile profile,
                                     String collectionNo, String assetSymbol) {
        String txHash = collectionRecordRepository.findTxHash(tenantId, CHAIN, collectionNo).orElseThrow();
        if (transactionFinalized(assetSymbol, txHash, confirmationLookback(profile))) {
            return collectionRecordRepository.markConfirmed(tenantId, CHAIN, collectionNo, txHash) == 1;
        }
        return false;
    }
    public static BigInteger toPlanck(BigDecimal amount) {
        return toAtomic(amount, DOT_DECIMALS);
    }
    public static BigDecimal fromPlanck(BigInteger amount) {
        return new BigDecimal(amount == null ? BigInteger.ZERO : amount).movePointLeft(DOT_DECIMALS)
                .stripTrailingZeros();
    }
    private String collect(java.util.UUID tenantId, String collectionNo, TxSubmitter submitter) {
        Optional<String> existing = collectionRecordRepository.findTxHash(tenantId, CHAIN, collectionNo);
        if (existing.isPresent()) {
            return existing.get();
        }
        if (collectionRecordRepository.claimSigning(tenantId, CHAIN, collectionNo, null) != 1) {
            return collectionRecordRepository.findTxHash(tenantId, CHAIN, collectionNo)
                    .orElseThrow(() -> new IllegalStateException("DOT collection is not retryable"));
        }
        try {
            String txHash = submitter.submit();
            collectionRecordRepository.updateStatus(tenantId, CHAIN, collectionNo, "SENT", txHash, null, null);
            return txHash;
        } catch (RuntimeException e) {
            collectionRecordRepository.updateStatus(tenantId, CHAIN, collectionNo, "FAILED", null, e.getMessage(), null);
            throw e;
        }
    }
    private String secretSeedHex(ChainAddressRecord from) {
        Ed25519DerivedKey key = keyService.derive(from.getUserId(), from.getBiz(), from.getAddressIndex());
        return HexFormat.of().formatHex(key.privateSeed());
    }
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
    private BigInteger systemPlanck(String key, BigInteger fallback) {
        return walletSystemConfigRepository.find(key).filter(WalletSystemConfigRepository.Setting::enabled).map(WalletSystemConfigRepository.Setting::value)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(BigInteger::new)
                .filter(value -> value.signum() > 0)
                .orElse(fallback);
    }
    private static boolean sameAddress(String left, String right) {
        return left != null && right != null && left.equalsIgnoreCase(right);
    }
    private static int confirmationLookback(AccountChainProfile profile) {
        Integer configured = profile.getWithdrawConfirmations();
        int confirmations = configured == null || configured <= 0 ? 12 : configured;
        return Math.max(512, confirmations * 20);
    }
    private boolean transactionFinalized(String assetSymbol, String txHash, int maxRecentBlocks) {
        if (SYMBOL.equalsIgnoreCase(assetSymbol)) {
            return runtimeClient.transactionFinalized(txHash, maxRecentBlocks);
        }
        return runtimeClient.assetTransactionFinalized(txHash, maxRecentBlocks);
    }
    private static BigInteger toAtomic(BigDecimal amount, int decimals) {
        return amount.movePointRight(decimals).setScale(0, RoundingMode.UNNECESSARY).toBigIntegerExact();
    }

    @FunctionalInterface
    private interface TxSubmitter {
        String submit();
    }
    public record DeployAssetResult(String txHash, String assetId, long blockHeight) {
    }
}
