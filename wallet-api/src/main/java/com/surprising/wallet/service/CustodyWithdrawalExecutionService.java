package com.surprising.wallet.service;

import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.repository.CustodyRepository.AddressRecord;
import com.surprising.wallet.chain.cardano.CardanoKeyService;
import com.surprising.wallet.chain.monero.MoneroAddressValidator;
import com.surprising.wallet.chain.near.NearKeyService;
import com.surprising.wallet.chain.polkadot.PolkadotKeyService;
import com.surprising.wallet.repository.ChainJdbcRepository;
import com.surprising.wallet.repository.ChainAddressRepository;
import com.surprising.wallet.repository.ChainAssetRepository;
import com.surprising.wallet.repository.ChainProfileRepository;
import com.surprising.wallet.repository.CustodyAddressRepository;
import com.surprising.wallet.repository.LedgerBalanceRepository;
import com.surprising.wallet.repository.TokenConfigRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 负责钱包业务流程编排，并集中处理状态、校验和异常边界。
 */
@Service
public class CustodyWithdrawalExecutionService {
    /**
     * 定义 {@code RANDOM} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    private static final SecureRandom RANDOM = new SecureRandom();
    /**
     * 保存 {@code jdbc}，用于访问当前业务所依赖的仓储、客户端或服务。
     */
    /**
     * 保存 {@code chains}，表示链、网络、资产或代币配置。
     */
    private final ChainJdbcRepository chains;
    /**
     * 保存 {@code runtimeConfig}，用于保存运行配置和策略参数。
     */
    private final WalletRuntimeConfigService runtimeConfig;
    /** chain_asset 单表仓储。 */
    private final ChainAssetRepository chainAssetRepository;
    /** chain_profile 单表仓储。 */
    private final ChainProfileRepository chainProfileRepository;
    /** token_config 单表仓储。 */
    private final TokenConfigRepository tokenConfigRepository;
    /** custody_address 单表仓储。 */
    private final CustodyAddressRepository custodyAddressRepository;
    /** chain_address 单表仓储。 */
    private final ChainAddressRepository chainAddressRepository;
    /** ledger_balance 单表仓储。 */
    private final LedgerBalanceRepository ledgerBalanceRepository;

    /**
     * 构造 {@code CustodyWithdrawalExecutionService}，初始化该组件运行所需的状态和依赖。
     */
    public CustodyWithdrawalExecutionService(ChainJdbcRepository chains,
                                             WalletRuntimeConfigService runtimeConfig,
                                             ChainAssetRepository chainAssetRepository,
                                             ChainProfileRepository chainProfileRepository,
                                             TokenConfigRepository tokenConfigRepository,
                                             CustodyAddressRepository custodyAddressRepository,
                                             ChainAddressRepository chainAddressRepository,
                                             LedgerBalanceRepository ledgerBalanceRepository) {
        this.chains = chains;
        this.runtimeConfig = runtimeConfig;
        this.chainAssetRepository = chainAssetRepository;
        this.chainProfileRepository = chainProfileRepository;
        this.tokenConfigRepository = tokenConfigRepository;
        this.custodyAddressRepository = custodyAddressRepository;
        this.chainAddressRepository = chainAddressRepository;
        this.ledgerBalanceRepository = ledgerBalanceRepository;
    }

    /**
     * 执行或处理 {@code execute} 对应的业务流程，并维护状态和异常边界。
     */
    public ExecutionResult execute(UUID tenantId, AddressRecord custodyAddress,
                                   String chain, String symbol, String toAddress,
                                   BigDecimal amount, String orderPrefix) {
        runtimeConfig.requireTaskEnabled(
                chain, WalletRuntimeConfigService.TASK_WITHDRAW, "custody withdrawal create");
        AssetMeta asset = requireAsset(chain, symbol);
        validateExternalAddress(chain, toAddress);
        BigDecimal frozenAmount = amount.add(asset.networkFeeReserve());
        SpendAccount spend = requireTenantSpendAccount(
                tenantId, custodyAddress.id(), chain, symbol, frozenAmount);
        String sourceAddress = withdrawalSourceAddress(tenantId, asset, spend);
        String orderNo = normalizeOrderPrefix(orderPrefix) + "-" + System.currentTimeMillis()
                + "-" + randomSuffix();

        int created = chains.createTenantWithdrawalOrder(
                tenantId, orderNo, Integer.toUnsignedLong(custodyAddress.derivationSubject()),
                chain, symbol, sourceAddress, spend.accountId(), toAddress,
                amount, asset.networkFeeReserve());
        if (created != 1) {
            throw new IllegalStateException("duplicate withdrawal order");
        }
        if (!chains.freezeLedgerBalance(tenantId, chain, symbol, spend.accountId(), frozenAmount)) {
            chains.updateWithdrawalStatus(
                    tenantId, chain, orderNo, "FAILED", sourceAddress, null, "insufficient balance");
            throw new IllegalArgumentException("insufficient available balance");
        }

        boolean approvalRequired = runtimeConfig.isWithdrawalAdminApprovalRequired();
        String status = approvalRequired ? "PENDING_REVIEW" : "FROZEN";
        String statusMessage = approvalRequired
                ? "waiting for platform withdrawal approval before broadcast"
                : null;
        chains.updateWithdrawalStatus(
                tenantId, chain, orderNo, status, sourceAddress, null, statusMessage);
        return new ExecutionResult(orderNo, status, chain, symbol, amount,
                asset.networkFeeReserve(), toAddress, sourceAddress, spend.address(), approvalRequired);
    }
    /**
     * 校验 {@code requireAsset} 对应的前置条件，不满足时抛出明确异常。
     */
    private AssetMeta requireAsset(String chain, String symbol) {
        Map<String, Object> asset = chainAssetRepository.findActive(chain, symbol)
                .orElseThrow(() -> new IllegalArgumentException(
                        "asset withdrawal is not enabled: " + chain + "/" + symbol));
        boolean nativeAsset = Boolean.TRUE.equals(asset.get("native_asset"));
        Map<String, Object> profile = chainProfileRepository.listEnabledForWithdrawal(chain).stream()
                .filter(candidate -> nativeAsset
                        || tokenConfigRepository.findEnabled(chain,
                                String.valueOf(candidate.get("network")), symbol).isPresent())
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "asset withdrawal is not enabled: " + chain + "/" + symbol));
        int decimals = asset.get("decimals") instanceof Number number ? number.intValue() : 18;
        BigDecimal networkFee = "XMR".equals(chain)
                ? atomicToDecimal(profile.get("default_fee_rate"), decimals)
                    .max(atomicToDecimal(profile.get("dust_threshold"), decimals))
                    .max(new BigDecimal("0.0001")).stripTrailingZeros()
                : BigDecimal.ZERO;
        return new AssetMeta(
                chain, symbol, nativeAsset, String.valueOf(profile.get("native_symbol")), networkFee);
    }

    /**
     * 校验 {@code requireTenantSpendAccount} 对应的前置条件，不满足时抛出明确异常。
     */
    private SpendAccount requireTenantSpendAccount(UUID tenantId, UUID custodyAddressId,
                                                   String chain, String symbol,
                                                   BigDecimal requiredAmount) {
        Map<String, Object> custody = custodyAddressRepository.findByTenantAndId(
                        tenantId, custodyAddressId)
                .orElseThrow(() -> new IllegalArgumentException("insufficient available balance"));
        if (!chain.equalsIgnoreCase(String.valueOf(custody.get("chain")))
                || !"ACTIVE".equalsIgnoreCase(String.valueOf(custody.get("status")))) {
            throw new IllegalArgumentException("insufficient available balance");
        }
        long chainAddressId = ((Number) custody.get("chain_address_id")).longValue();
        Map<String, Object> address = chainAddressRepository.findByTenantAndId(tenantId, chainAddressId)
                .filter(row -> Boolean.TRUE.equals(row.get("enabled")))
                .orElseThrow(() -> new IllegalArgumentException("insufficient available balance"));
        String accountId = String.valueOf(address.get("account_id"));
        Map<String, Object> balance = ledgerBalanceRepository.listAvailable(
                        tenantId, chain, symbol, accountId, requiredAmount).stream()
                .findFirst().orElseThrow(() -> new IllegalArgumentException("insufficient available balance"));
        return new SpendAccount(String.valueOf(balance.get("account_id")),
                String.valueOf(address.get("address")));
    }
    /**
     * 处理 {@code withdrawalSourceAddress} 对应的链上或钱包业务流程，并维护状态、幂等和错误边界。
     */
    private String withdrawalSourceAddress(UUID tenantId, AssetMeta asset, SpendAccount spend) {
        if (ChainType.valueOf(asset.chain()).isUtxo()) {
            return spend.address();
        }
        return chains.findActiveTenantCollectionAddress(tenantId, asset.chain())
                .orElse(spend.address());
    }
    /**
     * 转换或计算 {@code normalizeOrderPrefix} 对应的值，统一金额、格式和边界规则。
     */
    private static String normalizeOrderPrefix(String value) {
        String prefix = value == null ? "" : value.replaceAll("[^A-Za-z0-9_-]", "");
        if (prefix.isBlank() || prefix.length() > 56) {
            throw new IllegalArgumentException("invalid withdrawal order prefix");
        }
        return prefix;
    }
    /**
     * 校验 {@code validateExternalAddress} 对应的前置条件，不满足时抛出明确异常。
     */
    private static void validateExternalAddress(String chain, String address) {
        String value = address == null ? "" : address.trim();
        if (value.isBlank() || value.length() > 160) {
            throw new IllegalArgumentException("valid withdrawal address is required");
        }
        if ((ChainType.valueOf(chain).isEvm() || "HYPERCORE".equals(chain))
                && !value.matches("^0x[0-9a-fA-F]{40}$")) {
            throw new IllegalArgumentException("invalid EVM address");
        }
        if ("TRON".equals(chain) && !value.matches("^T[1-9A-HJ-NP-Za-km-z]{33}$")) {
            throw new IllegalArgumentException("invalid TRON address");
        }
        if ("XRP".equals(chain) && !value.matches("^r[1-9A-HJ-NP-Za-km-z]{25,34}$")) {
            throw new IllegalArgumentException("invalid XRP address");
        }
        if ("XMR".equals(chain) && !MoneroAddressValidator.isValid(value)) {
            throw new IllegalArgumentException("invalid XMR address");
        }
        if ("ADA".equals(chain) && !CardanoKeyService.isValidAddress(value)) {
            throw new IllegalArgumentException("invalid ADA address");
        }
        if ("DOT".equals(chain) && !PolkadotKeyService.isValidSs58Address(value)) {
            throw new IllegalArgumentException("invalid DOT address");
        }
        if ("NEAR".equals(chain) && !NearKeyService.isValidAccountId(value)) {
            throw new IllegalArgumentException("invalid NEAR address");
        }
    }
    /**
     * 执行 {@code atomicToDecimal} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private static BigDecimal atomicToDecimal(Object value, int decimals) {
        return value == null ? BigDecimal.ZERO
                : new BigDecimal(String.valueOf(value)).movePointLeft(decimals);
    }
    /**
     * 执行 {@code randomSuffix} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private static String randomSuffix() {
        return Integer.toUnsignedString(RANDOM.nextInt(), 36).toLowerCase(Locale.ROOT);
    }

    private record AssetMeta(String chain, String symbol, boolean nativeAsset,
                             String nativeSymbol, BigDecimal networkFeeReserve) {
    }
    private record SpendAccount(String accountId, String address) {
    }

    public record ExecutionResult(String orderNo, String status, String chain, String symbol,
                                  BigDecimal amount, BigDecimal fee, String toAddress,
                                  String fromAddress, String debitAddress,
                                  boolean approvalRequired) {
    }
}
