package com.surprising.wallet.service;

import com.surprising.wallet.custody.model.PageView;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.pojo.Address;
import com.surprising.wallet.repository.CustodyRepository.AddressRecord;
import com.surprising.wallet.repository.CustodyRepository.TenantRecord;
import com.surprising.wallet.repository.Evm7702CollectionRepository;
import com.surprising.wallet.chain.BlockchainRuntimeService;
import com.surprising.wallet.repository.ChainJdbcRepository;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.surprising.wallet.custody.exception.CustodyForbiddenException;
import com.surprising.wallet.custody.model.CustodyPrincipal;
import com.surprising.wallet.repository.CustodyRepository;

/**
 * 托管充值地址服务，管理租户的充值地址生命周期。
 *
 * <p>核心功能：为租户创建充值地址（链上 BIP44 派生 + EIP-7702 委托）、
 * 查询地址列表、校验地址归属权限。地址创建时生成确定性 BIP32 路径，
 * 同一 externalReference 多次请求返回相同地址。
 */
@Service
public class CustodyAddressService {
    /**
     * 定义 {@code RESERVED_SUBJECT_PREFIX} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    private static final String RESERVED_SUBJECT_PREFIX = "__sw_";
    /**
     * 定义 {@code DEFAULT_ADDRESS_VERSION} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    static final long DEFAULT_ADDRESS_VERSION = 0L;
    /**
     * 定义 {@code MAX_ADDRESS_VERSION} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    private static final long MAX_ADDRESS_VERSION = Integer.MAX_VALUE;
    /**
     * 保存 {@code custodyRepository}，用于访问当前业务所依赖的仓储、客户端或服务。
     */
    private final CustodyRepository custodyRepository;
    /**
     * 保存 {@code chainRepository}，用于访问当前业务所依赖的仓储、客户端或服务。
     */
    private final ChainJdbcRepository chainRepository;
    /**
     * 保存 {@code runtime}，用于记录时间边界或审计时间。
     */
    private final BlockchainRuntimeService runtime;
    /**
     * 保存 {@code tenantChains}，表示链、网络、资产或代币配置。
     */
    private final CustodyTenantChainService tenantChains;
    /**
     * 保存 {@code objectMapper}，用于保存业务集合或索引状态。
     */
    private final ObjectMapper objectMapper;

    /**
     * 保存 {@code evm7702Repository}，用于访问当前业务所依赖的仓储、客户端或服务。
     */
    private final Optional<Evm7702CollectionRepository> evm7702Repository;

    /**
     * 构造 {@code CustodyAddressService}，初始化该组件运行所需的状态和依赖。
     */
    @Autowired
    public CustodyAddressService(CustodyRepository custodyRepository,
                                 ChainJdbcRepository chainRepository,
                                 BlockchainRuntimeService runtime,
                                 CustodyTenantChainService tenantChains,
                                 ObjectMapper objectMapper,
                                 Optional<Evm7702CollectionRepository> evm7702Repository) {
        this.custodyRepository = custodyRepository;
        this.chainRepository = chainRepository;
        this.runtime = runtime;
        this.tenantChains = tenantChains;
        this.objectMapper = objectMapper;
        this.evm7702Repository = evm7702Repository;
    }

    /** 提供不启用 EIP-7702 仓储时的测试构造函数。 */
    public CustodyAddressService(CustodyRepository custodyRepository,
                                 ChainJdbcRepository chainRepository,
                                 BlockchainRuntimeService runtime,
                                 CustodyTenantChainService tenantChains,
                                 ObjectMapper objectMapper) {
        this(custodyRepository, chainRepository, runtime, tenantChains, objectMapper, Optional.empty());
    }

    /**
     * 构建或生成 {@code create} 对应的结果，并执行输入和状态校验。
     */
    @Transactional(rollbackFor = Throwable.class)
    public AddressView create(CustodyPrincipal principal, CreateAddressCommand command,
                              String source, String sourceIp) {
        long addressVersion = requireAddressVersion(command.addressVersion());
        return createInternal(
                principal, command, source, sourceIp, false,
                addressVersion, addressVersion);
    }

    /**
     * 构建或生成 {@code createSystemAtChildIndex} 对应的结果，并执行输入和状态校验。
     */
    @Transactional(rollbackFor = Throwable.class)
    AddressView createSystemAtChildIndex(CustodyPrincipal principal, CreateAddressCommand command,
                                         long childIndex, String sourceIp) {
        return createInternal(
                principal, command, "CONSOLE", sourceIp, true,
                DEFAULT_ADDRESS_VERSION, childIndex);
    }

    /**
     * 构建或生成 {@code createInternal} 对应的结果，并执行输入和状态校验。
     */
    private AddressView createInternal(CustodyPrincipal principal, CreateAddressCommand command,
                                       String source, String sourceIp, boolean allowReservedSubject,
                                       long addressVersion, long childIndex) {
        requireScope(principal, "addresses:write");
        String normalizedSource = normalizeSource(source);
        String chain = requireChain(command.chain());
        String subject = requireSubject(command.subject(), allowReservedSubject);
        String label = optional(command.label(), 160, "label");
        String metadataJson = metadataJson(command.metadata());

        TenantRecord tenant = custodyRepository.requireTenant(principal.tenantId());
        if (!"ACTIVE".equals(tenant.status())) {
            throw new CustodyForbiddenException("tenant is not active");
        }
        tenantChains.requireActive(tenant.id(), chain);

        custodyRepository.lockSubjectAddressAllocation(tenant.id(), chain, subject);
        AddressRecord existing = custodyRepository.findAddressBySubjectAndVersion(
                tenant.id(), chain, subject, addressVersion).orElse(null);
        if (existing != null) {
            return toView(existing);
        }

        BlockchainRuntimeService.RuntimeChain runtimeChain = runtime.requireRuntime(chain);
        int derivationSubject = custodyRepository.resolveDerivationSubject(tenant.id(), subject);
        Address generated = runtime.generateDepositAddressAtIndex(
                chain, Integer.toUnsignedLong(derivationSubject), tenant.derivationNamespace(),
                childIndex);
        ChainAddressRecord chainAddress = chainRepository.findChainAddress(
                        chain, runtimeChain.nativeSymbol(), Integer.toUnsignedLong(derivationSubject),
                        tenant.derivationNamespace(), generated.getIndex(), "DEPOSIT")
                .orElseThrow(() -> new IllegalStateException("generated chain address was not persisted"));
        custodyRepository.assignChainAddressTenant(tenant.id(), chainAddress.getId());

        UUID addressId = UUID.randomUUID();
        AddressRecord saved = custodyRepository.insertAddress(
                addressId,
                tenant.id(),
                chainAddress.getId(),
                runtimeChain.chain(),
                runtimeChain.network(),
                chainAddress.getAddress(),
                null,
                subject,
                label,
                metadataJson,
                normalizedSource,
                derivationSubject,
                addressVersion,
                generated.getIndex(),
                "CONSOLE".equals(normalizedSource) ? principal.actorId() : null);
        if ("evm".equalsIgnoreCase(runtimeChain.family())) {
            evm7702Repository.ifPresent(repository -> repository.createAccountProjection(
                    tenant.id(), addressId, chain, runtimeChain.network(), chainAddress.getAddress()));
        }
        AddressView result = toView(saved);
        custodyRepository.audit(
                tenant.id(),
                principal.actorType().name(),
                principal.actorId().toString(),
                "ADDRESS.CREATE",
                "CUSTODY_ADDRESS",
                addressId.toString(),
                sourceIp,
                addressAuditDetails(
                        chain, normalizedSource, subject, addressVersion, generated.getIndex()));
        return result;
    }

    /**
     * 获取或查询 {@code list} 对应的数据，供调用方读取当前状态。
     */
    public List<AddressView> list(CustodyPrincipal principal, String chain, String source,
                                  String status, String search, int limit, int offset) {
        requireScope(principal, "addresses:read");
        return custodyRepository.listAddresses(
                        principal.tenantId(),
                        upperOrEmpty(chain),
                        upperOrEmpty(source),
                        upperOrEmpty(status),
                        search,
                        limit,
                        offset)
                .stream()
                .map(this::toView)
                .toList();
    }

    /**
     * 执行 {@code page} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public PageView<AddressView> page(CustodyPrincipal principal, String chain, String source,
                                      String status, String search, int limit, int offset) {
        requireScope(principal, "addresses:read");
        int pageSize = Math.min(Math.max(limit, 1), 200);
        int pageOffset = Math.max(offset, 0);
        String normalizedChain = upperOrEmpty(chain);
        String normalizedSource = upperOrEmpty(source);
        String normalizedStatus = upperOrEmpty(status);
        return new PageView<>(
                custodyRepository.listAddresses(
                                principal.tenantId(), normalizedChain, normalizedSource,
                                normalizedStatus, search, pageSize, pageOffset)
                        .stream()
                        .map(this::toView)
                        .toList(),
                custodyRepository.countAddresses(
                        principal.tenantId(), normalizedChain, normalizedSource,
                        normalizedStatus, search),
                pageSize, pageOffset);
    }

    /**
     * 设置或更新 {@code update} 对应的状态，并保持相关业务字段一致。
     */
    @Transactional(rollbackFor = Throwable.class)
    public AddressView update(CustodyPrincipal principal, UUID addressId,
                              UpdateAddressCommand command, String sourceIp) {
        requireScope(principal, "addresses:write");
        if (addressId == null) {
            throw new IllegalArgumentException("addressId is required");
        }
        AddressRecord current = custodyRepository.requireAddress(principal.tenantId(), addressId);
        if (custodyRepository.isGasAddress(principal.tenantId(), addressId)) {
            throw new IllegalArgumentException(
                    "collection addresses are managed from the asset overview");
        }
        String status = optional(command.status(), 24, "status");
        String normalizedStatus = status == null
                ? current.status()
                : status.toUpperCase(Locale.ROOT);
        if (!"ACTIVE".equals(normalizedStatus) && !"DISABLED".equals(normalizedStatus)) {
            throw new IllegalArgumentException("address status must be ACTIVE or DISABLED");
        }
        String label = command.label() == null
                ? current.label()
                : optional(command.label(), 160, "label");
        String metadata = command.metadata() == null
                ? current.metadataJson()
                : metadataJson(command.metadata());
        AddressRecord saved = custodyRepository.updateAddress(
                principal.tenantId(), addressId, label, metadata, normalizedStatus);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("label", label);
        details.put("status", normalizedStatus);
        custodyRepository.audit(
                principal.tenantId(),
                principal.actorType().name(),
                principal.actorId().toString(),
                "ADDRESS.UPDATE",
                "CUSTODY_ADDRESS",
                addressId.toString(),
                sourceIp,
                json(details));
        return toView(saved);
    }
    /**
     * 获取或查询 {@code assets} 对应的数据，并向调用方返回当前业务状态。
     */
    public List<Map<String, Object>> assets(CustodyPrincipal principal) {
        requireScope(principal, "assets:read");
        return custodyRepository.tenantAssetOverview(principal.tenantId());
    }
    /**
     * 编码 {@code toView} 对应的数据，生成链上或接口所需的表示。
     */
    private AddressView toView(AddressRecord record) {
        return new AddressView(
                record.id(),
                record.chain(),
                record.network(),
                record.address(),
                record.memo(),
                record.subject(),
                record.addressVersion(),
                record.label(),
                readMetadata(record.metadataJson()),
                record.source(),
                record.status(),
                record.createdAt());
    }

    /**
     * 获取或查询 {@code readMetadata} 对应的数据，供调用方读取当前状态。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> readMetadata(String json) {
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (JacksonException e) {
            throw new IllegalStateException("stored address metadata is invalid", e);
        }
    }
    /**
     * 执行 {@code metadataJson} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private String metadataJson(Map<String, Object> metadata) {
        Map<String, Object> value = metadata == null ? Map.of() : metadata;
        String json = json(value);
        if (json.length() > 16_384) {
            throw new IllegalArgumentException("metadata must not exceed 16 KiB");
        }
        return json;
    }
    /**
     * 编码 {@code json} 对应的数据，生成链上或接口所需的表示。
     */
    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("value cannot be serialized as JSON", e);
        }
    }

    /**
     * 添加 {@code addressAuditDetails} 对应的业务对象，并更新当前组件的集合或索引。
     */
    public String addressAuditDetails(String chain, String source, String subject,
                               long addressVersion, long childIndex) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("chain", chain);
        details.put("source", source);
        details.put("subject", subject);
        details.put("addressVersion", addressVersion);
        details.put("childIndex", childIndex);
        return json(details);
    }
    /**
     * 校验 {@code requireAddressVersion} 对应的前置条件，不满足时抛出明确异常。
     */
    public static long requireAddressVersion(Long value) {
        long addressVersion = value == null ? DEFAULT_ADDRESS_VERSION : value;
        if (addressVersion < 0 || addressVersion > MAX_ADDRESS_VERSION) {
            throw new IllegalArgumentException(
                    "addressVersion must be between 0 and " + MAX_ADDRESS_VERSION);
        }
        return addressVersion;
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
     * 执行 {@code optional} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private static String optional(String value, int max, String field) {
        String result = value == null ? "" : value.trim();
        if (result.length() > max) {
            throw new IllegalArgumentException(field + " must not exceed " + max + " characters");
        }
        return result.isBlank() ? null : result;
    }
    /**
     * 校验 {@code requireSubject} 对应的前置条件，不满足时抛出明确异常。
     */
    public static String requireSubject(String value, boolean allowReserved) {
        String subject = value == null ? "" : value.trim();
        if (!subject.matches("^[A-Za-z0-9_][A-Za-z0-9._:-]{0,159}$")) {
            throw new IllegalArgumentException(
                    "subject must contain 1-160 letters, digits, dots, underscores, colons or hyphens");
        }
        if (!allowReserved && subject.toLowerCase(Locale.ROOT).startsWith(RESERVED_SUBJECT_PREFIX)) {
            throw new IllegalArgumentException("subject prefix __sw_ is reserved");
        }
        return subject;
    }
    /**
     * 转换或计算 {@code normalizeSource} 对应的值，统一金额、格式和边界规则。
     */
    private static String normalizeSource(String value) {
        String source = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!"API".equals(source) && !"CONSOLE".equals(source)) {
            throw new IllegalArgumentException("address source must be API or CONSOLE");
        }
        return source;
    }
    /**
     * 转换或计算 {@code upperOrEmpty} 对应的值，统一金额、格式和边界规则。
     */
    private static String upperOrEmpty(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
    /**
     * 校验 {@code requireScope} 对应的前置条件，不满足时抛出明确异常。
     */
    private static void requireScope(CustodyPrincipal principal, String scope) {
        if (principal == null || principal.tenantId() == null || !principal.hasScope(scope)) {
            throw new CustodyForbiddenException(scope + " scope required");
        }
    }

    public record CreateAddressCommand(
            String chain,
            String subject,
            Long addressVersion,
            String label,
            Map<String, Object> metadata
    ) {
    }

    public record UpdateAddressCommand(
            String label,
            Map<String, Object> metadata,
            String status
    ) {
    }

    public record AddressView(
            UUID id,
            String chain,
            String network,
            String address,
            String memo,
            String subject,
            long addressVersion,
            String label,
            Map<String, Object> metadata,
            String source,
            String status,
            Instant createdAt
    ) {
    }
}
