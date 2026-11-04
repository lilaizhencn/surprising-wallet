package com.surprising.wallet.repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Owns only custody_hot_wallet_asset_policy. */
@Repository
public class CustodyHotWalletAssetPolicyRepository {
    private final JdbcTemplate jdbc;
    public CustodyHotWalletAssetPolicyRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Policy(UUID tenantId, UUID walletId, String chain, String assetSymbol,
                         BigDecimal targetBalance, BigDecimal maximumBalance,
                         BigDecimal maximumSingleWithdrawal, BigDecimal maximum24hWithdrawal) { }

    public List<Policy> list(UUID tenantId, UUID walletId) {
        return jdbc.query("""
                select tenant_id,wallet_id,chain,asset_symbol,target_balance,maximum_balance,
                       maximum_single_withdrawal,maximum_24h_withdrawal
                  from custody_hot_wallet_asset_policy
                 where tenant_id=? and wallet_id=? order by asset_symbol
                """, CustodyHotWalletAssetPolicyRepository::map, tenantId, walletId);
    }

    public int upsert(Policy policy) {
        return jdbc.update("""
                insert into custody_hot_wallet_asset_policy
                    (tenant_id,wallet_id,chain,asset_symbol,target_balance,maximum_balance,
                     maximum_single_withdrawal,maximum_24h_withdrawal)
                values (?,?,?,?,?,?,?,?)
                on conflict (tenant_id,wallet_id,asset_symbol) do update set
                    target_balance=excluded.target_balance,
                    maximum_balance=excluded.maximum_balance,
                    maximum_single_withdrawal=excluded.maximum_single_withdrawal,
                    maximum_24h_withdrawal=excluded.maximum_24h_withdrawal,
                    updated_at=now()
                """, policy.tenantId(), policy.walletId(), policy.chain(), policy.assetSymbol(),
                policy.targetBalance(), policy.maximumBalance(), policy.maximumSingleWithdrawal(),
                policy.maximum24hWithdrawal());
    }

    private static Policy map(ResultSet rs, int row) throws SQLException {
        return new Policy(rs.getObject("tenant_id", UUID.class), rs.getObject("wallet_id", UUID.class),
                rs.getString("chain"), rs.getString("asset_symbol"),
                rs.getBigDecimal("target_balance"), rs.getBigDecimal("maximum_balance"),
                rs.getBigDecimal("maximum_single_withdrawal"), rs.getBigDecimal("maximum_24h_withdrawal"));
    }
}
