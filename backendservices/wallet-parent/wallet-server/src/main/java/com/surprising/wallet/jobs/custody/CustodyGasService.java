package com.surprising.wallet.jobs.custody;

import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.jobs.custody.CustodyAddressService.AddressView;
import com.surprising.wallet.jobs.custody.CustodyAddressService.CreateAddressCommand;
import com.surprising.wallet.jobs.custody.CustodyRepository.GasAccountRecord;
import com.surprising.wallet.service.chain.BlockchainRuntimeService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class CustodyGasService {
    static final long COLLECTION_CHILD_INDEX = 1L;
    private static final BigDecimal DEFAULT_LOW_BALANCE_THRESHOLD = new BigDecimal("0.01");
    private static final String SYSTEM_REFERENCE_PREFIX = "__sw_collection__:";

    private final CustodyRepository repository;
    private final CustodyAddressService addresses;
    private final BlockchainRuntimeService runtime;

    public CustodyGasService(CustodyRepository repository,
                             CustodyAddressService addresses,
                             BlockchainRuntimeService runtime) {
        this.repository = repository;
        this.addresses = addresses;
        this.runtime = runtime;
    }

    public List<GasAccountView> list(CustodyPrincipal principal) {
        requireScope(principal, "assets:read");
        return repository.listGasAccounts(principal.tenantId())
                .stream().map(CustodyGasService::toView).toList();
    }

    @Transactional(rollbackFor = Throwable.class)
    public GasAccountView create(CustodyPrincipal principal, CreateGasAccountCommand command,
                                 String sourceIp) {
        requireTenantAdmin(principal);
        String chain = requireChain(command.chain());
        BigDecimal threshold = DEFAULT_LOW_BALANCE_THRESHOLD;
        BlockchainRuntimeService.RuntimeChain chainRuntime = runtime.requireRuntime(chain);
        String collectionSubject = collectionSubject(chain, chainRuntime.chainType());
        repository.lockSubjectAddressAllocation(
                principal.tenantId(), chain, collectionSubject);
        GasAccountRecord existing = repository.findGasAccount(principal.tenantId(), chain)
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
        GasAccountRecord saved = repository.insertGasAccount(
                UUID.randomUUID(),
                principal.tenantId(),
                fundingAddress.id(),
                chain,
                chainRuntime.network(),
                chainRuntime.nativeSymbol(),
                threshold,
                principal.actorId());
        repository.audit(
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

    static String collectionSubject(String chain, ChainType chainType) {
        String namespace = chainType.isEvm()
                ? "evm"
                : chain.toLowerCase(Locale.ROOT);
        return SYSTEM_REFERENCE_PREFIX + namespace;
    }

    @Transactional(rollbackFor = Throwable.class)
    public GasAccountView update(CustodyPrincipal principal, UUID gasAccountId,
                                 UpdateGasAccountCommand command, String sourceIp) {
        requireTenantAdmin(principal);
        GasAccountRecord current = repository.requireGasAccount(
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
        GasAccountRecord saved = repository.updateGasAccount(
                principal.tenantId(), gasAccountId, threshold, status);
        repository.audit(
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

    public List<Map<String, Object>> topups(CustodyPrincipal principal, UUID gasAccountId,
                                             int limit, int offset) {
        requireScope(principal, "assets:read");
        repository.requireGasAccount(principal.tenantId(), gasAccountId);
        return repository.listGasTopups(principal.tenantId(), gasAccountId, limit, offset);
    }

    public List<Map<String, Object>> usage(CustodyPrincipal principal, UUID gasAccountId,
                                           int limit, int offset) {
        requireScope(principal, "assets:read");
        repository.requireGasAccount(principal.tenantId(), gasAccountId);
        return repository.listGasUsage(principal.tenantId(), gasAccountId, limit, offset);
    }

    void reserveWithdrawal(UUID tenantId, UUID custodyWithdrawalId, String orderNo,
                           String chain, String assetSymbol) {
        BigDecimal reservation = reservationAmount(chain, assetSymbol);
        repository.reserveGasUsage(
                tenantId, custodyWithdrawalId, orderNo, chain, reservation);
    }

    BigDecimal reservationAmount(String chain, String assetSymbol) {
        CustodyRepository.GasPricingMetadata metadata =
                repository.gasPricingMetadata(chain, assetSymbol);
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

    public Map<String, Object> onboarding(CustodyPrincipal principal) {
        requireScope(principal, "assets:read");
        return repository.onboardingStatus(principal.tenantId());
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
