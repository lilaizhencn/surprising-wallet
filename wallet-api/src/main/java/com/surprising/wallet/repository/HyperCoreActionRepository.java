package com.surprising.wallet.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** hypercore_action_record 单表仓储。 */
@Repository
public class HyperCoreActionRepository {
    /** JDBC 模板。 */
    private final JdbcTemplate jdbc;

    /** 构造 HyperCore 操作记录仓储。 */
    public HyperCoreActionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 创建操作记录。 */
    public void create(java.util.UUID tenantId, java.util.UUID requestId, String actionId, String actionType, String chain, String assetSymbol,
                       String fromAddress, String toAddress, java.math.BigDecimal amount,
                       long nonce, String requestPayload, java.sql.Timestamp now) {
        jdbc.update("""
                insert into hypercore_action_record(tenant_id, request_id, action_id, action_type, chain, asset_symbol,
                                                    from_address, to_address, amount, nonce,
                                                    request_payload, status, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PREPARED', ?, ?)
                on conflict (action_id) do nothing
                """, tenantId, requestId, actionId, actionType, chain, assetSymbol, fromAddress, toAddress,
                amount, nonce, requestPayload, now, now);
    }

    public record Action(java.util.UUID tenantId,java.util.UUID requestId,String id,String type,String sender,String recipient,String symbol,java.math.BigDecimal amount,
                         long nonce,String raw,String status,String response,String receipt) { }
    public java.util.Optional<Action> find(String id) {
        return jdbc.query("select * from hypercore_action_record where action_id=?",(rs,n)->new Action(rs.getObject("tenant_id",java.util.UUID.class),rs.getObject("request_id",java.util.UUID.class),rs.getString("action_id"),
                rs.getString("action_type"),rs.getString("from_address"),rs.getString("to_address"),rs.getString("asset_symbol"),
                rs.getBigDecimal("amount"),rs.getLong("nonce"),rs.getString("request_payload"),rs.getString("status"),rs.getString("response_payload"),rs.getString("execution_receipt")),id).stream().findFirst();
    }

    /** Only the service may pass a verified original ledger event, under the owning business lock. */
    public int recordReceipt(java.util.UUID tenantId,java.util.UUID requestId,String actionId,String receipt) {
        return jdbc.update("""
                update hypercore_action_record set status='ACCEPTED',execution_receipt=cast(? as jsonb),
                       error_message=null,updated_at=now()
                 where tenant_id=? and request_id=? and action_id=? and execution_receipt is null
                """,receipt,tenantId,requestId,actionId);
    }

    /** 标记操作已接受。 */
    public void markAccepted(String actionId, String responsePayload, java.sql.Timestamp now) {
        jdbc.update("""
                update hypercore_action_record
                   set status = 'ACCEPTED', response_payload = ?,
                       error_message = null, updated_at = ?
                 where action_id = ? and status <> 'ACCEPTED'
                """, responsePayload, now, actionId);
    }

    /** 标记操作失败。 */
    public void markUnknown(String actionId, String errorMessage, java.sql.Timestamp now) {
        jdbc.update("""
                update hypercore_action_record
                   set status = 'UNKNOWN', error_message = ?, updated_at = ?
                 where action_id = ? and status <> 'ACCEPTED'
                """, errorMessage, now, actionId);
    }

    /** 判断操作是否已接受。 */
    public boolean accepted(String actionId) {
        Boolean exists = jdbc.queryForObject("""
                select exists(
                    select 1 from hypercore_action_record
                     where action_id = ? and status = 'ACCEPTED'
                )
                """, Boolean.class, actionId);
        return Boolean.TRUE.equals(exists);
    }
}
