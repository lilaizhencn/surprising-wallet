package com.surprising.wallet.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** chain_address 单表仓储。 */
@Repository
public class ChainAddressRepository {
    /** JDBC 模板。 */
    private final JdbcTemplate jdbc;

    /** 构造链地址仓储。 */
    public ChainAddressRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 判断指定链是否存在地址数据。 */
    public boolean existsByChain(String chain) {
        return !jdbc.queryForList("""
                select id from chain_address where upper(chain) = upper(?) limit 1
                """, chain).isEmpty();
    }

    /** 判断指定链和资产是否存在地址数据。 */
    public boolean existsByChainAndAsset(String chain, String symbol) {
        return !jdbc.queryForList("""
                select 1 from chain_address
                 where upper(chain) = upper(?) and upper(asset_symbol) = upper(?)
                 limit 1
                """, chain, symbol).isEmpty();
    }

    /** 查询租户链地址。 */
    public Optional<Map<String, Object>> findByTenantAndId(UUID tenantId, long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, chain, asset_symbol, account_id, user_id, biz, address_index,
                       address, owner_address, derivation_path, wallet_role, enabled, tenant_id
                  from chain_address
                 where tenant_id = ? and id = ?
                """, tenantId, id);
        return rows.stream().findFirst();
    }

    /** 查询租户在指定链上启用的地址文本。 */
    public List<String> listEnabledAddresses(UUID tenantId, String chain) {
        return jdbc.queryForList("""
                select address
                  from chain_address
                 where tenant_id = ? and chain = ? and enabled = true
                """, String.class, tenantId, chain);
    }

    /** 查询租户的全部链地址字段，供服务层在 Java 中完成关联组合。 */
    public List<Map<String, Object>> listByTenant(UUID tenantId) {
        return jdbc.queryForList("""
                select id, chain, account_id, user_id, biz, address_index,
                       address, wallet_role, enabled, tenant_id
                  from chain_address
                 where tenant_id = ?
                """, tenantId);
    }

    /** 判断租户是否拥有指定链上的启用地址。 */
    public boolean existsEnabledAddress(UUID tenantId, String chain, String address) {
        return !jdbc.queryForList("""
                select id from chain_address
                 where tenant_id = ? and chain = ? and lower(address) = lower(?) and enabled = true
                 limit 1
                """, tenantId, chain, address).isEmpty();
    }

}
