package com.surprising.wallet.repository;

import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.signing.MpcSignCommand;
import com.surprising.wallet.model.mpc.MpcSignRequest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** wallet_mpc_sign_request single-table repository. */
@Repository
public class WalletMpcSignRequestRepository {
    private static final String COLUMNS = """
            id,tenant_id,key_id,key_epoch,chain,business_type,business_reference,signing_slot,
            signing_purpose,payload_encoding,payload_hex,preimage_hex,state,current_attempt_id
            """;
    private final JdbcTemplate jdbc;
    public WalletMpcSignRequestRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public MpcSignRequest reserve(UUID id, MpcSignCommand command,List<String> references) {
        return jdbc.query("""
                insert into wallet_mpc_sign_request(id,tenant_id,key_id,key_epoch,chain,business_type,
                    business_reference,signing_slot,signing_purpose,payload_encoding,payload_hex,preimage_hex,business_references)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?)
                on conflict (tenant_id,chain,business_type,business_reference,signing_slot)
                do update set updated_at=wallet_mpc_sign_request.updated_at returning
                """ + COLUMNS, (rs, row) -> map(rs), id, command.tenantId(), command.keyId(), command.keyEpoch(),
                command.chain().name(), command.businessType().name(), command.businessReference(), command.signingSlot(),
                command.purpose().name(), command.payloadEncoding(), command.payloadHex(), command.preimageHex(),
                new org.springframework.jdbc.support.SqlArrayValue("text",references.toArray())).getFirst();
    }

    public Optional<MpcSignRequest> find(UUID tenant, UUID id) {
        return jdbc.query("select " + COLUMNS + " from wallet_mpc_sign_request where tenant_id=? and id=?",
                (rs, row) -> map(rs), tenant, id).stream().findFirst();
    }

    public Optional<MpcSignRequest> findBusiness(UUID tenant, ChainType chain, MpcSignCommand.BusinessType type,
                                                String reference, String slot) {
        return jdbc.query("select " + COLUMNS + """
                 from wallet_mpc_sign_request where tenant_id=? and chain=? and business_type=?
                    and business_reference=? and signing_slot=?
                """, (rs, row) -> map(rs), tenant, chain.name(), type.name(), reference, slot).stream().findFirst();
    }

    public Optional<MpcSignRequest> lock(UUID tenant, UUID id) {
        return jdbc.query("select " + COLUMNS + " from wallet_mpc_sign_request where tenant_id=? and id=? for update",
                (rs, row) -> map(rs), tenant, id).stream().findFirst();
    }
    /** Exact structured business membership, including every input/7702 slot of a batch. No other table is queried. */
    public List<UUID> withdrawalMembers(UUID tenant,String chain,String reference) {
        return jdbc.query("""
                select id from wallet_mpc_sign_request where tenant_id=? and chain=? and business_type='WITHDRAWAL'
                    and (business_reference=? or business_references @> array[?::text])
                order by id
                """,(r,n)->r.getObject("id",UUID.class),tenant,chain,reference,reference);
    }
    public List<UUID> businessSlots(UUID tenant,String chain,MpcSignCommand.BusinessType type,String reference) {
        return jdbc.queryForList("select id from wallet_mpc_sign_request where tenant_id=? and chain=? and business_type=? and business_reference=? order by id",UUID.class,tenant,chain,type.name(),reference);
    }
    /** Caller has locked all attempts and proven no grant/signature, using a fresh independent terminal receipt. */
    public int cancelWithoutGrant(UUID tenant,UUID id,String terminalReceipt) {
        return jdbc.update("""
                update wallet_mpc_sign_request set state='CANCELLED',cancellation_receipt=?::jsonb,cancelled_at=now(),updated_at=now()
                where tenant_id=? and id=? and state not in ('COMPLETED','CANCELLED')
                """,terminalReceipt,tenant,id);
    }

    public int pointToAttempt(UUID tenant, UUID id, UUID attempt) {
        return jdbc.update("""
                update wallet_mpc_sign_request set current_attempt_id=?,state='SIGNING',updated_at=now()
                 where tenant_id=? and id=? and state not in ('COMPLETED','CANCELLED')
                """, attempt, tenant, id);
    }

    public int recordState(UUID tenant, UUID id, UUID attempt, MpcSignRequest.State state) {
        return jdbc.update("""
                update wallet_mpc_sign_request set state=?,updated_at=now()
                 where tenant_id=? and id=? and current_attempt_id=? and state not in ('COMPLETED','CANCELLED')
                """, state.name(), tenant, id, attempt);
    }

    public List<MpcSignRequest> pending(int limit) {
        return jdbc.query("select " + COLUMNS + """
                 from wallet_mpc_sign_request where state not in ('COMPLETED','CANCELLED') order by created_at,id limit ?
                """, (rs, row) -> map(rs), Math.min(Math.max(limit, 1), 100));
    }

    public boolean hasPendingForKey(UUID tenant, UUID key) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from wallet_mpc_sign_request where tenant_id=? and key_id=? and state not in ('COMPLETED','CANCELLED'))
                """, Boolean.class, tenant, key));
    }

    private static MpcSignRequest map(ResultSet rs) throws SQLException {
        var command = new MpcSignCommand(rs.getObject("tenant_id", UUID.class), rs.getObject("key_id", UUID.class),
                rs.getInt("key_epoch"), ChainType.valueOf(rs.getString("chain")),
                MpcSignCommand.BusinessType.valueOf(rs.getString("business_type")), rs.getString("business_reference"),
                rs.getString("signing_slot"), MpcSignCommand.Purpose.valueOf(rs.getString("signing_purpose")),
                rs.getString("payload_encoding"), rs.getString("payload_hex"), rs.getString("preimage_hex"));
        return new MpcSignRequest(rs.getObject("id", UUID.class), command,
                MpcSignRequest.State.valueOf(rs.getString("state")), rs.getObject("current_attempt_id", UUID.class));
    }
}
