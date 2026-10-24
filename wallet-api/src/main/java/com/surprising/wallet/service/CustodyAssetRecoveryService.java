package com.surprising.wallet.service;

import com.surprising.wallet.model.PageView;
import com.surprising.wallet.repository.CustodyAssetRecoveryRepository;
import com.surprising.wallet.repository.ChainAddressRepository;
import com.surprising.wallet.repository.CustodyAddressRepository;
import com.surprising.wallet.repository.CustodyAuditRepository;
import com.surprising.wallet.repository.DepositRecordRepository;
import com.surprising.wallet.gateway.CustodyAssetRecoveryChainGateway;
import com.surprising.wallet.exception.CustodyForbiddenException;
import com.surprising.wallet.model.CustodyPrincipal;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.repository.CustodyAssetRecoveryRepository.RecoveryRecord;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.UUID;

/**
 * 托管资产找回服务，支持将误转入充值地址的资产找回至租户指定地址。
 *
 * <p>支持 EVM 链的原生币和 ERC20 代币找回。流程：
 * <ol>
 *   <li>管理员创建找回请求（指定源地址、资产、目标地址）</li>
 *   <li>签名服务对找回交易进行双重签名</li>
 *   <li>广播上链并记录结果</li>
 * </ol>
 */
@Service
public class CustodyAssetRecoveryService {
    private final CustodyAssetRecoveryRepository repository;
    private final CustodyAuditRepository audits;
    private final CustodyAddressRepository custodyAddressRepository;
    /** chain_address 单表仓储。 */
    private final ChainAddressRepository chainAddressRepository;
    /** deposit_record 单表仓储。 */
    private final DepositRecordRepository depositRecordRepository;
    private final List<CustodyAssetRecoveryChainGateway> gateways;
    private final ObjectMapper objectMapper;
    private final CustodyEventService events;
    public CustodyAssetRecoveryService(CustodyAssetRecoveryRepository repository,
                                       CustodyAuditRepository audits,
                                       CustodyAddressRepository custodyAddressRepository,
                                       ChainAddressRepository chainAddressRepository,
                                       DepositRecordRepository depositRecordRepository,
                                       List<CustodyAssetRecoveryChainGateway> gateways,
                                       ObjectMapper objectMapper,
                                       CustodyEventService events) {
        this.repository = repository;
        this.audits = audits;
        this.custodyAddressRepository = custodyAddressRepository;
        this.chainAddressRepository = chainAddressRepository;
        this.depositRecordRepository = depositRecordRepository;
        this.gateways = List.copyOf(gateways);
        this.objectMapper = objectMapper;
        this.events = events;
    }
    public RecoveryView submit(CustodyPrincipal principal, SubmitCommand command, String sourceIp) {
        requireTenant(principal);
        String actualChain = upper(command.actualChain(), "actualChain", 32);
        String expectedChain = optionalUpper(command.expectedChain(), 32);
        String assetSymbol = upper(command.assetSymbol(), "assetSymbol", 32);
        String txHash = required(command.txHash(), "txHash", 128).toLowerCase(Locale.ROOT);
        String destinationAddress = required(
                command.destinationAddress(), "destinationAddress", 160).toLowerCase(Locale.ROOT);
        String tokenContract = optional(command.tokenContract(), 128);
        if (tokenContract != null) {
            tokenContract = tokenContract.toLowerCase(Locale.ROOT);
        }
        long requestedLogIndex = command.logIndex() == null ? 0L : command.logIndex();
        if (requestedLogIndex < 0) {
            throw new IllegalArgumentException("logIndex cannot be negative");
        }
        Ownership ownership = requireOwnership(
                principal.tenantId(), destinationAddress,
                expectedChain == null ? actualChain : expectedChain);
        BigDecimal claimedAmount = command.claimedAmount() == null
                ? null : new BigDecimal(command.claimedAmount().trim());
        if (claimedAmount != null && claimedAmount.signum() <= 0) {
            throw new IllegalArgumentException("claimedAmount must be positive");
        }
        if (command.logIndex() == null) {
            RecoveryRecord existing = repository.findByRequest(
                    actualChain, txHash, destinationAddress, assetSymbol, tokenContract).orElse(null);
            if (existing != null) {
                return toView(requireSameTenant(principal.tenantId(), existing));
            }
        }
        RecoveryRecord recovery;
        try {
            recovery = repository.insert(
                    UUID.randomUUID(), principal.tenantId(), actualChain, expectedChain,
                    assetSymbol, tokenContract, txHash, requestedLogIndex, command.logIndex(),
                    destinationAddress, claimedAmount, principal.actorId());
        } catch (DuplicateKeyException e) {
            RecoveryRecord existing = repository.findByTransaction(actualChain, txHash, requestedLogIndex)
                    .orElseThrow(() -> new IllegalStateException("recovery request already exists"));
            return toView(requireSameTenant(principal.tenantId(), existing));
        }
        audits.insert(UUID.randomUUID(), principal.tenantId(), principal.actorType().name(),
                principal.actorId().toString(), "ASSET_RECOVERY.SUBMIT", "ASSET_RECOVERY",
                recovery.id().toString(), sourceIp,
                "{\"actualChain\":\"" + actualChain + "\",\"txHash\":\"" + txHash + "\"}");
        return toView(verifyInternal(recovery, ownership, command.logIndex()));
    }
    private static RecoveryRecord requireSameTenant(UUID tenantId, RecoveryRecord recovery) {
        if (!tenantId.equals(recovery.tenantId())) {
            throw new IllegalStateException("recovery request already belongs to another tenant");
        }
        return recovery;
    }

    public PageView<RecoveryView> tenantList(CustodyPrincipal principal, String status,
                                             int limit, int offset) {
        requireTenant(principal);
        String normalizedStatus = normalizedStatus(status);
        int pageSize = pageSize(limit);
        int pageOffset = offset(offset);
        return new PageView<>(
                repository.list(principal.tenantId(), normalizedStatus, pageSize, pageOffset)
                        .stream().map(CustodyAssetRecoveryService::toView).toList(),
                repository.count(principal.tenantId(), normalizedStatus),
                pageSize, pageOffset);
    }

    public PageView<RecoveryView> platformList(CustodyPrincipal principal, String status,
                                               int limit, int offset) {
        requirePlatform(principal);
        String normalizedStatus = normalizedStatus(status);
        int pageSize = pageSize(limit);
        int pageOffset = offset(offset);
        return new PageView<>(
                repository.list(null, normalizedStatus, pageSize, pageOffset)
                        .stream().map(CustodyAssetRecoveryService::toView).toList(),
                repository.count(null, normalizedStatus),
                pageSize, pageOffset);
    }
    public RecoveryView verify(CustodyPrincipal principal, UUID id, String sourceIp) {
        requirePlatform(principal);
        RecoveryRecord recovery = repository.require(id);
        Ownership ownership = requireOwnership(
                recovery.tenantId(), recovery.destinationAddress(),
                recovery.expectedChain() == null ? recovery.actualChain() : recovery.expectedChain());
        RecoveryRecord result = verifyInternal(
                recovery, ownership, recovery.requestedLogIndex());
        audits.insert(UUID.randomUUID(), recovery.tenantId(), principal.actorType().name(), principal.actorId().toString(),
                "ASSET_RECOVERY.VERIFY", "ASSET_RECOVERY", id.toString(), sourceIp,
                "{\"status\":\"" + result.status() + "\"}");
        return toView(result);
    }

    @Transactional(rollbackFor = Throwable.class)
    public RecoveryView approve(CustodyPrincipal principal, UUID id,
                                ApproveCommand command, String sourceIp) {
        requirePlatform(principal);
        RecoveryRecord existing = repository.require(id);
        String recoveryAddress = required(command.recoveryAddress(), "recoveryAddress", 160)
                .toLowerCase(Locale.ROOT);
        if (!recoveryAddress.matches("^0x[0-9a-f]{40}$")) {
            throw new IllegalArgumentException("a valid EVM recovery address is required");
        }
        if (recoveryAddress.equalsIgnoreCase(existing.destinationAddress())) {
            throw new IllegalArgumentException("recoveryAddress must differ from the source address");
        }
        RecoveryRecord result = repository.approve(id, recoveryAddress, principal.actorId());
        audits.insert(UUID.randomUUID(), existing.tenantId(), principal.actorType().name(), principal.actorId().toString(),
                "ASSET_RECOVERY.APPROVE", "ASSET_RECOVERY", id.toString(), sourceIp,
                "{\"recoveryAddress\":\"" + recoveryAddress + "\"}");
        return toView(result);
    }
    public RecoveryView execute(CustodyPrincipal principal, UUID id, String sourceIp) {
        requirePlatform(principal);
        RecoveryRecord existing = repository.require(id);
        if (!repository.claimExecution(id)) {
            throw new IllegalStateException("only an approved recovery case can be executed");
        }
        try {
            CustodyAssetRecoveryChainGateway gateway = gateway(existing.actualChain());
            Ownership ownership = requireOwnership(
                    existing.tenantId(), existing.destinationAddress(),
                    existing.expectedChain() == null ? existing.actualChain() : existing.expectedChain());
            ChainAddressRecord source = sourceAddress(
                    existing.tenantId(), existing.actualChain(), ownership);
            String recoveryTxHash = gateway.execute(new CustodyAssetRecoveryChainGateway.ExecutionRequest(
                    existing.actualChain(), existing.assetSymbol(), existing.tokenContract(),
                    existing.tokenDecimals() == null ? 18 : existing.tokenDecimals(),
                    existing.verifiedAmount(), source, existing.recoveryAddress()));
            RecoveryRecord result = repository.broadcasted(id, recoveryTxHash, principal.actorId());
            audits.insert(UUID.randomUUID(), existing.tenantId(), principal.actorType().name(), principal.actorId().toString(),
                    "ASSET_RECOVERY.EXECUTE", "ASSET_RECOVERY", id.toString(), sourceIp,
                    "{\"recoveryTxHash\":\"" + recoveryTxHash + "\"}");
            return toView(result);
        } catch (RuntimeException e) {
            return toView(repository.executionFailed(id, friendly(e)));
        }
    }
    public RecoveryView confirm(CustodyPrincipal principal, UUID id, String sourceIp) {
        requirePlatform(principal);
        RecoveryRecord result = confirm(repository.require(id));
        if ("RECOVERED".equals(result.status())) {
            audits.insert(UUID.randomUUID(), result.tenantId(), principal.actorType().name(), principal.actorId().toString(),
                    "ASSET_RECOVERY.CONFIRM", "ASSET_RECOVERY", id.toString(), sourceIp,
                    "{\"recoveryTxHash\":\"" + result.recoveryTxHash() + "\"}");
        }
        return toView(result);
    }
    public void confirmBroadcastRecoveries() {
        for (RecoveryRecord recovery : repository.broadcastRecoveries(100)) {
            try {
                RecoveryRecord result = confirm(recovery);
                if ("RECOVERED".equals(result.status())) {
                    audits.insert(UUID.randomUUID(), result.tenantId(), "SYSTEM", "asset-recovery-finality",
                            "ASSET_RECOVERY.CONFIRM", "ASSET_RECOVERY", result.id().toString(), null,
                            "{\"recoveryTxHash\":\"" + result.recoveryTxHash() + "\"}");
                }
            } catch (RuntimeException ignored) {
                // The case remains visible in BROADCAST with its tx hash for the next run/manual review.
            }
        }
    }
    private RecoveryRecord confirm(RecoveryRecord recovery) {
        if (!"BROADCAST".equals(recovery.status())) {
            throw new IllegalStateException("only a broadcast recovery can be confirmed");
        }
        try {
            if (!gateway(recovery.actualChain()).confirmed(
                    recovery.actualChain(), recovery.recoveryTxHash())) {
                return recovery;
            }
        } catch (CustodyAssetRecoveryChainGateway.PermanentlyFailedTransactionException e) {
            return repository.broadcastFailed(recovery.id(), friendly(e));
        }
        RecoveryRecord confirmed = repository.confirmed(recovery.id());
        publishRecovered(confirmed);
        return confirmed;
    }
    /**
     * 提交或广播 {@code publishRecovered} 对应的请求，并记录发送结果。
     */
    private void publishRecovered(RecoveryRecord recovery) {
        UUID eventId = UUID.randomUUID();
        boolean automatic = custodyAddressRepository.hasApiSource(
                recovery.tenantId(), recovery.custodyAddressId());
        Map<String, Object> data = Map.of(
                "recoveryId", recovery.id(),
                "chain", recovery.actualChain(),
                "asset", recovery.assetSymbol(),
                "amount", recovery.verifiedAmount(),
                "sourceAddress", recovery.destinationAddress(),
                "recoveryAddress", recovery.recoveryAddress(),
                "sourceTxHash", recovery.txHash(),
                "recoveryTxHash", recovery.recoveryTxHash());
        events.publish(
                eventId, recovery.tenantId(), "ASSET_RECOVERY.RECOVERED",
                "ASSET_RECOVERY", recovery.id().toString(),
                json(Map.of("id", eventId, "type", "ASSET_RECOVERY.RECOVERED",
                        "createdAt", java.time.Instant.now(), "data", data)), automatic);
    }
    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new IllegalStateException("failed to serialize asset recovery event", e);
        }
    }

    @Transactional(rollbackFor = Throwable.class)
    public RecoveryView reject(CustodyPrincipal principal, UUID id,
                               RejectCommand command, String sourceIp) {
        requirePlatform(principal);
        RecoveryRecord existing = repository.require(id);
        String reason = required(command.reason(), "reason", 500);
        RecoveryRecord result = repository.reject(id, reason, principal.actorId());
        audits.insert(UUID.randomUUID(), existing.tenantId(), principal.actorType().name(), principal.actorId().toString(),
                "ASSET_RECOVERY.REJECT", "ASSET_RECOVERY", id.toString(), sourceIp,
                "{\"reason\":\"" + reason.replace("\"", "'") + "\"}");
        return toView(result);
    }

    @Transactional(rollbackFor = Throwable.class)
    public RecoveryView cancel(CustodyPrincipal principal, UUID id, String sourceIp) {
        requireTenant(principal);
        RecoveryRecord result = repository.cancel(principal.tenantId(), id);
        audits.insert(UUID.randomUUID(), principal.tenantId(), principal.actorType().name(), principal.actorId().toString(),
                "ASSET_RECOVERY.CANCEL", "ASSET_RECOVERY", id.toString(), sourceIp, "{}");
        return toView(result);
    }

    private RecoveryRecord verifyInternal(RecoveryRecord recovery, Ownership ownership,
                                          Long requestedLogIndex) {
        try {
            CustodyAssetRecoveryChainGateway gateway = gateway(recovery.actualChain());
            CustodyAssetRecoveryChainGateway.Verification verification = gateway.verify(
                    new CustodyAssetRecoveryChainGateway.VerificationRequest(
                            recovery.actualChain(), recovery.assetSymbol(), recovery.tokenContract(),
                            recovery.txHash(), requestedLogIndex, recovery.destinationAddress(),
                            recovery.claimedAmount()));
            if (depositRecordRepository.existsCanonical(recovery.actualChain(), recovery.txHash(),
                    verification.logIndex())) {
                return repository.verificationFailed(
                        recovery.id(), "this transfer is already recorded by the deposit system");
            }
            return repository.verified(recovery.id(), ownership.custodyAddressId(), verification);
        } catch (RuntimeException e) {
            return repository.verificationFailed(recovery.id(), friendly(e));
        }
    }

    private Ownership requireOwnership(UUID tenantId, String destinationAddress,
                                       String preferredChain) {
        List<Ownership> owners = new ArrayList<>();
        for (var custodyRow : custodyAddressRepository.listByTenantAndAddress(
                tenantId, destinationAddress, preferredChain)) {
            long chainAddressId = custodyRow.chainAddressId();
            chainAddressRepository.findOwned(tenantId, chainAddressId).ifPresent(address ->
                    owners.add(new Ownership(
                            custodyRow.id(), string(address.getAccountId()),
                            address.getUserId().longValue(), address.getBiz(),
                            address.getAddressIndex().longValue(), string(address.getAddress()),
                            nullable(address.getOwnerAddress()), string(address.getDerivationPath()),
                            string(address.getWalletRole()))));
        }
        if (owners.isEmpty()) {
            throw new IllegalArgumentException(
                    "destination address is not controlled by this tenant");
        }
        Ownership selected = owners.getFirst();
        boolean ambiguous = owners.stream().skip(1).anyMatch(owner ->
                owner.userId() != selected.userId()
                        || owner.biz() != selected.biz()
                        || owner.addressIndex() != selected.addressIndex()
                        || !java.util.Objects.equals(
                                owner.derivationPath(), selected.derivationPath()));
        if (ambiguous) {
            throw new IllegalStateException("destination address has ambiguous ownership");
        }
        return selected;
    }
    private ChainAddressRecord sourceAddress(UUID tenantId, String actualChain, Ownership ownership) {
        return ChainAddressRecord.builder()
                .tenantId(tenantId)
                .chain(actualChain)
                .assetSymbol(actualChain)
                .accountId(ownership.accountId())
                .userId(ownership.userId())
                .biz(ownership.biz())
                .addressIndex(ownership.addressIndex())
                .address(ownership.address())
                .ownerAddress(ownership.ownerAddress())
                .derivationPath(ownership.derivationPath())
                .walletRole(ownership.walletRole())
                .enabled(true)
                .build();
    }
    private CustodyAssetRecoveryChainGateway gateway(String chain) {
        return gateways.stream().filter(candidate -> candidate.supports(chain)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "automatic recovery is not available for chain " + chain));
    }
    private static String friendly(Throwable error) {
        Throwable value = error;
        while (value.getCause() != null && value.getMessage() == null) {
            value = value.getCause();
        }
        String message = value.getMessage();
        return message == null || message.isBlank() ? "chain verification failed" : message;
    }
    private static void requireTenant(CustodyPrincipal principal) {
        if (principal == null || principal.tenantId() == null || !principal.hasScope("deposits:read")) {
            throw new CustodyForbiddenException("tenant deposits access required");
        }
    }
    private static void requirePlatform(CustodyPrincipal principal) {
        if (principal == null || principal.tenantId() != null
                || !"PLATFORM_ADMIN".equals(principal.role())) {
            throw new CustodyForbiddenException("platform administrator required");
        }
    }
    private static int pageSize(int value) {
        return Math.max(1, Math.min(value, 200));
    }
    private static int offset(int value) {
        return Math.max(0, value);
    }
    private static String normalizedStatus(String status) {
        return status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
    }

    /** 将数据库对象转换为可空字符串。 */
    private static String nullable(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /** 将数据库对象转换为字符串。 */
    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /** 将数据库数值转换为长整型。 */
    private static long longValue(Object value) {
        return value == null ? 0L : ((Number) value).longValue();
    }

    /** 将数据库数值转换为整型。 */
    private static int intValue(Object value) {
        return value == null ? 0 : ((Number) value).intValue();
    }
    private static String upper(String value, String field, int max) {
        return required(value, field, max).toUpperCase(Locale.ROOT);
    }
    private static String optionalUpper(String value, int max) {
        String result = optional(value, max);
        return result == null ? null : result.toUpperCase(Locale.ROOT);
    }
    private static String required(String value, String field, int max) {
        String result = value == null ? "" : value.trim();
        if (result.isEmpty() || result.length() > max) {
            throw new IllegalArgumentException(field + " is required and must not exceed " + max + " characters");
        }
        return result;
    }
    private static String optional(String value, int max) {
        String result = value == null ? "" : value.trim();
        if (result.length() > max) {
            throw new IllegalArgumentException("value must not exceed " + max + " characters");
        }
        return result.isEmpty() ? null : result;
    }

    /** 将持久化记录转换为应用层返回模型，避免 Web 层依赖 Repository 类型。 */
    private static RecoveryView toView(RecoveryRecord record) {
        return new RecoveryView(
                record.id(), record.tenantId(), record.custodyAddressId(), record.actualChain(),
                record.expectedChain(), record.assetSymbol(), record.tokenContract(),
                record.tokenDecimals(), record.txHash(), record.logIndex(), record.requestedLogIndex(),
                record.destinationAddress(), record.recoveryAddress(), record.claimedAmount(),
                record.verifiedAmount(), record.blockHeight(), record.blockHash(),
                record.confirmations(), record.status(), record.verificationDetails(),
                record.failureReason(), record.recoveryTxHash(), record.requestedBy(),
                record.reviewedBy(), record.executedBy(), record.approvedAt(), record.executedAt(),
                record.createdAt(), record.updatedAt());
    }

    /** 资产找回应用层返回模型。 */
    public record RecoveryView(
            UUID id, UUID tenantId, UUID custodyAddressId, String actualChain,
            String expectedChain, String assetSymbol, String tokenContract,
            Integer tokenDecimals, String txHash, long logIndex, Long requestedLogIndex,
            String destinationAddress, String recoveryAddress, BigDecimal claimedAmount,
            BigDecimal verifiedAmount, Long blockHeight, String blockHash, int confirmations,
            String status, String verificationDetails, String failureReason,
            String recoveryTxHash, UUID requestedBy, UUID reviewedBy, UUID executedBy,
            java.time.Instant approvedAt, java.time.Instant executedAt,
            java.time.Instant createdAt, java.time.Instant updatedAt) {
    }

    public record SubmitCommand(String actualChain, String expectedChain, String assetSymbol,
                                String tokenContract, String txHash, Long logIndex,
                                String destinationAddress, String claimedAmount) {
    }
    public record ApproveCommand(String recoveryAddress) {
    }
    public record RejectCommand(String reason) {
    }

    private record Ownership(UUID custodyAddressId, String accountId, long userId, int biz,
                             long addressIndex, String address, String ownerAddress,
                             String derivationPath, String walletRole) {
    }
}
