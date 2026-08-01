package com.surprising.wallet.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * chain_asset 单表仓储。
 */
@Repository
public class ChainAssetRepository {
    /** JDBC 模板。 */
    private final JdbcTemplate jdbc;

    /** 构造链资产仓储。 */
    public ChainAssetRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 查询非原生资产概览。 */
    public List<Map<String, Object>> listNonNativeOverview() {
        return jdbc.queryForList("""
                select chain, symbol, contract_address, active
                  from chain_asset
                 where native_asset = false
                 order by chain, symbol
                """);
    }

    /** 查询全部处于启用状态的链资产。 */
    public List<Map<String, Object>> listActive() {
        return jdbc.queryForList("""
                select id, chain, symbol, asset_kind, contract_address, decimals,
                       native_asset, active, min_transfer, min_withdraw, created_at, updated_at
                  from chain_asset
                 where active = true
                 order by chain, symbol
                """);
    }

    /** 查询链资产。 */
    public Optional<Map<String, Object>> find(String chain, String symbol) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select chain, symbol, contract_address, decimals, active, native_asset
                  from chain_asset where chain = ? and symbol = ?
                """, chain, symbol);
        return rows.stream().findFirst();
    }

    /** 查询指定链上处于启用状态的资产。 */
    public Optional<Map<String, Object>> findActive(String chain, String symbol) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select chain, symbol, decimals, active, native_asset, contract_address
                  from chain_asset
                 where chain = ? and symbol = ? and active = true
                """, chain, symbol);
        return rows.stream().findFirst();
    }

    /** 判断链资产是否存在。 */
    public boolean exists(String chain, String symbol) {
        return find(chain, symbol).isPresent();
    }

    /** 创建或更新非原生链资产。 */
    public int upsertNonNative(String chain, String symbol, String assetKind,
                               String contractAddress, int decimals, boolean active,
                               Object minTransfer, Object minWithdraw) {
        return jdbc.update("""
                insert into chain_asset(
                    chain, symbol, asset_kind, contract_address, decimals, native_asset,
                    active, min_transfer, min_withdraw, updated_at)
                values (?, ?, ?, ?, ?, false, ?, ?, ?, now())
                on conflict (chain, symbol) do update
                   set asset_kind = excluded.asset_kind, contract_address = excluded.contract_address,
                       decimals = excluded.decimals, native_asset = false, active = excluded.active,
                       min_transfer = excluded.min_transfer, min_withdraw = excluded.min_withdraw,
                       updated_at = now()
                """, chain, symbol, assetKind, contractAddress, decimals, active,
                minTransfer, minWithdraw);
    }

    /** 修改非原生资产启用状态。 */
    public int updateActive(String chain, String symbol, boolean active) {
        return jdbc.update("""
                update chain_asset set active = ?, updated_at = now()
                 where chain = ? and symbol = ? and native_asset = false
                """, active, chain, symbol);
    }

    /** 删除非原生资产。 */
    public int deleteNonNative(String chain, String symbol) {
        return jdbc.update("""
                delete from chain_asset where chain = ? and symbol = ? and native_asset = false
                """, chain, symbol);
    }

    /** 将指定链的资产配置迁移到新的链名称。 */
    public int moveChain(String currentChain, String targetChain) {
        return jdbc.update("""
                update chain_asset
                   set chain = ?, updated_at = now()
                 where chain = ?
                """, targetChain, currentChain);
    }
}
