package com.surprising.wallet.service;

import com.surprising.wallet.chain.BlockchainRuntimeService;
import com.surprising.wallet.chain.model.CustodyAddressRecord;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.repository.ChainAddressRepository;
import com.surprising.wallet.repository.ChainAssetRepository;
import com.surprising.wallet.repository.ChainProfileRepository;
import com.surprising.wallet.repository.CustodyAddressRepository;
import com.surprising.wallet.repository.LedgerBalanceRepository;
import com.surprising.wallet.repository.TokenConfigRepository;
import com.surprising.wallet.repository.WithdrawalOrderRepository;
import java.math.BigDecimal;
import com.surprising.wallet.chain.model.ChainAsset;
import com.surprising.wallet.common.chain.AccountChainProfile;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class CustodyWithdrawalExecutionService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final WithdrawalOrderRepository withdrawalOrderRepository;
    private final CollectionPlanningService collectionPlanningService;

    private final WalletRuntimeConfigService runtimeConfig;
    private final BlockchainRuntimeService runtime;
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

    public CustodyWithdrawalExecutionService(
            WithdrawalOrderRepository withdrawalOrderRepository,
            CollectionPlanningService collectionPlanningService,
            WalletRuntimeConfigService runtimeConfig,
            BlockchainRuntimeService runtime,
            ChainAssetRepository chainAssetRepository,
            ChainProfileRepository chainProfileRepository,
            TokenConfigRepository tokenConfigRepository,
            CustodyAddressRepository custodyAddressRepository,
            ChainAddressRepository chainAddressRepository,
            LedgerBalanceRepository ledgerBalanceRepository) {
        this.withdrawalOrderRepository = withdrawalOrderRepository;
        this.collectionPlanningService = collectionPlanningService;
        this.runtimeConfig = runtimeConfig;
        this.runtime = runtime;
        this.chainAssetRepository = chainAssetRepository;
        this.chainProfileRepository = chainProfileRepository;
        this.tokenConfigRepository = tokenConfigRepository;
        this.custodyAddressRepository = custodyAddressRepository;
        this.chainAddressRepository = chainAddressRepository;
        this.ledgerBalanceRepository = ledgerBalanceRepository;
    }

    public ExecutionResult execute(UUID tenantId, CustodyAddressRecord custodyAddress,
                                   String chain, String symbol, String toAddress,
                                   BigDecimal amount, String orderPrefix) {
        runtimeConfig.requireTaskEnabled(
                chain, WalletRuntimeConfigService.TASK_WITHDRAW, "custody withdrawal create");
        AssetMeta asset = requireAsset(chain, symbol);
        toAddress = validateExternalAddress(chain, toAddress);
        BigDecimal frozenAmount = amount.add(asset.networkFeeReserve());
        SpendAccount spend = requireTenantSpendAccount(
                tenantId, custodyAddress.id(), chain, symbol, frozenAmount);
        String sourceAddress = withdrawalSourceAddress(tenantId, asset, spend);
        String orderNo = normalizeOrderPrefix(orderPrefix) + "-" + System.currentTimeMillis()
                + "-" + randomSuffix();

        int created = withdrawalOrderRepository.createForTenant(
                tenantId,
                orderNo,
                Integer.toUnsignedLong(custodyAddress.derivationSubject()),
                chain,
                symbol,
                sourceAddress,
                spend.accountId(),
                toAddress,
                amount,
                asset.networkFeeReserve());
        if (created != 1) {
            throw new IllegalStateException("duplicate withdrawal order");
        }
        if (!ledgerBalanceRepository.freeze(chain, symbol, spend.accountId(), frozenAmount, tenantId)) {
            withdrawalOrderRepository.updateStatus(tenantId, chain, orderNo, "FAILED", sourceAddress, null, "insufficient balance");
            throw new IllegalArgumentException("insufficient available balance");
        }

        boolean approvalRequired = runtimeConfig.isWithdrawalAdminApprovalRequired();
        String status = approvalRequired ? "PENDING_REVIEW" : "FROZEN";
        String statusMessage = approvalRequired
                ? "waiting for platform withdrawal approval before broadcast"
                : null;
        withdrawalOrderRepository.updateStatus(tenantId, chain, orderNo, status, sourceAddress, null, statusMessage);
        long orderId = withdrawalOrderRepository.find(chain, orderNo, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("withdrawal order not found")).getId();
        return new ExecutionResult(orderId, orderNo, status, amount, asset.networkFeeReserve(), toAddress);
    }
    private AssetMeta requireAsset(String chain, String symbol) {
        ChainAsset asset = chainAssetRepository.findActive(chain, symbol)
                .orElseThrow(() -> new IllegalArgumentException(
                        "asset withdrawal is not enabled: " + chain + "/" + symbol));
        boolean nativeAsset = Boolean.TRUE.equals(asset.getNativeAsset());
        AccountChainProfile profile = chainProfileRepository.listEnabledForWithdrawal(chain).stream()
                .filter(candidate -> nativeAsset
                        || tokenConfigRepository.findEnabled(chain,
                                String.valueOf(candidate.getNetwork()), symbol).isPresent())
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "asset withdrawal is not enabled: " + chain + "/" + symbol));
        int decimals = asset.getDecimals() == null ? 18 : asset.getDecimals();
        BigDecimal networkFee = "XMR".equals(chain)
                ? atomicToDecimal(profile.getDefaultFee(), decimals)
                    .max(atomicToDecimal(profile.getDustThreshold(), decimals))
                    .max(new BigDecimal("0.0001")).stripTrailingZeros()
                : BigDecimal.ZERO;
        return new AssetMeta(
                chain, symbol, nativeAsset, String.valueOf(profile.getNativeSymbol()), networkFee);
    }

    private SpendAccount requireTenantSpendAccount(UUID tenantId, UUID custodyAddressId,
                                                   String chain, String symbol,
                                                   BigDecimal requiredAmount) {
        var custody = custodyAddressRepository.findFullByTenantAndId(
                        tenantId, custodyAddressId)
                .orElseThrow(() -> new IllegalArgumentException("insufficient available balance"));
        if (!chain.equalsIgnoreCase(String.valueOf(custody.chain()))
                || !"ACTIVE".equalsIgnoreCase(String.valueOf(custody.status()))) {
            throw new IllegalArgumentException("insufficient available balance");
        }
        long chainAddressId = custody.chainAddressId();
        var address = chainAddressRepository.findOwned(tenantId, chainAddressId)
                .filter(row -> Boolean.TRUE.equals(row.getEnabled()))
                .orElseThrow(() -> new IllegalArgumentException("insufficient available balance"));
        String accountId = String.valueOf(address.getAccountId());
        String availableAccount = ledgerBalanceRepository.findAvailableAccount(
                        tenantId, chain, symbol, accountId, requiredAmount)
                .orElseThrow(() -> new IllegalArgumentException("insufficient available balance"));
        return new SpendAccount(availableAccount, address.getAddress());
    }
    private String withdrawalSourceAddress(UUID tenantId, AssetMeta asset, SpendAccount spend) {
        if (ChainType.valueOf(asset.chain()).isUtxo()) {
            return spend.address();
        }
        return collectionPlanningService.destination(tenantId, asset.chain())
                .orElse(spend.address());
    }
    private static String normalizeOrderPrefix(String value) {
        String prefix = value == null ? "" : value.replaceAll("[^A-Za-z0-9_-]", "");
        if (prefix.isBlank() || prefix.length() > 56) {
            throw new IllegalArgumentException("invalid withdrawal order prefix");
        }
        return prefix;
    }
    private String validateExternalAddress(String chain, String address) {
        String value = address == null ? "" : address.trim();
        if (value.isBlank() || value.length() > 160) {
            throw new IllegalArgumentException("valid withdrawal address is required");
        }
        if (!runtime.checkAddress(chain, value)) {
            throw new IllegalArgumentException("invalid " + chain + " address");
        }
        return value;
    }
    private static BigDecimal atomicToDecimal(Object value, int decimals) {
        return value == null ? BigDecimal.ZERO
                : new BigDecimal(String.valueOf(value)).movePointLeft(decimals);
    }
    private static String randomSuffix() {
        return Integer.toUnsignedString(RANDOM.nextInt(), 36).toLowerCase(Locale.ROOT);
    }

    private record AssetMeta(String chain, String symbol, boolean nativeAsset,
                             String nativeSymbol, BigDecimal networkFeeReserve) {
    }
    private record SpendAccount(String accountId, String address) {
    }

    public record ExecutionResult(long orderId, String orderNo, String status,
                                  BigDecimal amount, BigDecimal fee, String toAddress) {
    }
}
