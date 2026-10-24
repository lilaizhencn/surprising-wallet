package com.surprising.wallet.service;

import com.surprising.wallet.model.PageView;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.chain.model.CustodyAddressRecord;
import com.surprising.wallet.model.custody.TenantRecord;
import com.surprising.wallet.repository.Evm7702AccountRepository;
import com.surprising.wallet.chain.BlockchainRuntimeService;
import com.surprising.wallet.repository.ChainAddressRepository;
import com.surprising.wallet.repository.CustodyAddressRepository;
import com.surprising.wallet.repository.CustodyGasAccountRepository;
import com.surprising.wallet.repository.CustodyDerivationSubjectRepository;
import com.surprising.wallet.repository.CustodyAuditRepository;
import com.surprising.wallet.repository.LedgerBalanceRepository;
import org.springframework.transaction.annotation.Isolation;
import java.util.ArrayList;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.surprising.wallet.exception.CustodyForbiddenException;
import com.surprising.wallet.model.CustodyPrincipal;
import com.surprising.wallet.repository.CustodyTenantTableRepository;

/**
 * 管理租户地址的确定性分配、版本和查询。
 * 同一主题/版本重复请求返回原地址；分配锁、地址归属、EVM 投影和审计共用事务。
 */
@Service
public class CustodyAddressService {
    private static final String RESERVED_SUBJECT_PREFIX = "__sw_";
    static final long DEFAULT_ADDRESS_VERSION = 0L;
    private static final long MAX_ADDRESS_VERSION = Integer.MAX_VALUE;
    private final CustodyTenantTableRepository tenants;
    private final ChainAddressRepository chainRepository;
    private final CustodyAddressRepository addressRepository;
    private final CustodyGasAccountRepository gasAccounts;
    private final CustodyDerivationSubjectRepository subjects;
    private final CustodyAuditRepository audits;
    private final LedgerBalanceRepository balances;
    private final BlockchainRuntimeService runtime;
    private final CustodyTenantChainService tenantChains;
    private final ObjectMapper objectMapper;

    private final Evm7702AccountRepository evmAccounts;

    public CustodyAddressService(CustodyTenantTableRepository tenants, ChainAddressRepository chainRepository,
                                 BlockchainRuntimeService runtime, CustodyTenantChainService tenantChains,
                                 ObjectMapper objectMapper, Evm7702AccountRepository evmAccounts,
                                 CustodyAddressRepository addressRepository, CustodyGasAccountRepository gasAccounts,
                                 CustodyDerivationSubjectRepository subjects, CustodyAuditRepository audits,
                                 LedgerBalanceRepository balances) {
        this.tenants = tenants;
        this.chainRepository = chainRepository;
        this.runtime = runtime;
        this.tenantChains = tenantChains;
        this.objectMapper = objectMapper;
        this.evmAccounts = evmAccounts;
        this.addressRepository = addressRepository;
        this.gasAccounts = gasAccounts;
        this.subjects = subjects;
        this.audits = audits;
        this.balances = balances;
    }

    @Transactional(rollbackFor = Throwable.class)
    public AddressView create(CustodyPrincipal principal, CreateAddressCommand command,
                              String source, String sourceIp) {
        long addressVersion = requireAddressVersion(command.addressVersion());
        return createInternal(
                principal, command, source, sourceIp, false,
                addressVersion, addressVersion);
    }

    @Transactional(rollbackFor = Throwable.class)
    AddressView createSystemAtChildIndex(CustodyPrincipal principal, CreateAddressCommand command,
                                         long childIndex, String sourceIp) {
        return createInternal(
                principal, command, "CONSOLE", sourceIp, true,
                DEFAULT_ADDRESS_VERSION, childIndex);
    }

    private AddressView createInternal(CustodyPrincipal principal, CreateAddressCommand command,
                                       String source, String sourceIp, boolean allowReservedSubject,
                                       long addressVersion, long childIndex) {
        requireScope(principal, "addresses:write");
        String normalizedSource = normalizeSource(source);
        String chain = requireChain(command.chain());
        String subject = requireSubject(command.subject(), allowReservedSubject);
        String label = optional(command.label(), 160, "label");
        String metadataJson = metadataJson(command.metadata());

        TenantRecord tenant = tenants.findById(principal.tenantId())
                .orElseThrow(() -> new IllegalArgumentException("tenant not found"));
        if (!"ACTIVE".equals(tenant.status())) {
            throw new CustodyForbiddenException("tenant is not active");
        }
        tenantChains.requireActive(tenant.id(), chain);

        int derivationSubject = subjects.resolve(tenant.id(), subject);
        CustodyAddressRecord existing = addressRepository.findBySubjectAndVersion(
                tenant.id(), chain, subject, addressVersion).orElse(null);
        if (existing != null) {
            return toView(existing);
        }

        AccountChainProfile profile = runtime.requireProfile(chain);
        ChainAddressRecord chainAddress = runtime.generateDepositAddressAtIndex(
                profile, Integer.toUnsignedLong(derivationSubject), tenant.derivationNamespace(), childIndex);
        if (chainRepository.assignTenant(tenant.id(), chainAddress.getId()) != 1) {
            throw new IllegalStateException("generated chain address tenant assignment failed");
        }

        UUID addressId = UUID.randomUUID();
        int inserted = addressRepository.insert(
                addressId,
                tenant.id(),
                chainAddress.getId(),
                profile.getChain(),
                profile.getNetwork(),
                chainAddress.getAddress(),
                null,
                subject,
                label,
                metadataJson,
                normalizedSource,
                derivationSubject,
                addressVersion,
                chainAddress.getAddressIndex(),
                "CONSOLE".equals(normalizedSource) ? principal.actorId() : null);
        if (inserted != 1) throw new IllegalStateException("failed to create custody address");
        if ("evm".equalsIgnoreCase(profile.getFamily())) {
            evmAccounts.createProjection(
                    tenant.id(), addressId, chain, profile.getNetwork(), chainAddress.getAddress());
        }
        AddressView result = toView(addressRepository.require(tenant.id(), addressId));
        audits.insert(
                UUID.randomUUID(),
                tenant.id(),
                principal.actorType().name(),
                principal.actorId().toString(),
                "ADDRESS.CREATE",
                "CUSTODY_ADDRESS",
                addressId.toString(),
                sourceIp,
                addressAuditDetails(
                        chain, normalizedSource, subject, addressVersion, chainAddress.getAddressIndex()));
        return result;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<AddressView> list(CustodyPrincipal principal, String chain, String source,
                                  String status, String search, int limit, int offset) {
        requireScope(principal, "addresses:read");
        return listForTenant(principal.tenantId(), chain, source, status, search, limit, offset)
                .stream().map(this::toView).toList();
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageView<AddressView> page(CustodyPrincipal principal, String chain, String source,
                                      String status, String search, int limit, int offset) {
        requireScope(principal, "addresses:read");
        var query = addressQuery(principal.tenantId(), chain, source, status, search);
        int pageSize = Math.min(Math.max(limit, 1), 200);
        int pageOffset = Math.max(offset, 0);
        return new PageView<>(addressRepository.query(query, pageSize, pageOffset).stream().map(this::toView).toList(),
                addressRepository.count(query), pageSize, pageOffset);
    }

    // Platform tenant detail checks its actor and target tenant before using this shared query.
    List<CustodyAddressRecord> listForTenant(UUID tenantId, String chain, String source, String status,
                                             String search, int limit, int offset) {
        return addressRepository.query(addressQuery(tenantId, chain, source, status, search), limit, offset);
    }

    private CustodyAddressRepository.Query addressQuery(UUID tenantId, String chain, String source, String status, String search) {
        return new CustodyAddressRepository.Query(tenantId, chain, source, status, search, gasAccounts.listCustodyAddressIds(tenantId));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<Map<String, Object>> fundedAddresses(CustodyPrincipal principal, String chain,
                                                      String assetSymbol, String subject, String requiredAmount) {
        requireScope(principal, "addresses:read");
        String normalizedChain = requiredUpper(chain, "chain", 32);
        String normalizedAsset = requiredUpper(assetSymbol, "assetSymbol", 32);
        String normalizedSubject = requireSubject(subject, false);
        BigDecimal amount;
        try {
            amount = new BigDecimal(requiredAmount == null ? "" : requiredAmount.trim());
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("requiredAmount must be a positive decimal", ex);
        }
        if (amount.signum() <= 0) throw new IllegalArgumentException("requiredAmount must be a positive decimal");
        var candidates = addressRepository.listActiveBySubject(principal.tenantId(), normalizedChain, normalizedSubject);
        var chainAddresses = chainRepository.findByIds(principal.tenantId(), candidates.stream()
                .map(CustodyAddressRecord::chainAddressId).distinct().toList()).stream()
                .collect(Collectors.toMap(ChainAddressRecord::getId, Function.identity()));
        var available = balances.findByAccounts(principal.tenantId(), normalizedChain, normalizedAsset,
                chainAddresses.values().stream().map(ChainAddressRecord::getAccountId).distinct().toList()).stream()
                .collect(Collectors.toMap(com.surprising.wallet.chain.model.LedgerBalanceRecord::getAccountId,
                        com.surprising.wallet.chain.model.LedgerBalanceRecord::getAvailableBalance));
        var result = new ArrayList<Map<String, Object>>();
        for (var address : candidates) {
            var chainAddress = chainAddresses.get(address.chainAddressId());
            if (chainAddress == null) continue;
            var balance = available.get(chainAddress.getAccountId());
            if (balance == null || balance.compareTo(amount) < 0) continue;
            var item = new LinkedHashMap<String, Object>();
            item.put("custodyAddressId", address.id()); item.put("chain", address.chain());
            item.put("network", address.network()); item.put("address", address.address());
            item.put("subject", address.subject()); item.put("addressVersion", address.addressVersion());
            item.put("assetSymbol", normalizedAsset); item.put("availableBalance", balance);
            result.add(item);
        }
        return result;
    }

    @Transactional(rollbackFor = Throwable.class)
    public AddressView update(CustodyPrincipal principal, UUID addressId,
                              UpdateAddressCommand command, String sourceIp) {
        requireScope(principal, "addresses:write");
        if (addressId == null) {
            throw new IllegalArgumentException("addressId is required");
        }
        CustodyAddressRecord current = addressRepository.require(principal.tenantId(), addressId);
        if (gasAccounts.existsForAddress(principal.tenantId(), addressId)) {
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
        if (addressRepository.update(principal.tenantId(), addressId, label, current.memo(), normalizedStatus, metadata) != 1) {
            throw new IllegalArgumentException("custody address not found");
        }
        CustodyAddressRecord saved = addressRepository.require(principal.tenantId(), addressId);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("label", label);
        details.put("status", normalizedStatus);
        audits.insert(
                UUID.randomUUID(),
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
    private AddressView toView(CustodyAddressRecord record) {
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMetadata(String json) {
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (JacksonException e) {
            throw new IllegalStateException("stored address metadata is invalid", e);
        }
    }
    private String metadataJson(Map<String, Object> metadata) {
        Map<String, Object> value = metadata == null ? Map.of() : metadata;
        String json = json(value);
        if (json.length() > 16_384) {
            throw new IllegalArgumentException("metadata must not exceed 16 KiB");
        }
        return json;
    }
    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("value cannot be serialized as JSON", e);
        }
    }

    private String addressAuditDetails(String chain, String source, String subject,
                               long addressVersion, long childIndex) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("chain", chain);
        details.put("source", source);
        details.put("subject", subject);
        details.put("addressVersion", addressVersion);
        details.put("childIndex", childIndex);
        return json(details);
    }
    public static long requireAddressVersion(Long value) {
        long addressVersion = value == null ? DEFAULT_ADDRESS_VERSION : value;
        if (addressVersion < 0 || addressVersion > MAX_ADDRESS_VERSION) {
            throw new IllegalArgumentException(
                    "addressVersion must be between 0 and " + MAX_ADDRESS_VERSION);
        }
        return addressVersion;
    }
    private static String requireChain(String value) {
        String chain = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!chain.matches("^[A-Z][A-Z0-9_]{1,31}$")) {
            throw new IllegalArgumentException("valid chain is required");
        }
        return chain;
    }
    private static String optional(String value, int max, String field) {
        String result = value == null ? "" : value.trim();
        if (result.length() > max) {
            throw new IllegalArgumentException(field + " must not exceed " + max + " characters");
        }
        return result.isBlank() ? null : result;
    }
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
    private static String normalizeSource(String value) {
        String source = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!"API".equals(source) && !"CONSOLE".equals(source)) {
            throw new IllegalArgumentException("address source must be API or CONSOLE");
        }
        return source;
    }
    /**
     * 转换可选筛选值，统一大小写并将空值转换为无筛选语义。
     */
    private static String upperOrNull(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        return normalized.isBlank() ? null : normalized;
    }

    private static String requiredUpper(String value, String field, int maxLength) {
        String normalized = upperOrNull(value);
        if (normalized == null || normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + " is required and must be at most " + maxLength + " characters");
        }
        return normalized;
    }
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
