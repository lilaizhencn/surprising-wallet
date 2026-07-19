package com.surprising.wallet.jobs.custody;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.surprising.wallet.common.chain.DepositEvent;
import com.surprising.wallet.service.dao.DepositCreditObserver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class CustodyDepositCreditObserver implements DepositCreditObserver {
    private static final String EVENT_TYPE = "DEPOSIT.CONFIRMED";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final CustodyRepository repository;

    public CustodyDepositCreditObserver(JdbcTemplate jdbc, ObjectMapper objectMapper,
                                        CustodyRepository repository) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.repository = repository;
    }

    @Override
    public void onDepositCredited(DepositEvent event, long logIndex, String accountId) {
        String chain = event.chainType().name();
        List<AddressOwner> owners = jdbc.query("""
                        select c.id as custody_address_id, c.tenant_id, c.external_reference,
                               c.address, c.memo
                          from custody_address c
                          join chain_address base on base.id = c.chain_address_id
                         where c.chain = ?
                           and exists (
                               select 1
                                 from chain_address related
                                where related.chain = base.chain
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
                        rs.getString("external_reference"),
                        rs.getString("address"),
                        rs.getString("memo")),
                chain, accountId, event.toAddress());
        if (owners.isEmpty()) {
            return;
        }
        if (owners.size() > 1) {
            throw new IllegalStateException("deposit address maps to more than one custody tenant");
        }
        AddressOwner owner = owners.getFirst();
        Long depositRecordId = jdbc.queryForObject("""
                        select id from deposit_record
                         where chain = ? and tx_hash = ? and log_index = ?
                        """, Long.class, chain, event.txId(), logIndex);
        if (depositRecordId == null) {
            throw new IllegalStateException("credited deposit record is missing");
        }

        UUID custodyDepositId = jdbc.queryForObject("""
                        insert into custody_deposit(
                            id, tenant_id, custody_address_id, deposit_record_id, chain,
                            asset_symbol, tx_hash, log_index, amount, status, credited_at)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, 'CONFIRMED', now())
                        on conflict (tenant_id, deposit_record_id) do update set
                            status = excluded.status,
                            credited_at = coalesce(custody_deposit.credited_at, excluded.credited_at),
                            updated_at = now()
                        returning id
                        """, UUID.class, UUID.randomUUID(), owner.tenantId(), owner.addressId(), depositRecordId,
                chain, event.assetSymbol(), event.txId(), logIndex, event.amount());
        if (custodyDepositId == null) {
            throw new IllegalStateException("failed to persist custody deposit");
        }

        String referenceId = chain + ":" + event.txId() + ":" + logIndex;
        jdbc.update("""
                        insert into custody_ledger_entry(
                            id, tenant_id, custody_address_id, chain, asset_symbol, account_id,
                            entry_type, direction, amount, reference_type, reference_id)
                        values (?, ?, ?, ?, ?, ?, 'DEPOSIT', 'CREDIT', ?, 'DEPOSIT', ?)
                        on conflict (tenant_id, entry_type, reference_type, reference_id) do nothing
                        """, UUID.randomUUID(), owner.tenantId(), owner.addressId(), chain,
                event.assetSymbol(), accountId, event.amount(), referenceId);

        UUID eventId = UUID.randomUUID();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("depositId", custodyDepositId);
        data.put("externalReference", owner.externalReference());
        data.put("chain", chain);
        data.put("asset", event.assetSymbol());
        data.put("address", owner.address());
        data.put("memo", owner.memo());
        data.put("amount", event.amount());
        data.put("txHash", event.txId());
        data.put("logIndex", logIndex);
        data.put("blockHeight", event.blockHeight());
        data.put("confirmations", event.confirmations());
        data.put("confirmedAt", Instant.now());
        String payload = json(Map.of(
                "id", eventId,
                "type", EVENT_TYPE,
                "createdAt", Instant.now(),
                "data", data));

        repository.insertEventWithDeliveries(
                eventId, owner.tenantId(), EVENT_TYPE, "DEPOSIT", referenceId, payload);
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
            String externalReference,
            String address,
            String memo
    ) {
    }
}
