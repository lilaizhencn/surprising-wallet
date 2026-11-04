package com.surprising.wallet.approval.repository;

import com.surprising.wallet.approval.gateway.ApprovalNativeRegistryClient;
import com.surprising.wallet.approval.model.RegisteredMpcKey;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/** approval.key only; values are admitted only after direct authenticated three-holder observation. */
@Repository
public class ApprovalKeyRepository {
    private final JdbcTemplate jdbc;private final ObjectMapper json;
    public ApprovalKeyRepository(JdbcTemplate jdbc,ObjectMapper json) { this.jdbc=jdbc;this.json=json; }
    public Optional<RegisteredMpcKey> find(UUID tenant,UUID key) {
        return jdbc.query("select * from approval.key where tenant_id=? and key_id=?",(r,n)->map(r),tenant,key).stream().findFirst();
    }
    public Optional<RegisteredMpcKey> lock(UUID tenant,UUID key) {
        return jdbc.query("select * from approval.key where tenant_id=? and key_id=? for update",(r,n)->map(r),tenant,key).stream().findFirst();
    }
    public Optional<RegisteredMpcKey> findByPublicKey(UUID tenant,String group,String publicKey) {
        return jdbc.query("select * from approval.key where tenant_id=? and key_group=? and public_key_hex=? and state='ACTIVE'",
                (r,n)->map(r),tenant,group,publicKey).stream().reduce((a,b)->{throw new IllegalStateException("ambiguous independently registered public key");});
    }
    public java.util.List<RegisteredMpcKey> active(UUID tenant,String group) {
        return jdbc.query("select * from approval.key where tenant_id=? and key_group=? and state='ACTIVE' order by key_id limit 10001",(r,n)->map(r),tenant,group);
    }
    public void insert(String group,ApprovalNativeRegistryClient.Ceremony ceremony) {
        var r=ceremony.request();
        jdbc.update("""
                insert into approval.key(key_id,tenant_id,key_group,algorithm,epoch,public_key_hex,public_address,ceremony_request,observations)
                values (?,?,?,?,?,?,?,?::jsonb,?::jsonb)
                """,r.keyId(),r.tenantId(),group,r.algorithm().name(),r.epoch(),ceremony.publicKeyHex(),ceremony.publicAddress(),
                json.writeValueAsString(r),json.writeValueAsString(ceremony.observations()));
    }
    public int refresh(RegisteredMpcKey previous,ApprovalNativeRegistryClient.Ceremony ceremony) {
        var r=ceremony.request();
        return jdbc.update("""
                update approval.key set epoch=?,ceremony_request=?::jsonb,observations=?::jsonb,updated_at=now()
                where tenant_id=? and key_id=? and epoch=? and public_key_hex=? and state='ACTIVE'
                """,r.epoch(),json.writeValueAsString(r),json.writeValueAsString(ceremony.observations()),previous.tenantId(),previous.keyId(),previous.epoch(),previous.publicKeyHex());
    }
    public int quarantine(UUID tenant, UUID key, String descriptorHash) {
        return jdbc.update("""
                update approval.key set state='DISABLED', quarantine_hash=?, updated_at=now()
                where tenant_id=? and key_id=? and state in ('ACTIVE','DISABLED') and quarantine_hash is null
                """, descriptorHash, tenant, key);
    }
    public Optional<String> quarantineHash(UUID tenant, UUID key) {
        var values = jdbc.query("select quarantine_hash from approval.key where tenant_id=? and key_id=?",
                (r,n)->r.getString(1), tenant, key);
        return values.isEmpty() ? Optional.empty() : Optional.ofNullable(values.getFirst());
    }
    private static RegisteredMpcKey map(ResultSet r) throws SQLException {
        return new RegisteredMpcKey(r.getObject("tenant_id",UUID.class),r.getObject("key_id",UUID.class),r.getString("key_group"),r.getString("algorithm"),
                r.getInt("epoch"),r.getString("public_key_hex"),r.getString("public_address"),r.getString("state"));
    }
}
