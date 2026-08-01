package com.surprising.wallet.custody.service;

import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.custody.service.CustodyAddressService.AddressView;
import com.surprising.wallet.custody.service.CustodyAddressService.CreateAddressCommand;
import com.surprising.wallet.custody.repository.CustodyRepository.GasAccountRecord;
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

import com.surprising.wallet.custody.exception.CustodyForbiddenException;
import com.surprising.wallet.custody.model.CustodyPrincipal;
import com.surprising.wallet.custody.repository.CustodyRepository;

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
    /**
     * 定义 {@code DEFAULT_LOW_BALANCE_THRESHOLD} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    private static final BigDecimal DEFAULT_LOW_BALANCE_THRESHOLD = new BigDecimal("0.01");
    /**
     * 定义 {@code SYSTEM_REFERENCE_PREFIX} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    private static final String SYSTEM_REFERENCE_PREFIX = "__sw_collection__:";
    /**
     * 保存 {@code repository}，用于访问当前业务所依赖的仓储、客户端或服务。
     */
    private final CustodyRepository repository;
    /**
     * 保存 {@code addresses}，表示链、网络、资产或代币配置。
     */
    private final CustodyAddressService addresses;
    /**
     * 保存 {@code runtime}，用于记录时间边界或审计时间。
     */
    private final BlockchainRuntimeService runtime;

    /**
     * 构造 {@code CustodyGasService}，初始化该组件运行所需的状态和依赖。
     */
    public CustodyGasService(CustodyRepository repository,
                             CustodyAddressService addresses,
                             BlockchainRuntimeService runtime) {
        this.repository = repository;
        this.addresses = addresses;
        this.runtime = runtime;
    }
    /**
     * 获取或查询 {@code list} 对应的数据，供调用方读取当前状态。
     */
    public List<GasAccountView> list(CustodyPrincipal principal) {
        requireScope(principal, "assets:read");
        return repository.listGasAccounts(principal.tenantId())
                .stream().map(CustodyGasService::toView).toList();
    }

    /**
     * 构建或生成 {@code create} 对应的结果，并执行输入和状态校验。
     */
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
    /**
     * 处理 {@code collectionSubject} 对应的链上或钱包业务流程，并维护状态、幂等和错误边界。
     */
    public static String collectionSubject(String chain, ChainType chainType) {
        String namespace = chainType.isEvm()
                ? "evm"
                : chain.toLowerCase(Locale.ROOT);
        return SYSTEM_REFERENCE_PREFIX + namespace;
    }

    /**
     * 设置或更新 {@code update} 对应的状态，并保持相关业务字段一致。
     */
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

    /**
     * 编码 {@code topups} 对应的数据，生成链上或接口所需的表示。
     */
    public List<Map<String, Object>> topups(CustodyPrincipal principal, UUID gasAccountId,
                                             int limit, int offset) {
        requireScope(principal, "assets:read");
        repository.requireGasAccount(principal.tenantId(), gasAccountId);
        return repository.listGasTopups(principal.tenantId(), gasAccountId, limit, offset);
    }

    /**
     * 执行 {@code usage} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public List<Map<String, Object>> usage(CustodyPrincipal principal, UUID gasAccountId,
                                           int limit, int offset) {
        requireScope(principal, "assets:read");
        repository.requireGasAccount(principal.tenantId(), gasAccountId);
        return repository.listGasUsage(principal.tenantId(), gasAccountId, limit, offset);
    }

    /**
     * 执行 {@code reserveWithdrawal} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    void reserveWithdrawal(UUID tenantId, UUID custodyWithdrawalId, String orderNo,
                           String chain, String assetSymbol) {
        BigDecimal reservation = reservationAmount(chain, assetSymbol);
        repository.reserveGasUsage(
                tenantId, custodyWithdrawalId, orderNo, chain, reservation);
    }
    /**
     * 执行 {@code reservationAmount} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
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
    /**
     * 执行 {@code onboarding} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public Map<String, Object> onboarding(CustodyPrincipal principal) {
        requireScope(principal, "assets:read");
        return repository.onboardingStatus(principal.tenantId());
    }
    /**
     * 编码 {@code toView} 对应的数据，生成链上或接口所需的表示。
     */
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
    /**
     * 执行 {@code positiveAmount} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
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
    /**
     * 校验 {@code requireChain} 对应的前置条件，不满足时抛出明确异常。
     */
    private static String requireChain(String value) {
        String chain = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!chain.matches("^[A-Z][A-Z0-9_]{1,31}$")) {
            throw new IllegalArgumentException("valid chain is required");
        }
        return chain;
    }
    /**
     * 校验 {@code requireTenantAdmin} 对应的前置条件，不满足时抛出明确异常。
     */
    private static void requireTenantAdmin(CustodyPrincipal principal) {
        if (principal == null || principal.tenantId() == null
                || !"TENANT_ADMIN".equals(principal.role())) {
            throw new CustodyForbiddenException("tenant administrator required");
        }
    }
    /**
     * 校验 {@code requireScope} 对应的前置条件，不满足时抛出明确异常。
     */
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
