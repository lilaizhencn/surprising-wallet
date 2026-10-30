package com.surprising.wallet.repository;

import com.surprising.wallet.chain.model.CustodyAddressRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.SqlArrayValue;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.sql.SQLException;
import java.sql.ResultSet;
import java.util.Objects;
import java.util.Locale;
import java.util.ArrayList;

/** custody_address 单表仓储。 */
@Repository
public class CustodyAddressRepository {
    public List<CustodyAddressRecord> listOwned(UUID tenantId) {
        return jdbc.query("select " + COLUMNS + " from custody_address where tenant_id=? order by created_at desc, id",
                (rs, rowNum) -> mapAddress(rs), tenantId);
    }

    public List<CustodyAddressRecord> findByIds(UUID tenantId, List<UUID> ids) {
        if (ids.isEmpty()) return List.of();
        return jdbc.query("select " + COLUMNS + " from custody_address where tenant_id=? and id=any(?)",
                (rs, rowNum) -> mapAddress(rs), tenantId, new SqlArrayValue("uuid", ids.toArray()));
    }

    public List<CustodyAddressRecord> listActiveForTenantsAndChains(List<UUID> tenantIds, List<String> chains) {
        if (tenantIds.isEmpty() || chains.isEmpty()) return List.of();
        return jdbc.query("select " + COLUMNS + """
                 from custody_address where tenant_id=any(?) and chain=any(?) and status='ACTIVE'
                """, (rs, rowNum) -> mapAddress(rs), new SqlArrayValue("uuid", tenantIds.toArray()),
                new SqlArrayValue("varchar", chains.toArray()));
    }

    public Map<UUID, Long> countByTenants(List<UUID> ids) {
        if (ids.isEmpty()) return Map.of();
        return jdbc.query("select tenant_id, count(*) as total from custody_address where tenant_id=any(?) group by tenant_id",
                rs -> {
                    Map<UUID, Long> counts = new java.util.HashMap<>();
                    while (rs.next()) counts.put(rs.getObject("tenant_id", UUID.class), rs.getLong("total"));
                    return counts;
                }, new SqlArrayValue("uuid", ids.toArray()));
    }

    private static final String COLUMNS = """
            id, tenant_id, chain_address_id, chain, network, address, memo, subject, label,
            metadata::text as metadata_json, source, status, derivation_subject, address_version,
            derivation_child, created_at, updated_at
            """;
    private static final String QUERY_WHERE = """
             where tenant_id = ? and (? = '' or chain = ?) and (? = '' or source = ?)
               and (? = '' or status = ?) and not (id = any (?))
               and (? = '' or position(? in lower(concat_ws(' ', address, subject, label))) > 0)
            """;
    /** JDBC 模板。 */
    public boolean existsOtherNetwork(String chain,String network) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from custody_address where chain=? and network<>?)",Boolean.class,chain,network));
    }

    /** Exact ownership filter, applied before history pagination. */
    public List<UUID> findSubjectIds(UUID tenantId, String chain, String subject) {
        return jdbc.queryForList("select id from custody_address where tenant_id=? and subject=? and (?='' or chain=?)",
                UUID.class, tenantId, subject, chain, chain);
    }

    private final JdbcTemplate jdbc;

    /** 构造托管地址仓储。 */
    public CustodyAddressRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<UUID> findHistorySearchIds(UUID tenantId, String chain, String search) {
        return jdbc.queryForList("""
                select id from custody_address where tenant_id = ? and (? = '' or chain = ?)
                   and position(? in lower(concat_ws(' ', address, subject))) > 0
                """, UUID.class, tenantId, chain, chain, search);
    }

    public List<HistoryAddress> findHistoryAddresses(UUID tenantId, List<UUID> ids) {
        if (ids.isEmpty()) return List.of();
        return jdbc.query("""
                select id, address, subject from custody_address where tenant_id = ? and id = any (?)
                """, (rs, rowNum) -> new HistoryAddress(rs.getObject("id", UUID.class),
                        rs.getString("address"), rs.getString("subject")),
                tenantId, new SqlArrayValue("uuid", ids.toArray()));
    }

    public record HistoryAddress(UUID id, String address, String subject) { }

    /** 判断指定租户的托管地址是否来自 API。 */
    public boolean hasApiSource(UUID tenantId, UUID custodyAddressId) {
        Boolean result = jdbc.queryForObject("""
                select exists(
                    select 1 from custody_address
                     where tenant_id = ? and id = ? and source = 'API')
                """, Boolean.class, tenantId, custodyAddressId);
        return Boolean.TRUE.equals(result);
    }

    /** 查询租户指定托管地址的单表字段。 */
    public List<CustodyAddressRecord> listByTenantAndAddress(UUID tenantId, String address,
                                                             String preferredChain) {
        return jdbc.query("select " + COLUMNS + """
                  from custody_address
                 where tenant_id = ? and lower(address) = lower(?)
                 order by case when chain = ? then 0 else 1 end, created_at, id
                """, (rs, rowNum) -> mapAddress(rs), tenantId, address, preferredChain);
    }

    /** 查询启用的托管地址及其链地址主键。 */
    public List<AddressLink> listActiveByChain(String chain) {
        return jdbc.query("""
                select id, chain_address_id
                  from custody_address where chain = ? and status = 'ACTIVE'
                """, (rs, rowNum) -> new AddressLink(rs.getObject("id", UUID.class), rs.getLong("chain_address_id")), chain);
    }

    public record AddressLink(UUID id, long chainAddressId) { }

    /** 创建托管地址。 */
    public int insert(UUID id, UUID tenantId, long chainAddressId, String chain, String network,
                      String address, String memo, String subject, String label, String metadataJson,
                      String source, int derivationSubject, long addressVersion, long derivationChild,
                      UUID createdBy) {
        return jdbc.update("""
                insert into custody_address(id, tenant_id, chain_address_id, chain, network, address, memo,
                    subject, label, metadata, source, status, derivation_subject, address_version,
                    derivation_child, created_by, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, coalesce(?::jsonb, '{}'::jsonb), ?, 'ACTIVE', ?, ?, ?, ?, now(), now())
                """, id, tenantId, chainAddressId, chain, network, address, memo, subject, label, metadataJson,
                source, derivationSubject, addressVersion, derivationChild, createdBy);
    }

    public CustodyAddressRecord require(UUID tenantId, UUID id) {
        return findFullByTenantAndId(tenantId, id)
                .orElseThrow(() -> new IllegalArgumentException("custody address not found"));
    }

    public Optional<CustodyAddressRecord> findFullByTenantAndId(UUID tenantId, UUID id) {
        return jdbc.query("select " + COLUMNS + " from custody_address where tenant_id = ? and id = ?",
                (rs, rowNum) -> mapAddress(rs), tenantId, id).stream().findFirst();
    }

    public Optional<CustodyAddressRecord> findBySubjectAndVersion(UUID tenantId, String chain,
                                                                  String subject, long version) {
        return jdbc.query("select " + COLUMNS + """
                 from custody_address where tenant_id = ? and chain = ? and subject = ? and address_version = ?
                 order by id limit 1
                """, (rs, rowNum) -> mapAddress(rs), tenantId, chain, subject, version).stream().findFirst();
    }

    public List<CustodyAddressRecord> listActiveBySubject(UUID tenantId, String chain, String subject) {
        return jdbc.query("select " + COLUMNS + """
                 from custody_address where tenant_id = ? and chain = ? and subject = ? and status = 'ACTIVE'
                 order by address_version desc, id
                """, (rs, rowNum) -> mapAddress(rs), tenantId, chain, subject);
    }

    /** 更新托管地址业务字段。 */
    public int update(UUID tenantId, UUID id, String label, String memo, String status,
                      String metadataJson) {
        return jdbc.update("""
                update custody_address set label = ?, memo = ?, status = ?, metadata = coalesce(?::jsonb, metadata),
                    updated_at = now() where tenant_id = ? and id = ?
                """, label, memo, status, metadataJson, tenantId, id);
    }

    public List<CustodyAddressRecord> query(Query query, int limit, int offset) {
        var args = queryArgs(query);
        args.add(Math.min(Math.max(limit, 1), 200));
        args.add(Math.max(offset, 0));
        return jdbc.query("select " + COLUMNS + " from custody_address " + QUERY_WHERE
                + " order by created_at desc, id desc limit ? offset ?",
                (rs, rowNum) -> mapAddress(rs), args.toArray());
    }

    public long count(Query query) {
        return jdbc.queryForObject("select count(*) from custody_address " + QUERY_WHERE,
                Long.class, queryArgs(query).toArray());
    }

    private static ArrayList<Object> queryArgs(Query query) {
        var args = new ArrayList<Object>();
        args.add(query.tenantId());
        args.add(query.chain()); args.add(query.chain());
        args.add(query.source()); args.add(query.source());
        args.add(query.status()); args.add(query.status());
        args.add(new SqlArrayValue("uuid", query.excludedAddressIds().toArray()));
        args.add(query.search()); args.add(query.search());
        return args;
    }

    public record Query(UUID tenantId, String chain, String source, String status, String search,
                        List<UUID> excludedAddressIds) {
        public Query {
            Objects.requireNonNull(tenantId, "tenantId");
            chain = upper(chain); source = upper(source); status = upper(status);
            search = search == null ? "" : search.toLowerCase(Locale.ROOT);
            excludedAddressIds = List.copyOf(excludedAddressIds);
        }
        private static String upper(String value) {
            return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        }
    }

    private static CustodyAddressRecord mapAddress(ResultSet rs) throws SQLException {
        return new CustodyAddressRecord(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getLong("chain_address_id"), rs.getString("chain"), rs.getString("network"),
                rs.getString("address"), Objects.toString(rs.getString("memo"), ""), rs.getString("subject"),
                Objects.toString(rs.getString("label"), ""),
                rs.getString("metadata_json"), rs.getString("source"), rs.getString("status"),
                rs.getInt("derivation_subject"), rs.getLong("address_version"), rs.getLong("derivation_child"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }

    public java.util.Optional<DepositOwner> findDepositOwner(
            UUID tenantId, String chain, List<Long> chainAddressIds, String preferredAddress) {
        if (chainAddressIds.isEmpty()) return java.util.Optional.empty();
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        args.add(chain);
        args.addAll(chainAddressIds);
        args.add(preferredAddress);
        String ids = String.join(",", java.util.Collections.nCopies(chainAddressIds.size(), "?"));
        return jdbc.query("""
                select id, subject, address, memo, source from custody_address
                 where tenant_id = ? and chain = ? and chain_address_id in (
                """ + ids + """
                ) order by case when lower(address) = lower(?) then 0 else 1 end,
                           address_version desc, created_at desc limit 1
                """, (rs, rowNum) -> mapDepositOwner(rs), args.toArray()).stream().findFirst();
    }

    public java.util.Optional<DepositOwner> lockDepositOwnerById(UUID tenantId, UUID id) {
        return jdbc.query("""
                select id, subject, address, memo, source from custody_address
                 where tenant_id = ? and id = ? for update
                """, (rs, rowNum) -> mapDepositOwner(rs), tenantId, id).stream().findFirst();
    }

    private static DepositOwner mapDepositOwner(ResultSet rs) throws SQLException {
        return new DepositOwner(rs.getObject("id", UUID.class), rs.getString("subject"),
                rs.getString("address"), rs.getString("memo"), rs.getString("source"));
    }

    public record DepositOwner(UUID id, String subject, String address, String memo, String source) { }

}
