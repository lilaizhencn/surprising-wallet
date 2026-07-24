package com.surprising.wallet.custody.observer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.surprising.wallet.service.dao.DepositReorgObserver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.surprising.wallet.custody.repository.CustodyRepository;

@Component
public class CustodyDepositReorgObserver implements DepositReorgObserver {
    private static final String EVENT_TYPE = "DEPOSIT.REORGED";
    private final JdbcTemplate jdbc;
    private final CustodyRepository repository;
    private final ObjectMapper objectMapper;

    public CustodyDepositReorgObserver(JdbcTemplate jdbc, CustodyRepository repository,
                                       ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Override
    public void onDepositReorged(ReorgedDeposit deposit) {
        List<AddressProjection> projections = jdbc.query("""
                        select d.id as custody_deposit_id, d.custody_address_id,
                               a.subject, a.address, a.memo, a.source
                          from custody_deposit d
                          join custody_address a on a.id = d.custody_address_id
                         where d.tenant_id = ? and d.deposit_record_id = ?
                         for update
                        """, (rs, rowNum) -> new AddressProjection(
                        rs.getObject("custody_deposit_id", UUID.class),
                        rs.getObject("custody_address_id", UUID.class),
                        rs.getString("subject"), rs.getString("address"),
                        rs.getString("memo"), rs.getString("source")),
                deposit.tenantId(), deposit.depositRecordId());
        if (projections.isEmpty()) {
            throw new IllegalStateException("credited custody deposit projection is missing");
        }
        AddressProjection projection = projections.getFirst();
        jdbc.update("""
                        update custody_deposit
                           set status = 'REORGED', updated_at = now()
                         where id = ? and tenant_id = ?
                        """, projection.depositId(), deposit.tenantId());

        String referenceId = reference(deposit, "reorg");
        jdbc.update("""
                        insert into custody_ledger_entry(
                            id, tenant_id, custody_address_id, chain, asset_symbol, account_id,
                            entry_type, direction, amount, reference_type, reference_id)
                        values (?, ?, ?, ?, ?, ?, 'DEPOSIT_REORG_REVERSAL', 'DEBIT', ?, 'DEPOSIT', ?)
                        on conflict (tenant_id, entry_type, reference_type, reference_id) do nothing
                        """, UUID.randomUUID(), deposit.tenantId(), projection.addressId(),
                deposit.chain(), deposit.assetSymbol(), deposit.accountId(), deposit.amount(), referenceId);

        UUID deficitId = null;
        if (deposit.deficitAmount().signum() > 0) {
            deficitId = jdbc.queryForObject("""
                            insert into custody_reorg_deficit(
                                id, tenant_id, custody_address_id, deposit_record_id, chain,
                                asset_symbol, account_id, original_amount, deficit_amount)
                            values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                            on conflict (tenant_id, deposit_record_id) do update set
                                original_amount = excluded.original_amount,
                                deficit_amount = excluded.deficit_amount,
                                recovered_amount = 0,
                                status = 'OPEN', updated_at = now()
                            returning id
                            """, UUID.class, UUID.randomUUID(), deposit.tenantId(), projection.addressId(),
                    deposit.depositRecordId(), deposit.chain(), deposit.assetSymbol(),
                    deposit.accountId(), deposit.amount(), deposit.deficitAmount());
        }

        UUID eventId = UUID.randomUUID();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("depositId", projection.depositId());
        data.put("subject", projection.subject());
        data.put("chain", deposit.chain());
        data.put("asset", deposit.assetSymbol());
        data.put("address", projection.address());
        data.put("memo", projection.memo());
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
                eventId, deposit.tenantId(), EVENT_TYPE, "DEPOSIT", referenceId,
                json(Map.of("id", eventId, "type", EVENT_TYPE,
                        "createdAt", Instant.now(), "data", data)),
                "API".equals(projection.source()));
        repository.audit(deposit.tenantId(), "SYSTEM", "deposit-finality",
                "DEPOSIT.REORG", "CUSTODY_DEPOSIT", projection.depositId().toString(), null,
                json(data));
    }
    private static String reference(ReorgedDeposit deposit, String action) {
        return deposit.chain() + ":" + deposit.txHash() + ":" + deposit.logIndex()
                + ":" + action + ":" + deposit.creditGeneration();
    }
    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize deposit reorg event", e);
        }
    }

    private record AddressProjection(UUID depositId, UUID addressId, String subject,
                                     String address, String memo, String source) {
    }
}
