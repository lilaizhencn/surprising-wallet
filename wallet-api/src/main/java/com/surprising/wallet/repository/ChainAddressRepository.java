package com.surprising.wallet.repository;

import com.surprising.wallet.common.chain.ChainAddressRecord;
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

    /** 按主键查询链地址。 */
    public Optional<Map<String, Object>> findById(long id) {
        return jdbc.queryForList("""
                select id, tenant_id, chain, asset_symbol, account_id, user_id, biz, address_index,
                       address, owner_address, derivation_path, wallet_role, enabled
                  from chain_address where id = ?
                """, id).stream().findFirst();
    }

    /** 查询租户在指定链上启用的地址文本。 */
    public List<String> listEnabledAddresses(UUID tenantId, String chain) {
        return jdbc.queryForList("""
                select address
                  from chain_address
                 where tenant_id = ? and chain = ? and enabled = true
                """, String.class, tenantId, chain);
    }

    /** 查询指定链上的全部启用地址文本。 */
    public List<String> listEnabledAddresses(String chain) {
        return jdbc.queryForList("""
                select lower(address) from chain_address where chain = ? and enabled = true
                """, String.class, chain);
    }

    /** 写入或更新链地址。 */
    public int upsert(ChainAddressRecord address) {
        return jdbc.update("""
                insert into chain_address(
                    tenant_id, chain, asset_symbol, account_id, user_id, biz, address_index, address,
                    owner_address, derivation_path, wallet_role, enabled, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now(), now())
                on conflict (chain, asset_symbol, user_id, biz, address_index, wallet_role) do update set
                    tenant_id = excluded.tenant_id, account_id = excluded.account_id, address = excluded.address,
                    owner_address = excluded.owner_address, derivation_path = excluded.derivation_path,
                    enabled = excluded.enabled, updated_at = excluded.updated_at
                """, address.getTenantId(), address.getChain(), address.getAssetSymbol(), address.getAccountId(),
                address.getUserId(), address.getBiz(), address.getAddressIndex(), address.getAddress(),
                address.getOwnerAddress(), address.getDerivationPath(), address.getWalletRole(), address.getEnabled());
    }

    /** 按业务主键查询链地址。 */
    public Optional<ChainAddressRecord> find(String chain, String assetSymbol, long userId, int biz,
                                             long addressIndex, String walletRole) {
        return jdbc.query("""
                select id, tenant_id, chain, asset_symbol, account_id, user_id, biz, address_index, address,
                       owner_address, derivation_path, wallet_role, enabled
                  from chain_address
                 where chain = ? and asset_symbol = ? and user_id = ? and biz = ?
                   and address_index = ? and wallet_role = ?
                """, (rs, rowNum) -> map(rs), chain, assetSymbol, userId, biz, addressIndex, walletRole)
                .stream().findFirst();
    }

    /** 查询默认热钱包候选地址。 */
    public List<ChainAddressRecord> listDefaultHot(String chain, String assetSymbol,
                                                   long userId, int biz, String walletRole) {
        return jdbc.query("""
                select id, tenant_id, chain, asset_symbol, account_id, user_id, biz, address_index, address,
                       owner_address, derivation_path, wallet_role, enabled
                  from chain_address
                 where chain = ? and asset_symbol = ? and user_id = ? and biz = ? and wallet_role = ?
                 order by address_index, id
                """, (rs, rowNum) -> map(rs), chain, assetSymbol, userId, biz, walletRole);
    }

    /** 查询保留的热钱包命名空间地址。 */
    public List<ChainAddressRecord> listReservedHot(String chain, long userId, int biz) {
        return jdbc.query("""
                select id, tenant_id, chain, asset_symbol, account_id, user_id, biz, address_index, address,
                       owner_address, derivation_path, wallet_role, enabled
                  from chain_address where chain = ? and user_id = ? and biz = ?
                 order by asset_symbol, wallet_role, address_index, id
                """, (rs, rowNum) -> map(rs), chain, userId, biz);
    }

    /** 查询指定链资产下的启用地址。 */
    public List<ChainAddressRecord> listEnabled(String chain, String assetSymbol) {
        return jdbc.query("""
                select id, tenant_id, chain, asset_symbol, account_id, user_id, biz, address_index, address,
                       owner_address, derivation_path, wallet_role, enabled
                  from chain_address where chain = ? and asset_symbol = ? and enabled = true order by id
                """, (rs, rowNum) -> map(rs), chain, assetSymbol);
    }

    /** 查询指定链上全部启用地址。 */
    public List<ChainAddressRecord> listEnabled(String chain) {
        return jdbc.query("""
                select id, tenant_id, chain, asset_symbol, account_id, user_id, biz, address_index, address,
                       owner_address, derivation_path, wallet_role, enabled
                  from chain_address where chain = ? and enabled = true order by id
                """, (rs, rowNum) -> map(rs), chain);
    }

    /** 按地址查询启用链地址。 */
    public Optional<ChainAddressRecord> findEnabledByAddress(String chain, String address) {
        return jdbc.query("""
                select id, tenant_id, chain, asset_symbol, account_id, user_id, biz, address_index, address,
                       owner_address, derivation_path, wallet_role, enabled
                  from chain_address where chain = ? and address = ? and enabled = true
                """, (rs, rowNum) -> map(rs), chain, address).stream().findFirst();
    }

    /** 按链和资产地址查询启用链地址。 */
    public Optional<ChainAddressRecord> findEnabledByAddress(String chain, String assetSymbol, String address) {
        return jdbc.query("""
                select id, tenant_id, chain, asset_symbol, account_id, user_id, biz, address_index, address,
                       owner_address, derivation_path, wallet_role, enabled
                  from chain_address where chain = ? and asset_symbol = ? and address = ? and enabled = true
                """, (rs, rowNum) -> map(rs), chain, assetSymbol, address).stream().findFirst();
    }

    /** 按租户、链和资产地址查询启用链地址。 */
    public Optional<ChainAddressRecord> findEnabledByTenantAndAddress(UUID tenantId, String chain,
                                                                        String assetSymbol, String address) {
        return jdbc.query("""
                select id, tenant_id, chain, asset_symbol, account_id, user_id, biz, address_index, address,
                       owner_address, derivation_path, wallet_role, enabled
                  from chain_address
                 where tenant_id = ? and chain = ? and asset_symbol = ? and address = ? and enabled = true
                """, (rs, rowNum) -> map(rs), tenantId, chain, assetSymbol, address).stream().findFirst();
    }

    /** 按租户、链和地址查询启用链地址。 */
    public Optional<ChainAddressRecord> findEnabledByTenantAndAddress(UUID tenantId, String chain, String address) {
        return jdbc.query("""
                select id, tenant_id, chain, asset_symbol, account_id, user_id, biz, address_index, address,
                       owner_address, derivation_path, wallet_role, enabled
                  from chain_address
                 where tenant_id = ? and chain = ? and address = ? and enabled = true
                """, (rs, rowNum) -> map(rs), tenantId, chain, address).stream().findFirst();
    }

    /** 查询最大启用地址索引。 */
    public Optional<Long> findMaxIndex(String chain, String assetSymbol, long userId, int biz, String walletRole) {
        return Optional.ofNullable(jdbc.queryForObject("""
                select max(address_index) from chain_address
                 where chain = ? and asset_symbol = ? and user_id = ? and biz = ?
                   and wallet_role = ? and enabled = true
                """, Long.class, chain, assetSymbol, userId, biz, walletRole));
    }

    /** 将数据库行映射为链地址记录。 */
    private static ChainAddressRecord map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return ChainAddressRecord.builder()
                .id(rs.getLong("id")).tenantId(rs.getObject("tenant_id", UUID.class))
                .chain(rs.getString("chain")).assetSymbol(rs.getString("asset_symbol"))
                .accountId(rs.getString("account_id")).userId(rs.getLong("user_id"))
                .biz(rs.getInt("biz")).addressIndex(rs.getLong("address_index"))
                .address(rs.getString("address")).ownerAddress(rs.getString("owner_address"))
                .derivationPath(rs.getString("derivation_path")).walletRole(rs.getString("wallet_role"))
                .enabled(rs.getBoolean("enabled")).build();
    }

    /** 将单表查询结果转换为链地址模型。 */
    public static ChainAddressRecord mapRow(Map<String, Object> row) {
        return ChainAddressRecord.builder()
                .id(number(row.get("id"), Long.class)).tenantId((UUID) row.get("tenant_id"))
                .chain((String) row.get("chain")).assetSymbol((String) row.get("asset_symbol"))
                .accountId((String) row.get("account_id")).userId(number(row.get("user_id"), Long.class))
                .biz(number(row.get("biz"), Integer.class)).addressIndex(number(row.get("address_index"), Long.class))
                .address((String) row.get("address")).ownerAddress((String) row.get("owner_address"))
                .derivationPath((String) row.get("derivation_path")).walletRole((String) row.get("wallet_role"))
                .enabled((Boolean) row.get("enabled")).build();
    }

    /** 安全转换单表数字字段。 */
    private static <T extends Number> T number(Object value, Class<T> type) {
        if (value == null) return null;
        Number number = (Number) value;
        if (type == Long.class) return type.cast(number.longValue());
        return type.cast(number.intValue());
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

    /** 查询指定地址或账户关联的租户集合。 */
    public List<UUID> listTenantIds(String chain, String accountId, String address) {
        return jdbc.queryForList("""
                select distinct tenant_id from chain_address
                 where tenant_id is not null and enabled = true and chain = ?
                   and (lower(account_id) = lower(?) or lower(address) = lower(?)) limit 2
                """, UUID.class, chain, accountId, address);
    }

    /** 查询租户指定链上的地址记录。 */
    public List<Map<String, Object>> listByTenantAndChain(UUID tenantId, String chain) {
        return jdbc.queryForList("""
                select id, chain, asset_symbol, account_id, address, owner_address, user_id, biz,
                       address_index, wallet_role, enabled, tenant_id
                  from chain_address where tenant_id = ? and chain = ?
                """, tenantId, chain);
    }

    /** 查询指定链的启用地址字段。 */
    public List<Map<String, Object>> listEnabledByChain(String chain) {
        return jdbc.queryForList("""
                select id, tenant_id, chain, asset_symbol, account_id, user_id, biz, address_index,
                       address, owner_address, wallet_role, enabled
                  from chain_address where chain = ? and enabled = true
                """, chain);
    }

    /** 将链地址归属到租户。 */
    public int assignTenant(UUID tenantId, long chainAddressId) {
        return jdbc.update("update chain_address set tenant_id = ?, updated_at = now() where id = ?",
                tenantId, chainAddressId);
    }

}
