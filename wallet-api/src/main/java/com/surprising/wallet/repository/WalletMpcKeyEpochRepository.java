package com.surprising.wallet.repository;

import com.surprising.wallet.common.signing.MpcClient;
import com.surprising.wallet.model.mpc.MpcKeyEpoch;
import com.surprising.wallet.model.mpc.MpcObservation;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/** wallet_mpc_key_epoch single-table repository. */
@Repository
public class WalletMpcKeyEpochRepository {
    private static final String COLUMNS = "request::text,state,node_results::text,refresh_actor,refresh_reason";
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public WalletMpcKeyEpochRepository(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }

    public MpcKeyEpoch create(MpcClient.Request request) {
        return create(request, null, null);
    }

    public MpcKeyEpoch create(MpcClient.Request request, String actor, String reason) {
        return jdbc.query("""
                insert into wallet_mpc_key_epoch(request,refresh_actor,refresh_reason) values (?::jsonb,?,?)
                on conflict (key_id,epoch) do update set updated_at=wallet_mpc_key_epoch.updated_at returning
                """ + COLUMNS, (rs, row) -> map(rs), json.writeValueAsString(request), actor, reason).getFirst();
    }

    public Optional<MpcKeyEpoch> find(UUID tenant, UUID key, int epoch) {
        return jdbc.query("select " + COLUMNS + " from wallet_mpc_key_epoch where tenant_id=? and key_id=? and epoch=?",
                (rs, row) -> map(rs), tenant, key, epoch).stream().findFirst();
    }

    public Optional<tools.jackson.databind.JsonNode> registration(UUID tenant,UUID key,int epoch) {
        return jdbc.query("select approval_registration::text from wallet_mpc_key_epoch where tenant_id=? and key_id=? and epoch=? and approval_registration is not null",
                (r,n)->json.readTree(r.getString(1)),tenant,key,epoch).stream().findFirst();
    }
    public int register(UUID tenant,UUID key,int epoch,tools.jackson.databind.JsonNode receipt) {
        return jdbc.update("update wallet_mpc_key_epoch set approval_registration=?::jsonb,updated_at=now() where tenant_id=? and key_id=? and epoch=? and state in ('ACTIVE','RETIRED') and approval_registration is null",
                json.writeValueAsString(receipt),tenant,key,epoch);
    }

    public int observations(UUID tenant, UUID key, int epoch, MpcKeyEpoch.State state, Map<String, MpcObservation> observations) {
        return jdbc.update("""
                update wallet_mpc_key_epoch set state=?,node_results=?::jsonb,updated_at=now()
                 where tenant_id=? and key_id=? and epoch=? and state='PREPARING'
                """, state.name(), json.writeValueAsString(observations), tenant, key, epoch);
    }

    public int retire(UUID tenant, UUID key, int epoch) {
        return jdbc.update("""
                update wallet_mpc_key_epoch set state='RETIRED',updated_at=now()
                 where tenant_id=? and key_id=? and epoch=? and state='ACTIVE'
                """, tenant, key, epoch);
    }

    private MpcKeyEpoch map(ResultSet rs) throws SQLException {
        Map<String, MpcObservation> observations = json.readValue(rs.getString("node_results"),
                json.getTypeFactory().constructMapType(Map.class, String.class, MpcObservation.class));
        return new MpcKeyEpoch(json.readValue(rs.getString("request"), MpcClient.Request.class),
                MpcKeyEpoch.State.valueOf(rs.getString("state")), observations,
                rs.getString("refresh_actor"), rs.getString("refresh_reason"));
    }
}
