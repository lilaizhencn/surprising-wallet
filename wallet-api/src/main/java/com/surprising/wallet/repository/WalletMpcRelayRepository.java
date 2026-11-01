package com.surprising.wallet.repository;

import com.surprising.wallet.common.approval.SigningAuthorization;
import com.surprising.wallet.common.signing.MpcClient;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/** wallet_mpc_relay only. The original relay descriptor and its once-persisted proof survive all network retries. */
@Repository
public class WalletMpcRelayRepository {
    private final JdbcTemplate jdbc;private final ObjectMapper json;
    public WalletMpcRelayRepository(JdbcTemplate jdbc,ObjectMapper json){this.jdbc=jdbc;this.json=json;}
    public Stored reserve(UUID requestId,UUID signSessionId,MpcClient.Request request) {
        jdbc.update("insert into wallet_mpc_relay(tenant_id,request_id,sign_session_id,holder,request) values (?,?,?,?,?::jsonb) on conflict (tenant_id,request_id,holder) do nothing",
                request.tenantId(),requestId,signSessionId,request.online().getFirst(),json.writeValueAsString(request));
        var stored=find(request.tenantId(),requestId,request.online().getFirst());
        if(!stored.signSessionId().equals(signSessionId) || !stored.execution().equals(request))throw new IllegalStateException("original Monero relay descriptor cannot change");return stored;
    }
    public Stored find(UUID tenant,UUID requestId,String holder) {
        return jdbc.query("select sign_session_id,request,authorization_proof from wallet_mpc_relay where tenant_id=? and request_id=? and holder=?",
                (r,n)->new Stored(r.getObject("sign_session_id",UUID.class),json.readValue(r.getString("request"),MpcClient.Request.class),r.getString("authorization_proof")==null?null:json.readValue(r.getString("authorization_proof"),SigningAuthorization.Proof.class)),tenant,requestId,holder).getFirst();
    }
    public int authorize(UUID tenant,UUID requestId,String holder,SigningAuthorization.Proof proof) {
        return jdbc.update("update wallet_mpc_relay set authorization_proof=?::jsonb where tenant_id=? and request_id=? and holder=? and authorization_proof is null",json.writeValueAsString(proof),tenant,requestId,holder);
    }
    public record Stored(UUID signSessionId,MpcClient.Request execution,SigningAuthorization.Proof proof) { }
}
