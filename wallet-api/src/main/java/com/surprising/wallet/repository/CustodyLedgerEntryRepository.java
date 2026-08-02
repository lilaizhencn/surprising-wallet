package com.surprising.wallet.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.UUID;

/** custody_ledger_entry 单表仓储。 */
@Repository
public class CustodyLedgerEntryRepository {
    /** JDBC 模板。 */
    private final JdbcTemplate jdbc;

    /** 构造托管流水单表仓储。 */
    public CustodyLedgerEntryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 幂等写入一条托管流水。 */
    public int insertIfAbsent(UUID id, UUID tenantId, UUID custodyAddressId,
                              String chain, String assetSymbol, String accountId,
                              String entryType, String direction, BigDecimal amount,
                              String referenceType, String referenceId) {
        return jdbc.update("""
                insert into custody_ledger_entry(
                    id, tenant_id, custody_address_id, chain, asset_symbol,
                    account_id, entry_type, direction, amount, reference_type, reference_id)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (tenant_id, entry_type, reference_type, reference_id) do nothing
                """, id, tenantId, custodyAddressId, chain, assetSymbol, accountId,
                entryType, direction, amount, referenceType, referenceId);
    }
}
