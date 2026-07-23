package com.surprising.wallet.custody.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.surprising.wallet.custody.repository.CustodyRepository.AddressRecord;
import com.surprising.wallet.custody.repository.CustodyRepository.IdempotencyRecord;
import com.surprising.wallet.custody.repository.CustodyRepository.TenantRecord;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
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
public class CustodyWithdrawalService {
    private static final String CREATE_OPERATION = "WITHDRAWAL.CREATE";

    private final CustodyRepository repository;
    private final CustodyWithdrawalExecutionService executionService;
    private final CustodyGasService gasService;
    private final CustodyTenantChainService tenantChains;
    private final CustodyCryptoService crypto;
    private final ObjectMapper objectMapper;

    public CustodyWithdrawalService(CustodyRepository repository,
                                    CustodyWithdrawalExecutionService executionService,
                                    CustodyGasService gasService,
                                    CustodyTenantChainService tenantChains,
                                    CustodyCryptoService crypto,
                                    ObjectMapper objectMapper) {
        this.repository = repository;
        this.executionService = executionService;
        this.gasService = gasService;
        this.tenantChains = tenantChains;
        this.crypto = crypto;
        this.objectMapper = objectMapper;
    }

    @Transactional(rollbackFor = Throwable.class)
    public WithdrawalView create(CustodyPrincipal principal, CreateWithdrawalCommand command,
                                 String source, String idempotencyKey, String sourceIp) {
        requireScope(principal, "withdrawals:write");
        String normalizedSource = normalizeSource(source);
        if (!Boolean.TRUE.equals(command.confirmed())) {
            throw new IllegalArgumentException(
                    "withdrawal requires explicit confirmation");
        }
        if (command.custodyAddressId() == null) {
            throw new IllegalArgumentException("custodyAddressId is required");
        }
        AddressRecord address = repository.requireAddress(
                principal.tenantId(), command.custodyAddressId());
        if (repository.isGasAddress(principal.tenantId(), command.custodyAddressId())) {
            throw new IllegalArgumentException(
                    "gas reserve addresses cannot be used as customer withdrawal sources");
        }
        String chain = requireUpper(command.chain(), "chain", 32);
        String symbol = requireUpper(command.assetSymbol(), "assetSymbol", 32);
        if (!address.chain().equals(chain)) {
            throw new IllegalArgumentException("custody address belongs to a different chain");
        }
        tenantChains.requireActive(principal.tenantId(), chain);
        tenantChains.requireWithdrawalEnabled(principal.tenantId(), chain, symbol);
        if (repository.hasOpenReorgDeficit(
                principal.tenantId(), address.id(), chain, symbol)) {
            throw new IllegalStateException(
                    "withdrawals are paused because this account has an unresolved deposit reorg deficit");
        }
        String toAddress = required(command.toAddress(), "toAddress", 160);
        String amount = required(command.amount(), "amount", 120);
        String externalReference = optional(command.externalReference(), 160);
        String requestHash = crypto.sha256(
                address.id() + "\n" + chain + "\n" + symbol + "\n"
                        + toAddress + "\n" + amount + "\n" + externalReference);

        String normalizedIdempotencyKey = null;
        if ("API".equals(normalizedSource)) {
            normalizedIdempotencyKey = requireIdempotencyKey(idempotencyKey);
            WithdrawalView replay = replay(
                    principal.tenantId(), normalizedIdempotencyKey, requestHash);
            if (replay != null) {
                return replay;
            }
            if (!repository.beginIdempotency(
                    principal.tenantId(), normalizedIdempotencyKey, CREATE_OPERATION,
                    requestHash, null)) {
                replay = replay(principal.tenantId(), normalizedIdempotencyKey, requestHash);
                if (replay != null) {
                    return replay;
                }
                throw new IllegalStateException(
                        "a withdrawal request with this idempotency key is still processing");
            }
        }

        TenantRecord tenant = repository.requireTenant(principal.tenantId());
        String tenantOrderSlug = tenant.slug().substring(0, Math.min(tenant.slug().length(), 32));
        String orderPrefix = "CW-" + tenantOrderSlug + "-"
                + address.id().toString().substring(0, 8);
        CustodyWithdrawalExecutionService.ExecutionResult result = executionService.execute(
                tenant.id(), address, chain, symbol, toAddress,
                new BigDecimal(amount), orderPrefix);
        UUID withdrawalId = UUID.randomUUID();
        String orderNo = result.orderNo();
        String status = result.status();
        BigDecimal amountValue = result.amount();
        BigDecimal feeValue = result.fee();
        String broadcastAddress = result.toAddress();
        repository.insertCustodyWithdrawal(
                withdrawalId,
                tenant.id(),
                address.id(),
                orderNo,
                externalReference,
                normalizedIdempotencyKey,
                chain,
                symbol,
                broadcastAddress,
                amountValue,
                feeValue,
                status,
                "API".equals(normalizedSource) ? "API_KEY" : "CONSOLE",
                principal.actorId().toString());
        gasService.reserveWithdrawal(
                tenant.id(), withdrawalId, orderNo, chain, symbol);

        UUID eventId = UUID.randomUUID();
        WithdrawalView view = new WithdrawalView(
                withdrawalId, address.id(), orderNo, externalReference,
                chain, symbol, broadcastAddress, amountValue, feeValue,
                status, null, null, normalizedSource, Instant.now());
        repository.insertEventWithDeliveries(
                eventId, tenant.id(), "WITHDRAWAL.CREATED", "WITHDRAWAL", orderNo,
                eventPayload(eventId, "WITHDRAWAL.CREATED", view),
                "API".equals(address.source()));
        repository.audit(
                tenant.id(), principal.actorType().name(), principal.actorId().toString(),
                "WITHDRAWAL.CREATE", "CUSTODY_WITHDRAWAL", withdrawalId.toString(), sourceIp,
                json(Map.of(
                        "orderNo", orderNo,
                        "chain", chain,
                        "assetSymbol", symbol,
                        "amount", amountValue,
                        "source", normalizedSource)));
        if ("API".equals(normalizedSource)) {
            repository.completeIdempotency(
                    tenant.id(), normalizedIdempotencyKey, CREATE_OPERATION, 201, json(view));
        }
        return view;
    }

    public List<Map<String, Object>> withdrawals(CustodyPrincipal principal, String chain,
                                                  String assetSymbol, String status,
                                                  String search, int limit, int offset) {
        requireScope(principal, "withdrawals:read");
        return repository.listCustodyWithdrawals(
                principal.tenantId(), upperOrEmpty(chain), upperOrEmpty(assetSymbol),
                upperOrEmpty(status), search, limit, offset);
    }

    public List<Map<String, Object>> deposits(CustodyPrincipal principal, String chain,
                                               String assetSymbol, String status,
                                               String search, int limit, int offset) {
        requireScope(principal, "deposits:read");
        return repository.listCustodyDeposits(
                principal.tenantId(), upperOrEmpty(chain), upperOrEmpty(assetSymbol),
                upperOrEmpty(status), search, limit, offset);
    }

    private WithdrawalView replay(UUID tenantId, String key, String requestHash) {
        IdempotencyRecord existing = repository.findIdempotency(tenantId, key, CREATE_OPERATION)
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
            return objectMapper.readValue(existing.responseJson(), WithdrawalView.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored idempotent response is invalid", e);
        }
    }

    String eventPayload(UUID eventId, String eventType, WithdrawalView view) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("withdrawalId", view.id());
        data.put("custodyAddressId", view.custodyAddressId());
        data.put("externalReference", view.externalReference());
        data.put("orderNo", view.orderNo());
        data.put("chain", view.chain());
        data.put("asset", view.assetSymbol());
        data.put("toAddress", view.toAddress());
        data.put("amount", view.amount());
        data.put("fee", view.fee());
        data.put("status", view.status());
        data.put("txHash", view.txHash());
        data.put("errorMessage", view.errorMessage());
        return json(Map.of(
                "id", eventId,
                "type", eventType,
                "createdAt", Instant.now(),
                "data", data));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("value cannot be serialized as JSON", e);
        }
    }

    private static String normalizeSource(String source) {
        String value = source == null ? "" : source.trim().toUpperCase(Locale.ROOT);
        if (!"API".equals(value) && !"CONSOLE".equals(value)) {
            throw new IllegalArgumentException("withdrawal source must be API or CONSOLE");
        }
        return value;
    }

    private static String requireUpper(String value, String field, int max) {
        String result = required(value, field, max).toUpperCase(Locale.ROOT);
        if (!result.matches("^[A-Z][A-Z0-9_]{1," + (max - 1) + "}$")) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return result;
    }

    private static String required(String value, String field, int max) {
        String result = value == null ? "" : value.trim();
        if (result.isBlank() || result.length() > max) {
            throw new IllegalArgumentException(field + " is required and must not exceed " + max + " characters");
        }
        return result;
    }

    private static String optional(String value, int max) {
        String result = value == null ? "" : value.trim();
        if (result.length() > max) {
            throw new IllegalArgumentException("externalReference must not exceed " + max + " characters");
        }
        return result.isBlank() ? null : result;
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

    public record CreateWithdrawalCommand(
            UUID custodyAddressId,
            String chain,
            String assetSymbol,
            String toAddress,
            String amount,
            String externalReference,
            Boolean confirmed
    ) {
    }

    public record WithdrawalView(
            UUID id,
            UUID custodyAddressId,
            String orderNo,
            String externalReference,
            String chain,
            String assetSymbol,
            String toAddress,
            BigDecimal amount,
            BigDecimal fee,
            String status,
            String txHash,
            String errorMessage,
            String source,
            Instant createdAt
    ) {
    }
}
