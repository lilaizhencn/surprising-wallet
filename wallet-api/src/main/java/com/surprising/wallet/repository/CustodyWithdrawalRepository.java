package com.surprising.wallet.repository;

import com.surprising.wallet.model.custody.GasOperationReference;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.SqlArrayValue;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Objects;

/** custody_withdrawal 单表仓储。 */
@Repository
public class CustodyWithdrawalRepository {
    private static final String STATUS_COLUMNS = "id, tenant_id, custody_address_id, withdrawal_order_id, order_no, "
            + "external_reference, chain, status";

    public List<StatusRow> listReconciliationCandidates(UUID afterId, int limit) {
        return jdbc.query("select " + STATUS_COLUMNS + """
                 from custody_withdrawal where status <> 'CONFIRMED'
                   and (cast(? as uuid) is null or id > cast(? as uuid))
                 order by id limit ?
                """, CustodyWithdrawalRepository::mapStatusRow, afterId, afterId, Math.min(Math.max(limit, 1), 500));
    }

    public java.util.Optional<StatusRow> lockProjection(UUID tenantId, UUID id) {
        return jdbc.query("select " + STATUS_COLUMNS + " from custody_withdrawal where tenant_id=? and id=? for update",
                CustodyWithdrawalRepository::mapStatusRow, tenantId, id).stream().findFirst();
    }

    private static StatusRow mapStatusRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new StatusRow(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getObject("custody_address_id", UUID.class), rs.getLong("withdrawal_order_id"),
                rs.getString("order_no"), rs.getString("external_reference"), rs.getString("chain"), rs.getString("status"));
    }

    public record StatusRow(UUID id, UUID tenantId, UUID custodyAddressId, long withdrawalOrderId,
                            String orderNo, String externalReference, String chain, String status) { }
    public java.util.Optional<GasOperationReference> lockGasOperation(UUID tenantId, UUID id) {
        return jdbc.query("select chain, order_no from custody_withdrawal where tenant_id=? and id=? for update",
                (rs, rowNum) -> new GasOperationReference(
                        rs.getString("chain"), rs.getString("order_no")), tenantId, id).stream().findFirst();
    }
    private static final String HISTORY_WHERE = """
             where tenant_id = ?
               and (? = '' or chain = ?) and (? = '' or asset_symbol = ?) and (? = '' or status = ?)
               and (? = '' or position(? in lower(concat_ws(' ', order_no, external_reference, to_address))) > 0
                    or withdrawal_order_id = any (?))
            """;
    /** JDBC 模板。 */
    private final JdbcTemplate jdbc;

    /** 构造托管提现仓储。 */
    public CustodyWithdrawalRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 按订单查询托管提现单表字段。 */
    public List<StatusRow> listByOrder(UUID tenantId, long withdrawalOrderId) {
        return jdbc.query("select " + STATUS_COLUMNS + " from custody_withdrawal where tenant_id = ? and withdrawal_order_id = ?",
                CustodyWithdrawalRepository::mapStatusRow, tenantId, withdrawalOrderId);
    }

    /** 按租户和主键查询托管提现记录。 */
    public List<Map<String, Object>> find(UUID tenantId, UUID id) {
        return jdbc.queryForList("""
                select id, tenant_id, chain, status
                  from custody_withdrawal where tenant_id = ? and id = ?
                """, tenantId, id);
    }

    /** 创建托管提现记录，订单主键由业务编排层解析后传入。 */
    public int insert(UUID id, UUID tenantId, UUID custodyAddressId, long withdrawalOrderId,
                      String orderNo, String externalReference, String idempotencyKey, String chain,
                      String assetSymbol, String toAddress, java.math.BigDecimal amount,
                      java.math.BigDecimal fee, String status, String createdByType, String createdById) {
        return jdbc.update("""
                insert into custody_withdrawal(id, tenant_id, custody_address_id, withdrawal_order_id,
                    order_no, external_reference, idempotency_key, chain, asset_symbol, to_address,
                    amount, fee, status, created_by_type, created_by_id)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, tenantId, custodyAddressId, withdrawalOrderId, orderNo, externalReference,
                idempotencyKey, chain, assetSymbol, toAddress, amount, fee, status, createdByType, createdById);
    }

    /** 查询租户托管提现记录。 */
    public List<Map<String, Object>> listByTenant(UUID tenantId, String chain, String assetSymbol,
                                                  String status, int limit, int offset) {
        return jdbc.queryForList("""
                select id, tenant_id, custody_address_id, withdrawal_order_id, order_no, external_reference,
                       chain, asset_symbol, to_address, amount, fee, status, created_by_type, created_by_id,
                       created_at, updated_at
                  from custody_withdrawal where tenant_id = ?
                   and (? = '' or chain = ?) and (? = '' or asset_symbol = ?) and (? = '' or status = ?)
                 order by created_at desc, id limit ? offset ?
                """, tenantId, chain, chain, assetSymbol, assetSymbol, status, status,
                Math.min(Math.max(limit, 1), 500), Math.max(offset, 0));
    }

    /** 统计租户托管提现记录。 */
    public long countByTenant(UUID tenantId, String chain, String assetSymbol, String status) {
        Long count = jdbc.queryForObject("""
                select count(*) from custody_withdrawal where tenant_id = ?
                   and (? = '' or chain = ?) and (? = '' or asset_symbol = ?) and (? = '' or status = ?)
                """, Long.class, tenantId, chain, chain, assetSymbol, assetSymbol, status, status);
        return count == null ? 0 : count;
    }

    public List<HistoryEntry> history(HistoryQuery query, int limit, int offset) {
        var args = historyArgs(query);
        args.add(Math.min(Math.max(limit, 1), 200));
        args.add(Math.max(offset, 0));
        return jdbc.query("""
                select id, custody_address_id, withdrawal_order_id, order_no, external_reference,
                       chain, asset_symbol, to_address, amount, fee, status, created_by_type,
                       created_at, updated_at from custody_withdrawal
                """ + HISTORY_WHERE + " order by created_at desc, id limit ? offset ?",
                (rs, rowNum) -> new HistoryEntry(rs.getObject("id", UUID.class),
                        rs.getObject("custody_address_id", UUID.class), rs.getLong("withdrawal_order_id"),
                        rs.getString("order_no"), rs.getString("external_reference"), rs.getString("chain"),
                        rs.getString("asset_symbol"), rs.getString("to_address"), rs.getBigDecimal("amount"),
                        rs.getBigDecimal("fee"), rs.getString("status"), rs.getString("created_by_type"),
                        rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant()),
                args.toArray());
    }

    public long countHistory(HistoryQuery query) {
        return jdbc.queryForObject("select count(*) from custody_withdrawal " + HISTORY_WHERE,
                Long.class, historyArgs(query).toArray());
    }

    private static ArrayList<Object> historyArgs(HistoryQuery query) {
        var args = new ArrayList<Object>();
        args.add(query.tenantId());
        args.add(query.chain()); args.add(query.chain());
        args.add(query.assetSymbol()); args.add(query.assetSymbol());
        args.add(query.status()); args.add(query.status());
        args.add(query.search()); args.add(query.search());
        args.add(new SqlArrayValue("bigint", query.matchingOrderIds().toArray()));
        return args;
    }

    public record HistoryQuery(UUID tenantId, String chain, String assetSymbol, String status,
                               String search, List<Long> matchingOrderIds) {
        public HistoryQuery {
            Objects.requireNonNull(tenantId, "tenantId");
            matchingOrderIds = List.copyOf(matchingOrderIds);
        }
    }

    public record HistoryEntry(UUID id, UUID custodyAddressId, long withdrawalOrderId, String orderNo,
                               String externalReference, String chain, String assetSymbol, String toAddress,
                               BigDecimal amount, BigDecimal fee, String status, String createdByType,
                               Instant createdAt, Instant updatedAt) { }


    /** 更新托管提现记录状态。 */
    public int updateStatus(UUID tenantId, UUID id, String status) {
        return jdbc.update("update custody_withdrawal set status = ?, updated_at = now() where tenant_id = ? and id = ?",
                status, tenantId, id);
    }

    /** 仅当前投影状态仍匹配时推进，防止同一状态变化被并发或过期任务重复应用。 */
    public int transitionStatus(UUID tenantId, UUID id, String expectedStatus, String nextStatus) {
        return jdbc.update("""
                update custody_withdrawal set status = ?, updated_at = now()
                 where tenant_id = ? and id = ? and status = ?
                """, nextStatus, tenantId, id, expectedStatus);
    }

}
