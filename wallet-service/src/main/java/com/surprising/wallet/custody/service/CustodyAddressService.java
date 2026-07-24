package com.surprising.wallet.custody.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.pojo.Address;
import com.surprising.wallet.custody.repository.CustodyRepository.AddressRecord;
import com.surprising.wallet.custody.repository.CustodyRepository.TenantRecord;
import com.surprising.wallet.account.repository.Evm7702CollectionRepository;
import com.surprising.wallet.service.chain.BlockchainRuntimeService;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.surprising.wallet.custody.exception.CustodyForbiddenException;
import com.surprising.wallet.custody.model.CustodyPrincipal;
import com.surprising.wallet.custody.repository.CustodyRepository;

@Service
public class CustodyAddressService {
    private static final String RESERVED_SUBJECT_PREFIX = "__sw_";    static final long DEFAULT_ADDRESS_VERSION = 0L;    private static final long MAX_ADDRESS_VERSION = Integer.MAX_VALUE;    private final CustodyRepository custodyRepository;    private final ChainJdbcRepository chainRepository;    private final BlockchainRuntimeService runtime;    private final CustodyTenantChainService tenantChains;    private final ObjectMapper objectMapper;

    @Autowired(required = false)
    private Evm7702CollectionRepository evm7702Repository;

    public CustodyAddressService(CustodyRepository custodyRepository,
                                 ChainJdbcRepository chainRepository,
                                 BlockchainRuntimeService runtime,
                                 CustodyTenantChainService tenantChains,
                                 ObjectMapper objectMapper) {
        this.custodyRepository = custodyRepository;
        this.chainRepository = chainRepository;
        this.runtime = runtime;
        this.tenantChains = tenantChains;
        this.objectMapper = objectMapper;
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
            if (evm7702Repository != null) {
                evm7702Repository.createAccountProjection(
                        tenant.id(), addressId, chain, runtimeChain.network(), chainAddress.getAddress());
            }
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
    public List<Map<String, Object>> assets(CustodyPrincipal principal) {
        requireScope(principal, "assets:read");
        return custodyRepository.tenantAssetOverview(principal.tenantId());
    }
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMetadata(String json) {
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (JsonProcessingException e) {
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
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("value cannot be serialized as JSON", e);
        }
    }

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
    private static String upperOrEmpty(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
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
