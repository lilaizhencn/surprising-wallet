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
    public void create(String actionId, String actionType, String chain, String assetSymbol,
                       String fromAddress, String toAddress, java.math.BigDecimal amount,
                       long nonce, String requestPayload, java.sql.Timestamp now) {
        jdbc.update("""
                insert into hypercore_action_record(action_id, action_type, chain, asset_symbol,
                                                    from_address, to_address, amount, nonce,
                                                    request_payload, status, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, 'CREATED', ?, ?)
                on conflict (action_id) do nothing
                """, actionId, actionType, chain, assetSymbol, fromAddress, toAddress,
                amount, nonce, requestPayload, now, now);
    }

    /** 标记操作已接受。 */
    public void markAccepted(String actionId, String responsePayload, java.sql.Timestamp now) {
        jdbc.update("""
                update hypercore_action_record
                   set status = 'ACCEPTED', response_payload = ?,
                       error_message = null, updated_at = ?
                 where action_id = ?
                """, responsePayload, now, actionId);
    }

    /** 标记操作失败。 */
    public void markFailed(String actionId, String errorMessage, java.sql.Timestamp now) {
        jdbc.update("""
                update hypercore_action_record
                   set status = 'FAILED', error_message = ?, updated_at = ?
                 where action_id = ?
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
