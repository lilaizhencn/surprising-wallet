package com.surprising.wallet.repository;

import com.surprising.wallet.chain.model.ChainAsset;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * chain_asset 单表仓储。
 */
@Repository
public class ChainAssetRepository {
    private static final String COLUMNS = """
            id, chain, symbol, asset_kind, contract_address, decimals, native_asset, active,
            min_transfer, min_withdraw, created_at, updated_at
            """;
    private final JdbcTemplate jdbc;

    public ChainAssetRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<ChainAsset> listDefinitions() {
        return query("order by chain, symbol");
    }

    public List<ChainAsset> listDefinitionsByChain(String chain) {
        return query("where upper(chain)=upper(?) order by symbol", chain);
    }

    public List<ChainAsset> listActiveDefinitions() {
        return query("where active=true order by chain, symbol");
    }

    public List<ChainAsset> listNonNativeOverview() {
        return query("where native_asset=false order by chain, symbol");
    }

    public List<ChainAsset> listActiveByChain(String chain) {
        return query("where chain=? and active=true", chain);
    }

    public Optional<ChainAsset> find(String chain, String symbol) {
        return query("where chain=? and symbol=?", chain, symbol).stream().findFirst();
    }

    public Optional<ChainAsset> findActive(String chain, String symbol) {
        return query("where chain=? and symbol=? and active=true", chain, symbol).stream().findFirst();
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

    /** 写入或更新完整链资产模型。 */
    public int upsert(ChainAsset asset) {
        return jdbc.update("""
                insert into chain_asset(chain, symbol, asset_kind, contract_address, decimals, native_asset, active,
                                        min_transfer, min_withdraw, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (chain, symbol) do update set
                    asset_kind = excluded.asset_kind, contract_address = excluded.contract_address,
                    decimals = excluded.decimals, native_asset = excluded.native_asset, active = excluded.active,
                    min_transfer = excluded.min_transfer, min_withdraw = excluded.min_withdraw,
                    updated_at = excluded.updated_at
                """, asset.getChain(), asset.getSymbol(), asset.getAssetKind(), asset.getContractAddress(),
                asset.getDecimals(), asset.getNativeAsset(), asset.getActive(), asset.getMinTransfer(),
                asset.getMinWithdraw(),
                asset.getCreatedAt() == null ? null : java.sql.Timestamp.from(asset.getCreatedAt()),
                asset.getUpdatedAt() == null ? null : java.sql.Timestamp.from(asset.getUpdatedAt()));
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

    public Optional<ChainAsset> findActiveDefinition(String chain, String symbol) {
        return query("where upper(chain)=upper(?) and upper(symbol)=upper(?) and active=true", chain, symbol)
                .stream().findFirst();
    }

    /** 已观察费用仍按原生资产保存的精度结算，不受新业务开关影响。 */
    public Optional<ChainAsset> findDefinition(String chain, String symbol) {
        return query("where upper(chain)=upper(?) and upper(symbol)=upper(?)", chain, symbol).stream().findFirst();
    }

    private List<ChainAsset> query(String predicate, Object... args) {
        return jdbc.query("select " + COLUMNS + " from chain_asset " + predicate,
                (rs, rowNum) -> mapDefinition(rs), args);
    }

    public int countActiveNativeAssets(String chain) {
        return jdbc.queryForObject("""
                select count(*) from chain_asset
                 where upper(chain) = upper(?) and native_asset = true and active = true
                """, Integer.class, chain);
    }

    private static ChainAsset mapDefinition(ResultSet rs) throws SQLException {
        var created = rs.getTimestamp("created_at");
        var updated = rs.getTimestamp("updated_at");
        return ChainAsset.builder().id(rs.getLong("id")).chain(rs.getString("chain"))
                .symbol(rs.getString("symbol")).assetKind(rs.getString("asset_kind"))
                .contractAddress(rs.getString("contract_address")).decimals(rs.getInt("decimals"))
                .nativeAsset(rs.getBoolean("native_asset")).active(rs.getBoolean("active"))
                .minTransfer(rs.getBigDecimal("min_transfer")).minWithdraw(rs.getBigDecimal("min_withdraw"))
                .createdAt(created == null ? null : created.toInstant())
                .updatedAt(updated == null ? null : updated.toInstant()).build();
    }
}
