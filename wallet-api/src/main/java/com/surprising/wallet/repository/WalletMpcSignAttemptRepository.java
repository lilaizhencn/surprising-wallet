package com.surprising.wallet.repository;

import com.surprising.wallet.common.signing.MpcClient;
import com.surprising.wallet.model.mpc.MpcObservation;
import com.surprising.wallet.model.mpc.MpcSignAttempt;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/** wallet_mpc_sign_attempt single-table repository. */
@Repository
public class WalletMpcSignAttemptRepository {
    private static final String COLUMNS = "request_id,request::text,authorization_proof::text,automatic_operation::text,state,signature_ciphertext,node_results::text,replay_actor,replay_reason";
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public WalletMpcSignAttemptRepository(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }

    public MpcSignAttempt create(UUID requestId, MpcClient.Request request, String actor, String reason) {
        return jdbc.query("""
                insert into wallet_mpc_sign_attempt(request_id,request,replay_actor,replay_reason)
                values (?,?::jsonb,?,?) returning
                """ + COLUMNS, (rs, row) -> map(rs), requestId, json.writeValueAsString(request), actor, reason).getFirst();
    }

    public Optional<MpcSignAttempt> find(UUID tenant, UUID id) {
        return jdbc.query("select " + COLUMNS + " from wallet_mpc_sign_attempt where tenant_id=? and id=?",
                (rs, row) -> map(rs), tenant, id).stream().findFirst();
    }

    public int authorize(UUID tenant,UUID id,com.surprising.wallet.common.approval.SigningAuthorization.Proof proof) {
        return authorize(tenant,id,proof,null);
    }
    public int authorize(UUID tenant,UUID id,com.surprising.wallet.common.approval.SigningAuthorization.Proof proof,com.surprising.wallet.common.approval.AutomaticOperation automaticOperation) {
        return jdbc.update("update wallet_mpc_sign_attempt set authorization_proof=?::jsonb,automatic_operation=?::jsonb,updated_at=now() where tenant_id=? and id=? and authorization_proof is null",
                json.writeValueAsString(proof),automaticOperation==null?null:json.writeValueAsString(automaticOperation),tenant,id);
    }

    /** Durable dispatch fence, committed before native RPC; this is not an acknowledgement of node acceptance. */
    public int start(UUID tenant,UUID id) {
        return jdbc.update("update wallet_mpc_sign_attempt set state='RUNNING',updated_at=now() where tenant_id=? and id=? and state='PENDING'",tenant,id);
    }

    public int observe(UUID tenant, UUID id, MpcSignAttempt.State state, Map<String, MpcObservation> observations) {
        return jdbc.update("""
                update wallet_mpc_sign_attempt set state=?,node_results=?::jsonb,updated_at=now()
                 where tenant_id=? and id=? and state <> 'COMPLETED'
                   and (state <> 'INDETERMINATE' or ? = 'INDETERMINATE')
                """, state.name(), json.writeValueAsString(observations), tenant, id, state.name());
    }

    public int complete(UUID tenant, UUID id, String signatureCiphertext, Map<String, MpcObservation> observations) {
        return jdbc.update("""
                update wallet_mpc_sign_attempt set state='COMPLETED',signature_ciphertext=?,node_results=?::jsonb,updated_at=now()
                 where tenant_id=? and id=? and state <> 'COMPLETED'
                """, signatureCiphertext, json.writeValueAsString(observations), tenant, id);
    }

    public List<MpcSignAttempt> history(UUID tenant, UUID requestId) {
        return jdbc.query("select " + COLUMNS + " from wallet_mpc_sign_attempt where tenant_id=? and request_id=? order by created_at,id",
                (rs, row) -> map(rs), tenant, requestId);
    }

    private MpcSignAttempt map(ResultSet rs) throws SQLException {
        Map<String, MpcObservation> observations = json.readValue(rs.getString("node_results"),
                json.getTypeFactory().constructMapType(Map.class, String.class, MpcObservation.class));
        return new MpcSignAttempt(rs.getObject("request_id", UUID.class),
                json.readValue(rs.getString("request"), MpcClient.Request.class),
                rs.getString("authorization_proof")==null?null:json.readValue(rs.getString("authorization_proof"),com.surprising.wallet.common.approval.SigningAuthorization.Proof.class),
                rs.getString("automatic_operation")==null?null:json.readValue(rs.getString("automatic_operation"),com.surprising.wallet.common.approval.AutomaticOperation.class),
                MpcSignAttempt.State.valueOf(rs.getString("state")), rs.getString("signature_ciphertext"),
                observations, rs.getString("replay_actor"), rs.getString("replay_reason"));
    }
}
