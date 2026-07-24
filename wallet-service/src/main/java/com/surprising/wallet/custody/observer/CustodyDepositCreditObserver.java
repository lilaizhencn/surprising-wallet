package com.surprising.wallet.custody.observer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.surprising.wallet.common.chain.DepositEvent;
import com.surprising.wallet.service.dao.DepositCreditObserver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.surprising.wallet.custody.repository.CustodyRepository;
import com.surprising.wallet.custody.repository.CustodyTenantChainRepository;

@Component
public class CustodyDepositCreditObserver implements DepositCreditObserver {
    private static final String EVENT_TYPE = "DEPOSIT.CONFIRMED";    private final JdbcTemplate jdbc;    private final ObjectMapper objectMapper;    private final CustodyRepository repository;    private final CustodyTenantChainRepository tenantChains;

    public CustodyDepositCreditObserver(JdbcTemplate jdbc, ObjectMapper objectMapper,
                                        CustodyRepository repository,
                                        CustodyTenantChainRepository tenantChains) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.repository = repository;
        this.tenantChains = tenantChains;
    }

    @Override
    public void onDepositCredited(DepositEvent event, long logIndex, String accountId) {
        String chain = event.chainType().name();
        List<AddressOwner> owners = jdbc.query("""
                        select c.id as custody_address_id, c.tenant_id, c.subject,
                               c.address, c.memo, c.source,
                               case when exists (
                                   select 1 from custody_gas_account g
                                    where g.tenant_id = c.tenant_id
                                      and g.custody_address_id = c.id
                               ) then 'GAS_FUNDING' else 'CUSTOMER' end as purpose
                          from custody_address c
                          join chain_address base
                            on base.tenant_id = c.tenant_id
                           and base.id = c.chain_address_id
                         where c.chain = ?
                           and exists (
                               select 1
                                 from chain_address related
                                where related.tenant_id = c.tenant_id
                                  and related.chain = base.chain
                                  and related.user_id = base.user_id
                                  and related.biz = base.biz
                                  and related.address_index = base.address_index
                                  and related.wallet_role = base.wallet_role
                                  and related.enabled = true
                                  and (lower(related.account_id) = lower(?)
                                       or lower(related.address) = lower(?))
                           )
                         limit 2
                        """, (rs, rowNum) -> new AddressOwner(
                        rs.getObject("custody_address_id", UUID.class),
                        rs.getObject("tenant_id", UUID.class),
                        rs.getString("subject"),
                        rs.getString("address"),
                        rs.getString("memo"),
                        rs.getString("source"),
                        rs.getString("purpose")),
                chain, accountId, event.toAddress());
        if (owners.isEmpty()) {
            return;
        }
        if (owners.size() > 1) {
            throw new IllegalStateException("deposit address maps to more than one custody tenant");
        }
        AddressOwner owner = owners.getFirst();
        if (!tenantChains.depositEnabled(
                owner.tenantId(), chain, event.assetSymbol())) {
            return;
        }
        DepositProjection depositProjection = jdbc.queryForObject("""
                        select id, credit_generation from deposit_record
                         where chain = ? and tx_hash = ? and log_index = ?
                        """, (rs, rowNum) -> new DepositProjection(
                        rs.getLong("id"), rs.getInt("credit_generation")),
                chain, event.txId(), logIndex);
        if (depositProjection == null) {
            throw new IllegalStateException("credited deposit record is missing");
        }
        long depositRecordId = depositProjection.id();
        if (jdbc.update("""
                        update deposit_record
                           set tenant_id = ?, updated_at = now()
                         where id = ? and (tenant_id is null or tenant_id = ?)
                        """, owner.tenantId(), depositRecordId, owner.tenantId()) != 1) {
            throw new IllegalStateException("deposit record belongs to another tenant");
        }
        if (jdbc.update("""
                        update ledger_balance
                           set tenant_id = ?, updated_at = now()
                         where chain = ? and asset_symbol = ?
                           and lower(account_id) = lower(?)
                           and (tenant_id is null or tenant_id = ?)
                        """, owner.tenantId(), chain, event.assetSymbol(), accountId,
                owner.tenantId()) != 1) {
            throw new IllegalStateException("ledger balance belongs to another tenant");
        }

        UUID custodyDepositId = jdbc.queryForObject("""
                        insert into custody_deposit(
                            id, tenant_id, custody_address_id, deposit_record_id, chain,
                            asset_symbol, tx_hash, log_index, amount, status, credited_at)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, 'CONFIRMED', now())
                        on conflict (tenant_id, deposit_record_id) do update set
                            status = excluded.status,
                            credited_at = excluded.credited_at,
                            updated_at = now()
                        returning id
                        """, UUID.class, UUID.randomUUID(), owner.tenantId(), owner.addressId(), depositRecordId,
                chain, event.assetSymbol(), event.txId(), logIndex, event.amount());
        if (custodyDepositId == null) {
            throw new IllegalStateException("failed to persist custody deposit");
        }

        String referenceId = chain + ":" + event.txId() + ":" + logIndex
                + ":credit:" + depositProjection.creditGeneration();
        jdbc.update("""
                        insert into custody_ledger_entry(
                            id, tenant_id, custody_address_id, chain, asset_symbol, account_id,
                            entry_type, direction, amount, reference_type, reference_id)
                        values (?, ?, ?, ?, ?, ?, 'DEPOSIT', 'CREDIT', ?, 'DEPOSIT', ?)
                        on conflict (tenant_id, entry_type, reference_type, reference_id) do nothing
                        """, UUID.randomUUID(), owner.tenantId(), owner.addressId(), chain,
                event.assetSymbol(), accountId, event.amount(), referenceId);

        BigDecimal deficitApplied = applyOpenDeficits(
                owner, event, accountId, referenceId, event.amount());

        UUID eventId = UUID.randomUUID();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("depositId", custodyDepositId);
        data.put("subject", owner.subject());
        data.put("chain", chain);
        data.put("asset", event.assetSymbol());
        data.put("address", owner.address());
        data.put("memo", owner.memo());
        data.put("purpose", owner.purpose());
        data.put("amount", event.amount());
        data.put("txHash", event.txId());
        data.put("logIndex", logIndex);
        data.put("blockHeight", event.blockHeight());
        data.put("blockHash", event.blockHash());
        data.put("confirmations", event.confirmations());
        data.put("creditGeneration", depositProjection.creditGeneration());
        data.put("appliedToReorgDeficit", deficitApplied);
        data.put("availableAmount", event.amount().subtract(deficitApplied));
        data.put("confirmedAt", Instant.now());
        String payload = json(Map.of(
                "id", eventId,
                "type", EVENT_TYPE,
                "createdAt", Instant.now(),
                "data", data));

        repository.insertEventWithDeliveries(
                eventId, owner.tenantId(), EVENT_TYPE, "DEPOSIT", referenceId, payload,
                "API".equals(owner.source()));
    }

    private BigDecimal applyOpenDeficits(AddressOwner owner, DepositEvent event, String accountId,
                                         String creditReference, BigDecimal creditAmount) {
        List<Deficit> deficits = jdbc.query("""
                        select id, deficit_amount, recovered_amount
                          from custody_reorg_deficit
                         where tenant_id = ? and chain = ? and asset_symbol = ? and account_id = ?
                           and status = 'OPEN'
                         order by created_at, id
                         for update
                        """, (rs, rowNum) -> new Deficit(
                        rs.getObject("id", UUID.class), rs.getBigDecimal("deficit_amount"),
                        rs.getBigDecimal("recovered_amount")),
                owner.tenantId(), event.chainType().name(), event.assetSymbol(), accountId);
        BigDecimal remaining = creditAmount;
        BigDecimal appliedTotal = BigDecimal.ZERO;
        for (Deficit deficit : deficits) {
            if (remaining.signum() <= 0) {
                break;
            }
            BigDecimal outstanding = deficit.amount().subtract(deficit.recovered());
            BigDecimal applied = outstanding.min(remaining);
            if (applied.signum() <= 0) {
                continue;
            }
            if (jdbc.update("""
                            update ledger_balance
                               set available_balance = available_balance - ?,
                                   total_balance = total_balance - ?, updated_at = now()
                             where tenant_id = ? and chain = ? and asset_symbol = ? and account_id = ?
                               and available_balance >= ? and total_balance >= ?
                            """, applied, applied, owner.tenantId(), event.chainType().name(),
                    event.assetSymbol(), accountId, applied, applied) != 1) {
                throw new IllegalStateException("unable to apply deposit to reorg deficit");
            }
            jdbc.update("""
                            update custody_reorg_deficit
                               set recovered_amount = recovered_amount + ?,
                                   status = case when recovered_amount + ? >= deficit_amount
                                       then 'RECOVERED' else 'OPEN' end,
                                   updated_at = now()
                             where id = ? and tenant_id = ?
                            """, applied, applied, deficit.id(), owner.tenantId());
            jdbc.update("""
                            insert into custody_ledger_entry(
                                id, tenant_id, custody_address_id, chain, asset_symbol, account_id,
                                entry_type, direction, amount, reference_type, reference_id)
                            values (?, ?, ?, ?, ?, ?, 'REORG_DEFICIT_RECOVERY', 'DEBIT', ?, 'REORG_DEFICIT', ?)
                            on conflict (tenant_id, entry_type, reference_type, reference_id) do nothing
                            """, UUID.randomUUID(), owner.tenantId(), owner.addressId(),
                    event.chainType().name(), event.assetSymbol(), accountId, applied,
                    creditReference + ":deficit:" + deficit.id());
            remaining = remaining.subtract(applied);
            appliedTotal = appliedTotal.add(applied);
        }
        return appliedTotal;
    }
    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize custody deposit event", e);
        }
    }

    private record AddressOwner(
            UUID addressId,
            UUID tenantId,
            String subject,
            String address,
            String memo,
            String source,
            String purpose
    ) {
    }
    private record DepositProjection(long id, int creditGeneration) {
    }
    private record Deficit(UUID id, BigDecimal amount, BigDecimal recovered) {
    }
}
