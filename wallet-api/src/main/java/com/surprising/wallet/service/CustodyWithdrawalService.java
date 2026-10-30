package com.surprising.wallet.service;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.surprising.wallet.chain.model.CustodyAddressRecord;
import com.surprising.wallet.model.custody.IdempotencyRecord;
import com.surprising.wallet.model.custody.TenantRecord;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.surprising.wallet.exception.CustodyForbiddenException;
import com.surprising.wallet.exception.CustodyWithdrawalRefusedException;
import com.surprising.wallet.model.CustodyPrincipal;
import com.surprising.wallet.repository.CustodyTenantTableRepository;
import com.surprising.wallet.repository.ChainAddressRepository;
import com.surprising.wallet.repository.CustodyReorgDeficitRepository;
import com.surprising.wallet.repository.CustodyIdempotencyRepository;
import com.surprising.wallet.repository.CustodyWithdrawalRepository;
import com.surprising.wallet.repository.CustodyAuditRepository;
import com.surprising.wallet.repository.CustodyGasAccountRepository;
import com.surprising.wallet.repository.CustodyAddressRepository;

/**
 * 托管提现服务，处理租户的提现请求流程。
 *
 * <p>核心功能：创建提现请求（幂等）、查询提现记录、校验地址归属、
 * 管理员审批、估算手续费。提现流程包括预扣余额、链上构造交易、
 * sig1/sig2 双重签名、广播上链、确认后扣款。
 */
@Service
public class CustodyWithdrawalService {
    /** 提现创建操作的幂等键前缀 */
    private static final String CREATE_OPERATION = "WITHDRAWAL.CREATE";
    private final CustodyTenantTableRepository tenants;
    private final ChainAddressRepository chainAddresses;
    private final CustodyReorgDeficitRepository deficits;
    private final CustodyIdempotencyRepository idempotency;
    private final CustodyWithdrawalRepository withdrawals;
    private final CustodyPoolFundingService pool;
    private final CustodyAuditRepository audits;
    private final CustodyWithdrawalExecutionService executionService;
    private final CustodyGasService gasService;
    private final CustodyTenantChainService tenantChains;
    private final CustodyCryptoService crypto;
    private final ObjectMapper objectMapper;
    private final CustodyEventService events;
    private final CustodyAddressRepository addresses;
    private final CustodyGasAccountRepository gasAccounts;

    public CustodyWithdrawalService(CustodyWithdrawalExecutionService executionService,
                                    CustodyGasService gasService,
                                    CustodyTenantChainService tenantChains,
                                    CustodyCryptoService crypto,
                                    ObjectMapper objectMapper,
                                    CustodyEventService events,
                                    CustodyAddressRepository addresses,
                                    CustodyGasAccountRepository gasAccounts,
                                    CustodyTenantTableRepository tenants, ChainAddressRepository chainAddresses,
                                    CustodyReorgDeficitRepository deficits, CustodyIdempotencyRepository idempotency,
                                    CustodyWithdrawalRepository withdrawals, CustodyAuditRepository audits, CustodyPoolFundingService pool) {
        this.tenants = tenants;
        this.chainAddresses = chainAddresses;
        this.deficits = deficits;
        this.idempotency = idempotency;
        this.withdrawals = withdrawals;
        this.audits = audits;
        this.executionService = executionService;
        this.gasService = gasService;
        this.tenantChains = tenantChains;
        this.crypto = crypto;
        this.objectMapper = objectMapper;
        this.events = events;
        this.addresses = addresses;
        this.gasAccounts = gasAccounts;
        this.pool = pool;
    }

    @Transactional(rollbackFor = Throwable.class)
    public WithdrawalView create(CustodyPrincipal principal, CreateWithdrawalCommand command,
                                 String source, String idempotencyKey, String sourceIp) {
        requireScope(principal, "withdrawals:write");
        String normalizedSource = normalizeSource(source);
        command=normalized(command);
        String chain=command.chain(),symbol=command.assetSymbol(),toAddress=command.toAddress(),amount=command.amount();
        String externalReference=command.externalReference();
        String requestHash=requestHash(command);
        String normalizedIdempotencyKey = requireIdempotencyKey(idempotencyKey);
        WithdrawalView replay = replay(principal.tenantId(), normalizedIdempotencyKey, requestHash);
        if (replay != null) return replay;
        TenantRecord tenant = tenants.findById(principal.tenantId())
                .orElseThrow(() -> new IllegalArgumentException("tenant not found"));
        // The unique row serializes create against resolution. A refusal permanently fences this key.
        if (!idempotency.begin(principal.tenantId(), normalizedIdempotencyKey, CREATE_OPERATION, requestHash, null)) {
            replay = replay(principal.tenantId(), normalizedIdempotencyKey, requestHash);
            if (replay != null) return replay;
            throw new IllegalStateException("a withdrawal request with this idempotency key is still processing");
        }
        CustodyAddressRecord address=command.fundingSource()==FundingSource.TENANT_POOL
                ? pool.source(principal.tenantId(),chain) : addresses.require(principal.tenantId(),command.custodyAddressId());
        if (command.fundingSource()==FundingSource.ADDRESS && gasAccounts.existsForAddress(principal.tenantId(), command.custodyAddressId())) {
            throw new IllegalArgumentException(
                    "gas reserve addresses cannot be used as customer withdrawal sources");
        }
        if (!address.chain().equals(chain)) {
            throw new IllegalArgumentException("custody address belongs to a different chain");
        }
        tenantChains.requireActive(principal.tenantId(), chain);
        tenantChains.requireWithdrawalEnabled(principal.tenantId(), chain, symbol);
        var account = chainAddresses.findOwned(principal.tenantId(), address.chainAddressId())
                .orElseThrow(() -> new IllegalArgumentException("custody chain address not found"));
        if (deficits.existsOpenAsset(principal.tenantId(), chain, symbol)) {
            throw new IllegalStateException(
                    "withdrawals are paused because this tenant asset has an unresolved deposit reorg deficit");
        }

        String tenantOrderSlug = tenant.slug().substring(0, Math.min(tenant.slug().length(), 32));
        String orderPrefix = "CW-" + tenantOrderSlug + "-"
                + address.id().toString().substring(0, 8);
        CustodyWithdrawalExecutionService.ExecutionResult result = executionService.execute(
                address, account, symbol, toAddress, new BigDecimal(amount), orderPrefix, command.fundingSource());
        UUID withdrawalId = UUID.randomUUID();
        String orderNo = result.orderNo();
        String status = result.status();
        BigDecimal amountValue = result.amount();
        BigDecimal feeValue = result.fee();
        String broadcastAddress = result.toAddress();
        withdrawals.insert(
                withdrawalId,
                tenant.id(),
                address.id(),
                result.orderId(),
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
                principal.actorId().toString(),command.subject(),command.fundingSource().name());
        gasService.reserveWithdrawal(
                tenant.id(), withdrawalId, chain, symbol);

        UUID eventId = UUID.randomUUID();
        WithdrawalView view = new WithdrawalView(
                withdrawalId, address.id(), orderNo, externalReference,
                chain, symbol, broadcastAddress, amountValue, feeValue,
                status, null, null, normalizedSource, Instant.now(), command.subject(),command.fundingSource());
        events.publish(
                eventId, tenant.id(), "WITHDRAWAL.CREATED", "WITHDRAWAL", orderNo,
                eventPayload(eventId, "WITHDRAWAL.CREATED", view),
                "API".equals(normalizedSource));
        audits.insert(UUID.randomUUID(),
                tenant.id(), principal.actorType().name(), principal.actorId().toString(),
                "WITHDRAWAL.CREATE", "CUSTODY_WITHDRAWAL", withdrawalId.toString(), sourceIp,
                json(Map.of(
                        "orderNo", orderNo,
                        "chain", chain,
                        "assetSymbol", symbol,
                        "amount", amountValue,
                        "source", normalizedSource)));
        if (idempotency.complete(tenant.id(), normalizedIdempotencyKey, CREATE_OPERATION, 202, json(view)) != 1) {
            throw new IllegalStateException("withdrawal acceptance receipt changed before commit");
        }
        return view;
    }

    /** Irrevocably refuse an unaccepted key, or return its committed acceptance. Never infer absence from a GET. */
    @Transactional(rollbackFor = Throwable.class)
    public SubmissionResolution resolve(CustodyPrincipal principal, CreateWithdrawalCommand command,
                                        String idempotencyKey, String sourceIp) {
        requireScope(principal, "withdrawals:write");
        command=normalized(command);
        String key=requireIdempotencyKey(idempotencyKey);
        String external=command.externalReference();
        String hash=requestHash(command);
        tenants.findById(principal.tenantId()).orElseThrow(() -> new IllegalArgumentException("tenant not found"));
        if (idempotency.begin(principal.tenantId(), key, CREATE_OPERATION, hash, null)) {
            if (withdrawals.existsSubmission(principal.tenantId(),key,external)) {
                throw new IllegalStateException("accepted withdrawal is missing its permanent receipt; reconciliation is required");
            }
            var refusal = new SubmissionResolution("REJECTED", key, hash, external, null);
            if (idempotency.complete(principal.tenantId(), key, CREATE_OPERATION, 422, json(refusal)) != 1) {
                throw new IllegalStateException("withdrawal refusal receipt changed before commit");
            }
            audits.insert(UUID.randomUUID(), principal.tenantId(), principal.actorType().name(),
                    principal.actorId().toString(), "WITHDRAWAL.REFUSE_UNACCEPTED", "IDEMPOTENCY_KEY", key,
                    sourceIp, json(Map.of("requestHash", hash, "externalReference", external == null ? "" : external)));
            return refusal;
        }
        try {
            var accepted = replay(principal.tenantId(), key, hash);
            if (accepted == null) throw new IllegalStateException("withdrawal receipt is still processing");
            return new SubmissionResolution("ACCEPTED", key, hash, external, accepted);
        } catch (CustodyWithdrawalRefusedException refused) {
            return new SubmissionResolution("REJECTED", key, hash, external, null);
        }
    }

    private CreateWithdrawalCommand normalized(CreateWithdrawalCommand command) {
        if(command==null || !Boolean.TRUE.equals(command.confirmed()) || command.fundingSource()==null) {
            throw new IllegalArgumentException("explicit funding source and withdrawal confirmation are required");
        }
        if((command.fundingSource()==FundingSource.ADDRESS && command.custodyAddressId()==null)
                || (command.fundingSource()==FundingSource.TENANT_POOL && command.custodyAddressId()!=null)) {
            throw new IllegalArgumentException("address funding requires a source address; tenant pool selects its own source");
        }
        return new CreateWithdrawalCommand(command.custodyAddressId(),requireUpper(command.chain(),"chain",32),
                requireUpper(command.assetSymbol(),"assetSymbol",32),required(command.toAddress(),"toAddress",160),
                normalizedAmount(command.amount()),optional(command.externalReference(),160),true,
                required(command.subject(),"subject",160),command.fundingSource());
    }

    private String requestHash(CreateWithdrawalCommand command) {
        return crypto.sha256(command.fundingSource()+"\n"+command.custodyAddressId()+"\n"+command.subject()+"\n"
                +command.chain()+"\n"+command.assetSymbol()+"\n"+command.toAddress()+"\n"+command.amount()+"\n"
                +(command.externalReference()==null?"null":command.externalReference()));
    }

    private static String normalizedAmount(String value) {
        try {
            BigDecimal amount = new BigDecimal(required(value, "amount", 120));
            if (amount.signum() <= 0 || amount.scale() > 36 || amount.precision() > 78) {
                throw new IllegalArgumentException("withdrawal amount exceeds the supported precision or range");
            }
            return value.trim();
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("amount must be a positive decimal", invalid);
        }
    }

    public record SubmissionResolution(String status, String idempotencyKey, String requestHash,
                                       String externalReference, WithdrawalView withdrawal) { }

    private WithdrawalView replay(UUID tenantId, String key, String requestHash) {
        IdempotencyRecord existing = idempotency.find(tenantId, key, CREATE_OPERATION)
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
        if (Integer.valueOf(422).equals(existing.responseStatus())) {
            throw new CustodyWithdrawalRefusedException(key, requestHash);
        }
        try {
            return objectMapper.readValue(existing.responseJson(), WithdrawalView.class);
        } catch (JacksonException e) {
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
        data.put("subject",view.subject());
        data.put("fundingSource",view.fundingSource());
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
        } catch (JacksonException e) {
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
        if (result.isBlank() || result.length() > max || result.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " is required and must not exceed " + max + " characters");
        }
        return result;
    }
    private static String optional(String value, int max) {
        String result = value == null ? "" : value.trim();
        if (result.length() > max || result.chars().anyMatch(Character::isISOControl)) {
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
    private static void requireScope(CustodyPrincipal principal, String scope) {
        if (principal == null || principal.tenantId() == null || !principal.hasScope(scope)) {
            throw new CustodyForbiddenException(scope + " scope required");
        }
    }

    public enum FundingSource { ADDRESS, TENANT_POOL }

    public record CreateWithdrawalCommand(
            UUID custodyAddressId,
            String chain,
            String assetSymbol,
            String toAddress,
            String amount,
            String externalReference,
            Boolean confirmed,
            String subject,
            FundingSource fundingSource
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
            @com.fasterxml.jackson.annotation.JsonFormat(shape=com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) BigDecimal amount,
            @com.fasterxml.jackson.annotation.JsonFormat(shape=com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) BigDecimal fee,
            String status,
            String txHash,
            String errorMessage,
            String source,
            Instant createdAt,
            String subject,
            FundingSource fundingSource
    ) {
    }
}
