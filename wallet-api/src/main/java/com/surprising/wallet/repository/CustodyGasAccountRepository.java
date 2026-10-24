package com.surprising.wallet.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** custody_gas_account 单表仓储。 */
@Repository
public class CustodyGasAccountRepository {
    public record AccountingAccount(UUID id, UUID custodyAddressId, String chain, String nativeSymbol, String status) { }

    public java.util.Optional<AccountingAccount> lockForChain(UUID tenantId, String chain) {
        return jdbc.query("""
                select id, custody_address_id, chain, native_symbol, status from custody_gas_account
                 where tenant_id=? and chain=? for update
                """, CustodyGasAccountRepository::mapAccountingAccount, tenantId, chain).stream().findFirst();
    }

    public java.util.Optional<AccountingAccount> lockById(UUID tenantId, UUID id) {
        return jdbc.query("""
                select id, custody_address_id, chain, native_symbol, status from custody_gas_account
                 where tenant_id=? and id=? for update
                """, CustodyGasAccountRepository::mapAccountingAccount, tenantId, id).stream().findFirst();
    }

    private static AccountingAccount mapAccountingAccount(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new AccountingAccount(rs.getObject("id", UUID.class), rs.getObject("custody_address_id", UUID.class),
                rs.getString("chain"), rs.getString("native_symbol"), rs.getString("status"));
    }
    public java.util.Optional<Reference> findReference(UUID tenantId, UUID id) {
        return jdbc.query("""
                select custody_address_id, chain, native_symbol from custody_gas_account
                 where tenant_id = ? and id = ?
                """, (rs, rowNum) -> new Reference(rs.getObject("custody_address_id", UUID.class),
                rs.getString("chain"), rs.getString("native_symbol")), tenantId, id).stream().findFirst();
    }

    public record Reference(UUID custodyAddressId, String chain, String nativeSymbol) { }
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

    /** 查询租户指定链上的启用 Gas 账户。 */
    public List<Map<String, Object>> listActiveByTenantAndChain(UUID tenantId, String chain) {
        return jdbc.queryForList("""
                select id, tenant_id, custody_address_id, chain, network, status
                  from custody_gas_account
                 where tenant_id = ? and chain = ? and status = 'ACTIVE'
                """, tenantId, chain);
    }

    /** 查询租户全部 Gas 账户单表字段。 */
    public List<Map<String, Object>> listByTenant(UUID tenantId) {
        return jdbc.queryForList("""
                select id, tenant_id, custody_address_id, chain, network, native_symbol,
                       low_balance_threshold, status, created_by, created_at, updated_at
                  from custody_gas_account where tenant_id = ? order by chain, id
                """, tenantId);
    }

    /** 按租户和链查询 Gas 账户单表字段。 */
    public List<Map<String, Object>> listByTenantAndChain(UUID tenantId, String chain) {
        return jdbc.queryForList("""
                select id, tenant_id, custody_address_id, chain, network, native_symbol,
                       low_balance_threshold, status, created_by, created_at, updated_at
                  from custody_gas_account where tenant_id = ? and chain = ? order by id
                """, tenantId, chain);
    }

    /** 按主键查询 Gas 账户。 */
    public java.util.Optional<Map<String, Object>> findById(UUID tenantId, UUID id) {
        return jdbc.queryForList("""
                select id, tenant_id, custody_address_id, chain, network, native_symbol,
                       low_balance_threshold, status, created_by, created_at, updated_at
                  from custody_gas_account where tenant_id = ? and id = ?
                """, tenantId, id).stream().findFirst();
    }

    /** 按租户和链查询 Gas 账户。 */
    public java.util.Optional<Map<String, Object>> findByChain(UUID tenantId, String chain) {
        return listByTenantAndChain(tenantId, chain).stream().findFirst();
    }

    /** 创建 Gas 账户。 */
    public int insert(UUID id, UUID tenantId, UUID custodyAddressId, String chain, String network,
                      String nativeSymbol, java.math.BigDecimal lowBalanceThreshold, UUID createdBy) {
        return jdbc.update("""
                insert into custody_gas_account(id, tenant_id, custody_address_id, chain, network,
                    native_symbol, low_balance_threshold, status, created_by)
                values (?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?)
                """, id, tenantId, custodyAddressId, chain, network, nativeSymbol,
                lowBalanceThreshold, createdBy);
    }

    /** 更新 Gas 账户阈值和状态。 */
    public int update(UUID tenantId, UUID id, java.math.BigDecimal threshold, String status) {
        return jdbc.update("""
                update custody_gas_account set low_balance_threshold = ?, status = ?, updated_at = now()
                 where tenant_id = ? and id = ?
                """, threshold, status, tenantId, id);
    }

    public boolean existsForAddress(UUID tenantId, UUID custodyAddressId) {
        return !jdbc.queryForList("""
                select id from custody_gas_account where tenant_id = ? and custody_address_id = ? limit 1
                """, UUID.class, tenantId, custodyAddressId).isEmpty();
    }

}
