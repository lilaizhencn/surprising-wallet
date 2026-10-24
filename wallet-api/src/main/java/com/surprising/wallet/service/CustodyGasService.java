package com.surprising.wallet.service;

import com.surprising.wallet.model.custody.GasPricingMetadata;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.chain.model.CustodyGasUsageEntry;
import com.surprising.wallet.repository.CustodyGasAccountRepository;
import com.surprising.wallet.repository.ChainProfileRepository;
import com.surprising.wallet.repository.ChainAssetRepository;
import com.surprising.wallet.repository.CustodyGasUsageRepository;
import com.surprising.wallet.repository.CustodyDepositRepository;
import com.surprising.wallet.repository.CustodyDerivationSubjectRepository;
import com.surprising.wallet.repository.CustodyAddressRepository;
import com.surprising.wallet.repository.ChainAddressRepository;
import com.surprising.wallet.repository.LedgerBalanceRepository;
import com.surprising.wallet.repository.CustodyAuditRepository;
import com.surprising.wallet.chain.model.LedgerBalanceRecord;
import org.springframework.transaction.annotation.Isolation;
import com.surprising.wallet.service.CustodyAddressService.AddressView;
import com.surprising.wallet.service.CustodyAddressService.CreateAddressCommand;
import com.surprising.wallet.model.custody.GasAccountRecord;
import com.surprising.wallet.chain.BlockchainRuntimeService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.Optional;
import java.util.stream.Collectors;

import com.surprising.wallet.exception.CustodyForbiddenException;
import com.surprising.wallet.model.CustodyPrincipal;

/**
 * 托管 Gas 账户服务，管理租户的原生币 Gas 预充值账户。
 *
 * <p>租户需预先向 Gas 账户充值原生币，用于支付提现和归集的手续费。
 * 提供 Gas 余额查询、充值、对账功能。每个租户的每条链有独立的 Gas 账户。
 * 归集使用的子地址索引固定为 {@value #COLLECTION_CHILD_INDEX}。
 */
@Service
public class CustodyGasService {
    /** 归集合约交互使用的子地址索引 */
    static final long COLLECTION_CHILD_INDEX = 1L;
    private static final BigDecimal DEFAULT_LOW_BALANCE_THRESHOLD = new BigDecimal("0.01");
    private static final String SYSTEM_REFERENCE_PREFIX = "__sw_collection__:";
    private final CustodyAddressRepository custodyAddresses;
    private final ChainAddressRepository chainAddresses;
    private final LedgerBalanceRepository balances;
    private final CustodyAuditRepository audits;
    private final CustodyGasAccountRepository gasAccounts;
    private final CustodyGasUsageRepository usages;
    private final CustodyGasAccountingService accounting;
    private final ChainProfileRepository profiles;
    private final ChainAssetRepository assets;
    private final CustodyDepositRepository deposits;
    private final CustodyDerivationSubjectRepository subjects;
    private final CustodyAddressService addresses;
    private final BlockchainRuntimeService runtime;

    public CustodyGasService(CustodyAddressService addresses,
                             BlockchainRuntimeService runtime, CustodyGasAccountRepository gasAccounts,
                             CustodyGasUsageRepository usages, CustodyDepositRepository deposits,
                             CustodyDerivationSubjectRepository subjects, CustodyGasAccountingService accounting,
                             ChainProfileRepository profiles, ChainAssetRepository assets,
                             CustodyAddressRepository custodyAddresses, ChainAddressRepository chainAddresses,
                             LedgerBalanceRepository balances, CustodyAuditRepository audits) {
        this.custodyAddresses = custodyAddresses;
        this.chainAddresses = chainAddresses;
        this.balances = balances;
        this.audits = audits;
        this.addresses = addresses;
        this.runtime = runtime;
        this.gasAccounts = gasAccounts;
        this.usages = usages;
        this.deposits = deposits;
        this.subjects = subjects;
        this.accounting = accounting;
        this.profiles = profiles;
        this.assets = assets;
    }
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<GasAccountView> list(CustodyPrincipal principal) {
        requireScope(principal, "assets:read");
        return listForTenant(principal.tenantId())
                .stream().map(CustodyGasService::toView).toList();
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<GasAccountRecord> listForTenant(UUID tenantId) {
        return loadAccounts(tenantId, gasAccounts.listByTenant(tenantId));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public GasAccountRecord requireGasAccount(UUID tenantId, UUID id) {
        var row = gasAccounts.findById(tenantId, id)
                .orElseThrow(() -> new IllegalArgumentException("gas account not found"));
        return loadAccounts(tenantId, List.of(row)).getFirst();
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Optional<GasAccountRecord> findGasAccount(UUID tenantId, String chain) {
        return gasAccounts.findByChain(tenantId, chain)
                .map(row -> loadAccounts(tenantId, List.of(row)).getFirst());
    }

    private List<GasAccountRecord> loadAccounts(UUID tenantId, List<CustodyGasAccountRepository.Definition> definitions) {
        if (definitions.isEmpty()) return List.of();
        var custody = custodyAddresses.findByIds(tenantId, definitions.stream()
                .map(CustodyGasAccountRepository.Definition::custodyAddressId).toList()).stream()
                .collect(Collectors.toMap(row -> row.id(), row -> row));
        var chain = chainAddresses.findByIds(tenantId, custody.values().stream()
                .map(row -> row.chainAddressId()).distinct().toList()).stream()
                .collect(Collectors.toMap(row -> row.getId(), row -> row));
        var ledger = balances.findByAccounts(tenantId, chain.values().stream()
                .map(row -> row.getAccountId()).distinct().toList()).stream()
                .collect(Collectors.toMap(row -> new BalanceKey(row.getChain(), row.getAssetSymbol(), row.getAccountId()), row -> row));
        return definitions.stream().map(row -> {
            var address = Optional.ofNullable(custody.get(row.custodyAddressId()))
                    .orElseThrow(() -> new IllegalStateException("gas custody address is missing"));
            var owner = Optional.ofNullable(chain.get(address.chainAddressId()))
                    .orElseThrow(() -> new IllegalStateException("gas chain address is missing"));
            LedgerBalanceRecord balance = ledger.get(new BalanceKey(row.chain(), row.nativeSymbol(), owner.getAccountId()));
            return new GasAccountRecord(row.id(), tenantId, row.custodyAddressId(), row.chain(), row.network(),
                    row.nativeSymbol(), address.address(), address.memo(), owner.getAddressIndex(), owner.getAccountId(),
                    balance == null ? BigDecimal.ZERO : balance.getAvailableBalance(),
                    balance == null ? BigDecimal.ZERO : balance.getLockedBalance(),
                    balance == null ? BigDecimal.ZERO : balance.getTotalBalance(),
                    row.lowBalanceThreshold(), row.status(), row.createdAt(), row.updatedAt());
        }).toList();
    }

    private record BalanceKey(String chain, String symbol, String accountId) { }

    @Transactional(rollbackFor = Throwable.class)
    public GasAccountView create(CustodyPrincipal principal, CreateGasAccountCommand command,
                                 String sourceIp) {
        requireTenantAdmin(principal);
        String chain = requireChain(command.chain());
        BigDecimal threshold = DEFAULT_LOW_BALANCE_THRESHOLD;
        BlockchainRuntimeService.RuntimeChain chainRuntime = runtime.requireRuntime(chain);
        String collectionSubject = collectionSubject(chain, chainRuntime.chainType());
        subjects.resolve(principal.tenantId(), collectionSubject);
        GasAccountRecord existing = findGasAccount(principal.tenantId(), chain)
                .orElse(null);
        if (existing != null) {
            return toView(existing);
        }
        AddressView fundingAddress = addresses.createSystemAtChildIndex(
                principal,
                new CreateAddressCommand(
                        chain,
                        collectionSubject,
                        null,
                        chain + " collection address",
                        Map.of("systemPurpose", "COLLECTION_AND_GAS")),
                COLLECTION_CHILD_INDEX,
                sourceIp);
        UUID id = UUID.randomUUID();
        gasAccounts.insert(
                id,
                principal.tenantId(),
                fundingAddress.id(),
                chain,
                chainRuntime.network(),
                chainRuntime.nativeSymbol(),
                threshold,
                principal.actorId());
        GasAccountRecord saved = requireGasAccount(principal.tenantId(), id);
        audits.insert(
                UUID.randomUUID(),
                principal.tenantId(),
                "TENANT_USER",
                principal.actorId().toString(),
                "GAS_ACCOUNT.CREATE",
                "GAS_ACCOUNT",
                saved.id().toString(),
                sourceIp,
                "{\"chain\":\"" + chain + "\",\"nativeSymbol\":\""
                        + chainRuntime.nativeSymbol() + "\"}");
        return toView(saved);
    }
    public static String collectionSubject(String chain, ChainType chainType) {
        String namespace = chainType.isEvm()
                ? "evm"
                : chain.toLowerCase(Locale.ROOT);
        return SYSTEM_REFERENCE_PREFIX + namespace;
    }

    @Transactional(rollbackFor = Throwable.class)
    public GasAccountView update(CustodyPrincipal principal, UUID gasAccountId,
                                 UpdateGasAccountCommand command, String sourceIp) {
        requireTenantAdmin(principal);
        GasAccountRecord current = requireGasAccount(
                principal.tenantId(), gasAccountId);
        BigDecimal threshold = command.lowBalanceThreshold() == null
                ? current.lowBalanceThreshold()
                : positiveAmount(command.lowBalanceThreshold(), "lowBalanceThreshold");
        String status = command.status() == null
                ? current.status()
                : command.status().trim().toUpperCase(Locale.ROOT);
        if (!"ACTIVE".equals(status) && !"DISABLED".equals(status)) {
            throw new IllegalArgumentException("gas account status must be ACTIVE or DISABLED");
        }
        if (gasAccounts.update(principal.tenantId(), gasAccountId, threshold, status) != 1) {
            throw new IllegalArgumentException("gas account not found");
        }
        GasAccountRecord saved = requireGasAccount(principal.tenantId(), gasAccountId);
        audits.insert(
                UUID.randomUUID(),
                principal.tenantId(),
                "TENANT_USER",
                principal.actorId().toString(),
                "GAS_ACCOUNT.UPDATE",
                "GAS_ACCOUNT",
                gasAccountId.toString(),
                sourceIp,
                "{\"status\":\"" + status + "\",\"lowBalanceThreshold\":\""
                        + threshold.toPlainString() + "\"}");
        return toView(saved);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<Map<String, Object>> topups(CustodyPrincipal principal, UUID gasAccountId,
                                             int limit, int offset) {
        requireScope(principal, "assets:read");
        var account = gasAccounts.findReference(principal.tenantId(), gasAccountId)
                .orElseThrow(() -> new IllegalArgumentException("gas account not found"));
        return deposits.listForAddress(principal.tenantId(), account.custodyAddressId(), account.chain(),
                account.nativeSymbol(), limit, offset);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<CustodyGasUsageEntry> usage(CustodyPrincipal principal, UUID gasAccountId,
                                           int limit, int offset) {
        requireScope(principal, "assets:read");
        gasAccounts.findReference(principal.tenantId(), gasAccountId)
                .orElseThrow(() -> new IllegalArgumentException("gas account not found"));
        return usages.listForAccount(principal.tenantId(), gasAccountId, limit, offset);
    }

    void reserveWithdrawal(UUID tenantId, UUID custodyWithdrawalId,
                           String chain, String assetSymbol) {
        BigDecimal reservation = reservationAmount(chain, assetSymbol);
        accounting.reserve(tenantId, "WITHDRAWAL", custodyWithdrawalId, reservation);
    }
    BigDecimal reservationAmount(String chain, String assetSymbol) {
        GasPricingMetadata metadata =
                pricingMetadata(chain, assetSymbol);
        BigDecimal atomic = BigDecimal.ONE.movePointLeft(metadata.decimals());
        BigDecimal configured = BigDecimal.valueOf(
                        Math.max(1L, metadata.defaultFeeRate()))
                .movePointLeft(metadata.decimals());
        BigDecimal estimate = switch (metadata.family().toLowerCase(Locale.ROOT)) {
            case "evm" -> BigDecimal.valueOf(metadata.requestedNative() ? 21_000L : 65_000L)
                    .multiply(BigDecimal.valueOf(Math.max(1L, metadata.defaultFeeRate())))
                    .movePointLeft(9);
            case "bitcoin-like" -> BigDecimal.valueOf(
                            Math.max(1L, metadata.defaultFeeRate()))
                    .multiply(BigDecimal.valueOf(350L))
                    .movePointLeft(metadata.decimals());
            case "tron" -> metadata.requestedNative()
                    ? new BigDecimal("2")
                    : new BigDecimal("100");
            case "monero" -> configured.max(new BigDecimal("0.0001"));
            default -> configured;
        };
        return estimate.multiply(new BigDecimal("1.25"))
                .max(atomic)
                .setScale(metadata.decimals(), RoundingMode.UP)
                .stripTrailingZeros();
    }
    /** New reservations require an enabled profile and its active native asset. */
    private GasPricingMetadata pricingMetadata(String chain, String symbol) {
        var profile = profiles.findAccountByChain(chain)
                .orElseThrow(() -> new IllegalArgumentException("enabled chain gas pricing is unavailable for " + chain));
        var nativeAsset = assets.findActiveDefinition(chain, profile.getNativeSymbol())
                .orElseThrow(() -> new IllegalArgumentException("native asset is missing"));
        boolean nativeRequested = assets.findActiveDefinition(chain, symbol)
                .map(asset -> Boolean.TRUE.equals(asset.getNativeAsset())).orElse(false);
        return new GasPricingMetadata(profile.getFamily(), profile.getNativeSymbol(),
                profile.getDefaultFee() == null ? 1 : profile.getDefaultFee(), nativeAsset.getDecimals(), nativeRequested);
    }

    private static GasAccountView toView(GasAccountRecord record) {
        return new GasAccountView(
                record.id(),
                record.custodyAddressId(),
                record.chain(),
                record.network(),
                record.nativeSymbol(),
                record.address(),
                record.memo(),
                record.childIndex(),
                record.availableBalance(),
                record.lockedBalance(),
                record.totalBalance(),
                record.lowBalanceThreshold(),
                record.lowBalance(),
                record.status(),
                record.createdAt(),
                record.updatedAt());
    }
    private static BigDecimal positiveAmount(String value, String field) {
        try {
            BigDecimal amount = new BigDecimal(value == null ? "" : value.trim());
            if (amount.signum() <= 0 || amount.scale() > 24 || amount.precision() > 78) {
                throw new NumberFormatException("out of range");
            }
            return amount.stripTrailingZeros();
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    field + " must be a positive decimal with at most 24 fraction digits");
        }
    }
    private static String requireChain(String value) {
        String chain = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!chain.matches("^[A-Z][A-Z0-9_]{1,31}$")) {
            throw new IllegalArgumentException("valid chain is required");
        }
        return chain;
    }
    private static void requireTenantAdmin(CustodyPrincipal principal) {
        if (principal == null || principal.tenantId() == null
                || !"TENANT_ADMIN".equals(principal.role())) {
            throw new CustodyForbiddenException("tenant administrator required");
        }
    }
    private static void requireScope(CustodyPrincipal principal, String scope) {
        if (principal == null || principal.tenantId() == null || !principal.hasScope(scope)) {
            throw new CustodyForbiddenException(scope + " scope required");
        }
    }
    public record CreateGasAccountCommand(String chain) {
    }

    public record UpdateGasAccountCommand(
            String lowBalanceThreshold,
            String status
    ) {
    }

    public record GasAccountView(
            UUID id,
            UUID custodyAddressId,
            String chain,
            String network,
            String nativeSymbol,
            String address,
            String memo,
            long childIndex,
            BigDecimal availableBalance,
            BigDecimal lockedBalance,
            BigDecimal totalBalance,
            BigDecimal lowBalanceThreshold,
            boolean lowBalance,
            String status,
            Instant createdAt,
            Instant updatedAt
    ) {
    }
}
