package com.surprising.wallet.jobs.custody;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.pojo.Address;
import com.surprising.wallet.jobs.custody.CustodyRepository.AddressRecord;
import com.surprising.wallet.jobs.custody.CustodyRepository.IdempotencyRecord;
import com.surprising.wallet.jobs.custody.CustodyRepository.TenantRecord;
import com.surprising.wallet.service.chain.BlockchainRuntimeService;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class CustodyAddressService {
    private static final String CREATE_OPERATION = "ADDRESS.CREATE";
    private static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);

    private final CustodyRepository custodyRepository;
    private final ChainJdbcRepository chainRepository;
    private final BlockchainRuntimeService runtime;
    private final CustodyCryptoService crypto;
    private final ObjectMapper objectMapper;

    public CustodyAddressService(CustodyRepository custodyRepository,
                                 ChainJdbcRepository chainRepository,
                                 BlockchainRuntimeService runtime,
                                 CustodyCryptoService crypto,
                                 ObjectMapper objectMapper) {
        this.custodyRepository = custodyRepository;
        this.chainRepository = chainRepository;
        this.runtime = runtime;
        this.crypto = crypto;
        this.objectMapper = objectMapper;
    }

    @Transactional(rollbackFor = Throwable.class)
    public AddressView create(CustodyPrincipal principal, CreateAddressCommand command,
                              String source, String idempotencyKey, String sourceIp) {
        requireScope(principal, "addresses:write");
        String normalizedSource = normalizeSource(source);
        String chain = requireChain(command.chain());
        String externalReference = optional(command.externalReference(), 160, "externalReference");
        String label = optional(command.label(), 160, "label");
        String metadataJson = metadataJson(command.metadata());
        String requestHash = crypto.sha256(chain + "\n" + externalReference + "\n"
                + label + "\n" + metadataJson + "\n" + normalizedSource);
        String normalizedIdempotencyKey = null;

        if ("API".equals(normalizedSource)) {
            normalizedIdempotencyKey = requireIdempotencyKey(idempotencyKey);
            AddressView replay = replay(
                    principal.tenantId(), normalizedIdempotencyKey, requestHash);
            if (replay != null) {
                return replay;
            }
            if (!custodyRepository.beginIdempotency(
                    principal.tenantId(), normalizedIdempotencyKey, CREATE_OPERATION, requestHash,
                    Instant.now().plus(IDEMPOTENCY_TTL))) {
                replay = replay(principal.tenantId(), normalizedIdempotencyKey, requestHash);
                if (replay != null) {
                    return replay;
                }
                throw new IllegalStateException("an address request with this idempotency key is still processing");
            }
        }

        TenantRecord tenant = custodyRepository.requireTenant(principal.tenantId());
        if (!"ACTIVE".equals(tenant.status())) {
            throw new CustodyForbiddenException("tenant is not active");
        }

        if (externalReference != null) {
            custodyRepository.lockAddressAllocation(tenant.id(), chain, externalReference);
            AddressRecord existing = custodyRepository.findAddressByExternalReference(
                    tenant.id(), chain, externalReference).orElse(null);
            if (existing != null) {
                AddressView result = toView(existing);
                if ("API".equals(normalizedSource)) {
                    custodyRepository.completeIdempotency(
                            tenant.id(), normalizedIdempotencyKey,
                            CREATE_OPERATION, 201, json(result));
                }
                return result;
            }
        }

        BlockchainRuntimeService.RuntimeChain runtimeChain = runtime.requireRuntime(chain);
        int subject = custodyRepository.nextDerivationSubject();
        Address generated = runtime.generateDepositAddress(
                chain, Integer.toUnsignedLong(subject), tenant.derivationNamespace());
        ChainAddressRecord chainAddress = chainRepository.findChainAddress(
                        chain, runtimeChain.nativeSymbol(), Integer.toUnsignedLong(subject),
                        tenant.derivationNamespace(), generated.getIndex(), "DEPOSIT")
                .orElseThrow(() -> new IllegalStateException("generated chain address was not persisted"));

        UUID addressId = UUID.randomUUID();
        AddressRecord saved = custodyRepository.insertAddress(
                addressId,
                tenant.id(),
                chainAddress.getId(),
                runtimeChain.chain(),
                runtimeChain.network(),
                chainAddress.getAddress(),
                null,
                externalReference,
                label,
                metadataJson,
                normalizedSource,
                subject,
                "CONSOLE".equals(normalizedSource) ? principal.actorId() : null);
        AddressView result = toView(saved);
        custodyRepository.audit(
                tenant.id(),
                principal.actorType().name(),
                principal.actorId().toString(),
                "ADDRESS.CREATE",
                "CUSTODY_ADDRESS",
                addressId.toString(),
                sourceIp,
                addressAuditDetails(chain, normalizedSource, externalReference));

        if ("API".equals(normalizedSource)) {
            custodyRepository.completeIdempotency(
                    tenant.id(), normalizedIdempotencyKey, CREATE_OPERATION, 201, json(result));
        }
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
                    "manage gas reserve addresses from Gas station");
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

    private AddressView replay(UUID tenantId, String key, String requestHash) {
        IdempotencyRecord existing = custodyRepository.findIdempotency(tenantId, key, CREATE_OPERATION)
                .orElse(null);
        if (existing == null) {
            return null;
        }
        if (!crypto.constantTimeEquals(existing.requestHash(), requestHash)) {
            throw new IllegalStateException("idempotency key was already used with a different request");
        }
        if (existing.responseJson() == null) {
            return null;
        }
        try {
            return objectMapper.readValue(existing.responseJson(), AddressView.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored idempotent response is invalid", e);
        }
    }

    private AddressView toView(AddressRecord record) {
        return new AddressView(
                record.id(),
                record.chain(),
                record.network(),
                record.address(),
                record.memo(),
                record.externalReference(),
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

    String addressAuditDetails(String chain, String source, String externalReference) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("chain", chain);
        details.put("source", source);
        details.put("externalReference", externalReference);
        return json(details);
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

    private static String normalizeSource(String value) {
        String source = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!"API".equals(source) && !"CONSOLE".equals(source)) {
            throw new IllegalArgumentException("address source must be API or CONSOLE");
        }
        return source;
    }

    private static String requireIdempotencyKey(String value) {
        String key = value == null ? "" : value.trim();
        if (!key.matches("^[A-Za-z0-9._:-]{8,160}$")) {
            throw new IllegalArgumentException(
                    "Idempotency-Key must contain 8-160 letters, digits, dots, underscores, colons or hyphens");
        }
        return key;
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
            String externalReference,
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
            String externalReference,
            String label,
            Map<String, Object> metadata,
            String source,
            String status,
            Instant createdAt
    ) {
    }
}
