package com.surprising.wallet.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** custody_gas_account 单表仓储。 */
@Repository
public class CustodyGasAccountRepository {
    /** JDBC 模板。 */
    private final JdbcTemplate jdbc;

    /** 构造 Gas 账户仓储。 */
    public CustodyGasAccountRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 查询租户的 Gas 托管地址主键。 */
    public List<UUID> listCustodyAddressIds(UUID tenantId) {
        return jdbc.queryForList("""
                select custody_address_id
                  from custody_gas_account
                 where tenant_id = ?
                """, UUID.class, tenantId);
    }

    /** 查询启用的 Gas 账户关联字段。 */
    public List<Map<String, Object>> listActive() {
        return jdbc.queryForList("""
                select tenant_id, custody_address_id
                  from custody_gas_account
                 where status = 'ACTIVE'
                """);
    }
}
