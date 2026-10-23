package com.surprising.wallet.service;

import com.surprising.wallet.common.chain.DepositEvent;
import com.surprising.wallet.model.ReorgedDeposit;
import com.surprising.wallet.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.UUID;

/** Mandatory custody posting steps; all writes join the deposit transaction. */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY, rollbackFor = Throwable.class)
public class CustodyDepositProjectionService {
    private static final String EVENT_TYPE = "DEPOSIT.CONFIRMED";
    private final ChainAddressRepository chainAddresses;
    private final CustodyAddressRepository custodyAddresses;
    private final CustodyGasAccountRepository gasAccounts;
    private final CustodyTenantChainRepository tenantChains;
    private final CustodyDepositRepository deposits;
    private final CustodyLedgerEntryRepository entries;
    private final CustodyReorgDeficitRepository deficits;
    private final LedgerBalanceRepository balances;
    private final CustodyRepository repository;
    private final ObjectMapper objectMapper;

    public void credit(UUID tenantId, DepositEvent event, long logIndex, String accountId,
                       DepositRecordRepository.Credit credit) {
        String chain = event.chainType().name();
        LinkedHashSet<Long> relatedIds = new LinkedHashSet<>();
        var coordinates = new java.util.HashSet<Derivation>();
        for (var address : chainAddresses.findDepositMatches(tenantId, chain, accountId, event.toAddress())) {
            var derivation = new Derivation(address.getUserId(), address.getBiz(),
                    address.getAddressIndex(), address.getWalletRole());
            if (coordinates.add(derivation)) relatedIds.addAll(chainAddresses.listRelatedAddressIds(address));
        }
        var match = custodyAddresses.findDepositOwner(tenantId, chain, java.util.List.copyOf(relatedIds), event.toAddress());
        if (match.isEmpty() || !tenantChains.depositEnabled(tenantId, chain, event.assetSymbol())) return;
        var owner = match.orElseThrow();
        String purpose = gasAccounts.existsForAddress(tenantId, owner.id()) ? "GAS_FUNDING" : "CUSTOMER";
        UUID custodyDepositId = deposits.credit(tenantId, owner.id(), credit.id(), chain,
                event.assetSymbol(), event.txId(), logIndex, event.amount());
        if (custodyDepositId == null) throw new IllegalStateException("failed to persist custody deposit");
        String referenceId = chain + ":" + event.txId() + ":" + logIndex + ":credit:" + credit.generation();
        entries.insertIfAbsent(UUID.randomUUID(), tenantId, owner.id(), chain, event.assetSymbol(), accountId,
                "DEPOSIT", "CREDIT", event.amount(), "DEPOSIT", referenceId);
        BigDecimal deficitApplied = applyOpenDeficits(tenantId, owner.id(), event, accountId, referenceId);
        UUID eventId = UUID.randomUUID();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("depositId", custodyDepositId);
        data.put("subject", owner.subject());
        data.put("chain", chain);
        data.put("asset", event.assetSymbol());
        data.put("address", owner.address());
        data.put("memo", owner.memo());
        data.put("purpose", purpose);
        data.put("amount", event.amount());
        data.put("txHash", event.txId());
        data.put("logIndex", logIndex);
        data.put("blockHeight", event.blockHeight());
        data.put("blockHash", event.blockHash());
        data.put("confirmations", event.confirmations());
        data.put("creditGeneration", credit.generation());
        data.put("appliedToReorgDeficit", deficitApplied);
        data.put("availableAmount", event.amount().subtract(deficitApplied));
        data.put("confirmedAt", Instant.now());
        String payload = json(Map.of(
                "id", eventId,
                "type", EVENT_TYPE,
                "createdAt", Instant.now(),
                "data", data));

        repository.insertEventWithDeliveries(
                eventId, tenantId, EVENT_TYPE, "DEPOSIT", referenceId, payload,
                "API".equals(owner.source()));
    }

    public void reorg(ReorgedDeposit deposit) {
        var custodyDeposit = deposits.lockProjection(deposit.tenantId(), deposit.depositRecordId())
                .orElseThrow(() -> new IllegalStateException("credited custody deposit projection is missing"));
        var owner = custodyAddresses.lockDepositOwnerById(deposit.tenantId(), custodyDeposit.addressId())
                .orElseThrow(() -> new IllegalStateException("credited custody address is missing"));
        if (deposits.markReorged(deposit.tenantId(), custodyDeposit.id()) != 1) {
            throw new IllegalStateException("custody deposit reorg transition failed");
        }
        String referenceId = reference(deposit, "reorg");
        entries.insertIfAbsent(UUID.randomUUID(), deposit.tenantId(), owner.id(), deposit.chain(),
                deposit.assetSymbol(), deposit.accountId(), "DEPOSIT_REORG_REVERSAL", "DEBIT",
                deposit.amount(), "DEPOSIT", referenceId);
        UUID deficitId = null;
        if (deposit.deficitAmount().signum() > 0) {
            deficitId = deficits.open(deposit.tenantId(), owner.id(), deposit.depositRecordId(), deposit.chain(),
                    deposit.assetSymbol(), deposit.accountId(), deposit.amount(), deposit.deficitAmount());
        }
        UUID eventId = UUID.randomUUID();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("depositId", custodyDeposit.id());
        data.put("subject", owner.subject());
        data.put("chain", deposit.chain());
        data.put("asset", deposit.assetSymbol());
        data.put("address", owner.address());
        data.put("memo", owner.memo());
        data.put("amount", deposit.amount());
        data.put("reversedAmount", deposit.reversedAmount());
        data.put("deficitAmount", deposit.deficitAmount());
        data.put("deficitId", deficitId);
        data.put("txHash", deposit.txHash());
        data.put("logIndex", deposit.logIndex());
        data.put("blockHeight", deposit.blockHeight());
        data.put("orphanedBlockHash", deposit.blockHash());
        data.put("replacementBlockHash", deposit.replacementBlockHash());
        data.put("creditGeneration", deposit.creditGeneration());
        data.put("reason", deposit.reason());
        data.put("reorgedAt", Instant.now());
        repository.insertEventWithDeliveries(
                eventId, deposit.tenantId(), "DEPOSIT.REORGED", "DEPOSIT", referenceId,
                json(Map.of("id", eventId, "type", "DEPOSIT.REORGED",
                        "createdAt", Instant.now(), "data", data)),
                "API".equals(owner.source()));
        repository.audit(deposit.tenantId(), "SYSTEM", "deposit-finality",
                "DEPOSIT.REORG", "CUSTODY_DEPOSIT", custodyDeposit.id().toString(), null,
                json(data));
    }
    private BigDecimal applyOpenDeficits(UUID tenantId, UUID addressId, DepositEvent event,
                                         String accountId, String creditReference) {
        BigDecimal remaining = event.amount();
        BigDecimal total = BigDecimal.ZERO;
        for (var deficit : deficits.lockOpenForAccount(tenantId, event.chainType().name(), event.assetSymbol(), accountId)) {
            if (remaining.signum() <= 0) break;
            BigDecimal applied = deficit.amount().subtract(deficit.recovered()).min(remaining);
            if (applied.signum() <= 0) continue;
            if (!balances.debit(event.chainType().name(), event.assetSymbol(), accountId, applied, tenantId)) {
                throw new IllegalStateException("unable to apply deposit to reorg deficit");
            }
            if (deficits.recover(tenantId, deficit.id(), applied) != 1) {
                throw new IllegalStateException("reorg deficit belongs to another tenant");
            }
            entries.insertIfAbsent(UUID.randomUUID(), tenantId, addressId, event.chainType().name(),
                    event.assetSymbol(), accountId, "REORG_DEFICIT_RECOVERY", "DEBIT", applied,
                    "REORG_DEFICIT", creditReference + ":deficit:" + deficit.id());
            remaining = remaining.subtract(applied);
            total = total.add(applied);
        }
        return total;
    }

    private static String reference(ReorgedDeposit deposit, String action) {
        return deposit.chain() + ":" + deposit.txHash() + ":" + deposit.logIndex()
                + ":" + action + ":" + deposit.creditGeneration();
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (tools.jackson.core.JacksonException error) {
            throw new IllegalStateException("failed to serialize deposit event", error);
        }
    }

    private record Derivation(long userId, int biz, long childIndex, String role) { }
}
