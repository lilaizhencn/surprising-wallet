package com.surprising.wallet.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** custody_address 单表仓储。 */
@Repository
public class CustodyAddressRepository {
    /** JDBC 模板。 */
    private final JdbcTemplate jdbc;

    /** 构造托管地址仓储。 */
    public CustodyAddressRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 判断指定租户的托管地址是否来自 API。 */
    public boolean hasApiSource(UUID tenantId, UUID custodyAddressId) {
        Boolean result = jdbc.queryForObject("""
                select exists(
                    select 1 from custody_address
                     where tenant_id = ? and id = ? and source = 'API')
                """, Boolean.class, tenantId, custodyAddressId);
        return Boolean.TRUE.equals(result);
    }

    /** 查询租户指定托管地址的单表字段。 */
    public List<Map<String, Object>> listByTenantAndAddress(UUID tenantId, String address,
                                                             String preferredChain) {
        return jdbc.queryForList("""
                select id, chain_address_id, chain, created_at
                  from custody_address
                 where tenant_id = ? and lower(address) = lower(?)
                 order by case when chain = ? then 0 else 1 end, created_at, id
                """, tenantId, address, preferredChain);
    }

    /** 查询租户托管地址及其链地址主键。 */
    public Optional<Map<String, Object>> findByTenantAndId(UUID tenantId, UUID custodyAddressId) {
        return jdbc.queryForList("""
                select id, tenant_id, chain_address_id, chain, network, address, status,
                       derivation_subject, source
                  from custody_address
                where tenant_id = ? and id = ?
                """, tenantId, custodyAddressId).stream().findFirst();
    }

    /** 查询租户全部托管地址字段，供服务层组合链地址和账本数据。 */
    public List<Map<String, Object>> listByTenant(UUID tenantId) {
        return jdbc.queryForList("""
                select id, tenant_id, chain_address_id, chain, network, address,
                       memo, subject, label, status, source
                  from custody_address
                 where tenant_id = ?
                """, tenantId);
    }

    /** 查询全部托管地址字段，供开发水龙头服务组合租户和链配置。 */
    public List<Map<String, Object>> listAll() {
        return jdbc.queryForList("""
                select id, tenant_id, chain, network, address, status, source
                  from custody_address
                """);
    }
}
