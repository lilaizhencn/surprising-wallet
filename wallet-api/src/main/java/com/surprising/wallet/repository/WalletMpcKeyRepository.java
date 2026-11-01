package com.surprising.wallet.repository;

import com.surprising.wallet.model.mpc.MpcKey;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** wallet_mpc_key single-table repository. */
@Repository
public class WalletMpcKeyRepository {
    private static final String COLUMNS = "id,tenant_id,key_group,user_id,biz,address_index,algorithm,state,active_epoch,public_key_hex";
    private final JdbcTemplate jdbc;
    public WalletMpcKeyRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public MpcKey reserve(UUID id, UUID tenant, String group, long user, int biz, long index, String algorithm) {
        return jdbc.query("""
                insert into wallet_mpc_key(id,tenant_id,key_group,user_id,biz,address_index,algorithm)
                values (?,?,?,?,?,?,?)
                on conflict (tenant_id,key_group,user_id,biz,address_index)
                    where state in ('CREATING','ACTIVE','REFRESHING')
                do update set updated_at = wallet_mpc_key.updated_at returning
                """ + COLUMNS, (rs, row) -> map(rs), id, tenant, group, user, biz, index, algorithm).getFirst();
    }

    public Optional<MpcKey> find(UUID tenant, UUID id) {
        return jdbc.query("select " + COLUMNS + " from wallet_mpc_key where tenant_id=? and id=?",
                (rs, row) -> map(rs), tenant, id).stream().findFirst();
    }

    public Optional<MpcKey> lock(UUID tenant, UUID id) {
        return jdbc.query("select " + COLUMNS + " from wallet_mpc_key where tenant_id=? and id=? for update",
                (rs, row) -> map(rs), tenant, id).stream().findFirst();
    }

    public int activate(UUID tenant, UUID id, int epoch, String publicKeyHex) {
        return jdbc.update("""
                update wallet_mpc_key set state='ACTIVE',active_epoch=?,public_key_hex=?,updated_at=now()
                 where tenant_id=? and id=? and state='CREATING' and public_key_hex is null
                """, epoch, publicKeyHex, tenant, id);
    }

    public int failCreation(UUID tenant, UUID id) {
        return jdbc.update("""
                update wallet_mpc_key set state='FAILED',updated_at=now()
                 where tenant_id=? and id=? and state='CREATING' and public_key_hex is null
                """, tenant, id);
    }

    public int beginRefresh(UUID tenant, UUID id, int currentEpoch) {
        return jdbc.update("""
                update wallet_mpc_key set state='REFRESHING',updated_at=now()
                 where tenant_id=? and id=? and state='ACTIVE' and active_epoch=?
                """, tenant, id, currentEpoch);
    }

    public int finishRefresh(UUID tenant, UUID id, int previousEpoch, String publicKeyHex) {
        return jdbc.update("""
                update wallet_mpc_key set state='ACTIVE',active_epoch=active_epoch+1,updated_at=now()
                 where tenant_id=? and id=? and state='REFRESHING' and active_epoch=? and public_key_hex=?
                """, tenant, id, previousEpoch, publicKeyHex);
    }

    private static MpcKey map(ResultSet rs) throws SQLException {
        return new MpcKey(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getString("key_group"), rs.getLong("user_id"), rs.getInt("biz"), rs.getLong("address_index"),
                rs.getString("algorithm"), MpcKey.State.valueOf(rs.getString("state")),
                rs.getInt("active_epoch"), rs.getString("public_key_hex"));
    }
}
