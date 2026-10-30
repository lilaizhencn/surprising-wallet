package com.surprising.wallet.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.SqlArrayValue;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Objects;

/** custody_deposit 单表仓储。 */
@Repository
public class CustodyDepositRepository {
    public List<Map<String, Object>> listForAddress(UUID tenantId, UUID addressId, String chain,
                                                   String assetSymbol, int limit, int offset) {
        return jdbc.queryForList("""
                select id, tenant_id, custody_address_id, chain, asset_symbol, tx_hash, log_index,
                       amount, status, credited_at, created_at, updated_at
                  from custody_deposit
                 where tenant_id = ? and custody_address_id = ? and chain = ? and asset_symbol = ?
                 order by created_at desc, id limit ? offset ?
                """, tenantId, addressId, chain, assetSymbol, Math.min(Math.max(limit, 1), 500), Math.max(offset, 0));
    }
    private static final String HISTORY_WHERE = """
             where tenant_id = ?
               and (not ? or custody_address_id = any (?))
               and (? = '' or chain = ?) and (? = '' or asset_symbol = ?) and (? = '' or status = ?)
               and (? = '' or position(? in lower(tx_hash)) > 0 or custody_address_id = any (?))
            """;
    /** JDBC 模板。 */
    private final JdbcTemplate jdbc;

    /** 构造托管充值单表仓储。 */
    public CustodyDepositRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 查询已确认充值的入账时间。 */
    public Timestamp findConfirmedAt(UUID tenantId, UUID custodyAddressId, String chain,
                                     String assetSymbol, String txHash) {
        List<Timestamp> rows = jdbc.queryForList("""
                select credited_at
                  from custody_deposit
                 where tenant_id = ? and custody_address_id = ?
                   and chain = ? and asset_symbol = ?
                   and lower(tx_hash) = lower(?) and status = 'CONFIRMED'
                 limit 1
                """, Timestamp.class, tenantId, custodyAddressId, chain, assetSymbol, txHash);
        return rows.stream().findFirst().orElse(null);
    }

    /** 统计租户托管充值记录。 */
    public long countByTenant(UUID tenantId, String chain, String assetSymbol, String status) {
        Long count = jdbc.queryForObject("""
                select count(*) from custody_deposit where tenant_id = ?
                   and (? = '' or chain = ?) and (? = '' or asset_symbol = ?) and (? = '' or status = ?)
                """, Long.class, tenantId, chain, chain, assetSymbol, assetSymbol, status, status);
        return count == null ? 0 : count;
    }

    public List<HistoryEntry> history(HistoryQuery query, int limit, int offset) {
        var args = historyArgs(query);
        args.add(Math.min(Math.max(limit, 1), 200));
        args.add(Math.max(offset, 0));
        return jdbc.query("""
                select id, custody_address_id, chain, asset_symbol, tx_hash, log_index,
                       amount, status, credited_at, created_at, updated_at
                  from custody_deposit
                """ + HISTORY_WHERE + " order by created_at desc, id limit ? offset ?",
                (rs, rowNum) -> new HistoryEntry(rs.getObject("id", UUID.class),
                        rs.getObject("custody_address_id", UUID.class), rs.getString("chain"),
                        rs.getString("asset_symbol"), rs.getString("tx_hash"), rs.getLong("log_index"),
                        rs.getBigDecimal("amount"), rs.getString("status"),
                        rs.getTimestamp("credited_at") == null ? null : rs.getTimestamp("credited_at").toInstant(),
                        rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant()),
                args.toArray());
    }

    public long countHistory(HistoryQuery query) {
        return jdbc.queryForObject("select count(*) from custody_deposit " + HISTORY_WHERE,
                Long.class, historyArgs(query).toArray());
    }

    public long countCustomerDeposits(UUID tenantId, List<UUID> gasAddressIds) {
        return jdbc.queryForObject("""
                select count(*) from custody_deposit
                 where tenant_id = ? and not (custody_address_id = any (?))
                """, Long.class, tenantId, new SqlArrayValue("uuid", gasAddressIds.toArray()));
    }

    private static ArrayList<Object> historyArgs(HistoryQuery query) {
        var args = new ArrayList<Object>();
        args.add(query.tenantId());
        args.add(query.subjectFiltered());
        args.add(new SqlArrayValue("uuid", query.subjectAddressIds().toArray()));
        args.add(query.chain()); args.add(query.chain());
        args.add(query.assetSymbol()); args.add(query.assetSymbol());
        args.add(query.status()); args.add(query.status());
        args.add(query.search()); args.add(query.search());
        args.add(new SqlArrayValue("uuid", query.matchingAddressIds().toArray()));
        return args;
    }

    public record HistoryQuery(UUID tenantId, String chain, String assetSymbol, String status,
                               String search, List<UUID> matchingAddressIds, boolean subjectFiltered, List<UUID> subjectAddressIds) {
        public HistoryQuery {
            Objects.requireNonNull(tenantId, "tenantId");
            subjectAddressIds = List.copyOf(subjectAddressIds);
            matchingAddressIds = List.copyOf(matchingAddressIds);
        }
    }

    public record HistoryEntry(UUID id, UUID custodyAddressId, String chain, String assetSymbol,
                               String txHash, long logIndex, BigDecimal amount, String status,
                               Instant creditedAt, Instant createdAt, Instant updatedAt) { }

    public UUID credit(UUID tenantId, UUID addressId, long depositRecordId, String chain,
                       String symbol, String txHash, long logIndex, java.math.BigDecimal amount) {
        return jdbc.queryForObject("""
                insert into custody_deposit(id, tenant_id, custody_address_id, deposit_record_id,
                    chain, asset_symbol, tx_hash, log_index, amount, status, credited_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, 'CONFIRMED', now())
                on conflict (tenant_id, deposit_record_id) do update set
                    status = excluded.status, credited_at = excluded.credited_at, updated_at = now()
                returning id
                """, UUID.class, UUID.randomUUID(), tenantId, addressId, depositRecordId,
                chain, symbol, txHash, logIndex, amount);
    }

    public java.util.Optional<Projection> lockProjection(UUID tenantId, long depositRecordId) {
        return jdbc.query("""
                select id, custody_address_id from custody_deposit
                 where tenant_id = ? and deposit_record_id = ? for update
                """, (rs, rowNum) -> new Projection(rs.getObject("id", UUID.class),
                rs.getObject("custody_address_id", UUID.class)), tenantId, depositRecordId).stream().findFirst();
    }

    public int markReorged(UUID tenantId, UUID id) {
        return jdbc.update("""
                update custody_deposit set status = 'REORGED', updated_at = now() where id = ? and tenant_id = ?
                """, id, tenantId);
    }

    public record Projection(UUID id, UUID addressId) { }

}
