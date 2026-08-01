package com.surprising.wallet.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

/** custody_deposit 单表仓储。 */
@Repository
public class CustodyDepositRepository {
    /** JDBC 模板。 */
    private final JdbcTemplate jdbc;

    /** 构造托管充值单表仓储。 */
    public CustodyDepositRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 查询已确认充值的入账时间。 */
    public Timestamp findConfirmedAt(UUID tenantId, UUID custodyAddressId, String chain,
                                     String assetSymbol, String txHash) {
        List<Timestamp> rows = jdbc.queryForList("""
                select credited_at
                  from custody_deposit
                 where tenant_id = ? and custody_address_id = ?
                   and chain = ? and asset_symbol = ?
                   and lower(tx_hash) = lower(?) and status = 'CONFIRMED'
                 limit 1
                """, Timestamp.class, tenantId, custodyAddressId, chain, assetSymbol, txHash);
        return rows.stream().findFirst().orElse(null);
    }
}
