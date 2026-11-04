package com.surprising.wallet.repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Owns only custody_hot_wallet_exposure_limit. The row lock serializes collection capacity. */
@Repository
public class CustodyHotWalletExposureLimitRepository {
    private final JdbcTemplate jdbc;
    public CustodyHotWalletExposureLimitRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    public record Limit(UUID tenantId,String chain,String assetSymbol,BigDecimal maximumActiveBalance) { }

    public List<Limit> list(UUID tenant,String chain) {
        return jdbc.query("""
                select tenant_id,chain,asset_symbol,maximum_active_balance
                  from custody_hot_wallet_exposure_limit
                 where tenant_id=? and chain=? order by asset_symbol
                """,(rs,row)->new Limit(rs.getObject(1,UUID.class),rs.getString(2),
                rs.getString(3),rs.getBigDecimal(4)),tenant,chain);
    }

    public Optional<Limit> lock(UUID tenant,String chain,String asset) {
        return jdbc.query("""
                select tenant_id,chain,asset_symbol,maximum_active_balance
                  from custody_hot_wallet_exposure_limit
                 where tenant_id=? and chain=? and asset_symbol=? for update
                """,(rs,row)->new Limit(rs.getObject(1,UUID.class),rs.getString(2),
                rs.getString(3),rs.getBigDecimal(4)),tenant,chain,asset).stream().findFirst();
    }

    public int upsert(Limit limit) {
        return jdbc.update("""
                insert into custody_hot_wallet_exposure_limit
                    (tenant_id,chain,asset_symbol,maximum_active_balance)
                values (?,?,?,?)
                on conflict (tenant_id,chain,asset_symbol) do update set
                    maximum_active_balance=excluded.maximum_active_balance,updated_at=now()
                """,limit.tenantId(),limit.chain(),limit.assetSymbol(),limit.maximumActiveBalance());
    }
}
