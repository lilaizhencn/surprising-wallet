package com.surprising.wallet.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.time.Instant;
import java.sql.Timestamp;

/** custody_address 单表仓储。 */
@Repository
public class CustodyAddressRepository {
    /** JDBC 模板。 */
    private final JdbcTemplate jdbc;

    /** 构造托管地址仓储。 */
    public CustodyAddressRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

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
    public List<Map<String, Object>> listByTenantAndAddress(UUID tenantId, String address,
                                                             String preferredChain) {
        return jdbc.queryForList("""
                select id, chain_address_id, chain, created_at
                  from custody_address
                 where tenant_id = ? and lower(address) = lower(?)
                 order by case when chain = ? then 0 else 1 end, created_at, id
                """, tenantId, address, preferredChain);
    }

    /** 查询租户托管地址及其链地址主键。 */
    public Optional<Map<String, Object>> findByTenantAndId(UUID tenantId, UUID custodyAddressId) {
        return jdbc.queryForList("""
                select id, tenant_id, chain_address_id, chain, network, address, status,
                       derivation_subject, source
                  from custody_address
                where tenant_id = ? and id = ?
                """, tenantId, custodyAddressId).stream().findFirst();
    }

    /** 查询租户全部托管地址字段，供服务层组合链地址和账本数据。 */
    public List<Map<String, Object>> listByTenant(UUID tenantId) {
        return jdbc.queryForList("""
                select id, tenant_id, chain_address_id, chain, network, address,
                       memo, subject, label, status, source
                  from custody_address
                 where tenant_id = ?
                """, tenantId);
    }

    /** 查询全部托管地址字段，供开发水龙头服务组合租户和链配置。 */
    public List<Map<String, Object>> listAll() {
        return jdbc.queryForList("""
                select id, tenant_id, chain, network, address, status, source
                  from custody_address
                """);
    }

    /** 查询启用的托管地址及其链地址主键。 */
    public List<Map<String, Object>> listActiveByChain(String chain) {
        return jdbc.queryForList("""
                select id, tenant_id, chain_address_id, chain, network, address, status
                  from custody_address where chain = ? and status = 'ACTIVE'
                """, chain);
    }

    /** 查询租户指定链和地址的启用托管地址。 */
    public Optional<Map<String, Object>> findActiveByTenantAndAddress(UUID tenantId, String chain,
                                                                       String address) {
        return jdbc.queryForList("""
                select id, tenant_id, chain_address_id, chain, network, address, status
                  from custody_address
                 where tenant_id = ? and chain = ? and lower(address) = lower(?) and status = 'ACTIVE'
                 order by id limit 1
                """, tenantId, chain, address).stream().findFirst();
    }

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

    /** 按租户和主键查询完整托管地址。 */
    public Optional<Map<String, Object>> findFullByTenantAndId(UUID tenantId, UUID id) {
        return jdbc.queryForList("""
                select id, tenant_id, chain_address_id, chain, network, address, memo, subject, label,
                       metadata::text as metadata_json, source, status, derivation_subject, address_version,
                       derivation_child, created_at, updated_at
                  from custody_address where tenant_id = ? and id = ?
                """, tenantId, id).stream().findFirst();
    }

    /** 按租户、subject 和版本查询托管地址。 */
    public Optional<Map<String, Object>> findBySubjectAndVersion(UUID tenantId, String chain,
                                                                  String subject, long version) {
        return jdbc.queryForList("""
                select id, tenant_id, chain_address_id, chain, network, address, memo, subject, label,
                       metadata::text as metadata_json, source, status, derivation_subject, address_version,
                       derivation_child, created_at, updated_at
                  from custody_address
                 where tenant_id = ? and chain = ? and subject = ? and address_version = ?
                 order by id limit 1
                """, tenantId, chain, subject, version).stream().findFirst();
    }

    /** 更新托管地址业务字段。 */
    public int update(UUID tenantId, UUID id, String label, String memo, String status,
                      String metadataJson) {
        return jdbc.update("""
                update custody_address set label = ?, memo = ?, status = ?, metadata = coalesce(?::jsonb, metadata),
                    updated_at = now() where tenant_id = ? and id = ?
                """, label, memo, status, metadataJson, tenantId, id);
    }

    /** 按条件查询租户托管地址。 */
    public List<Map<String, Object>> list(UUID tenantId, String chain, String source,
                                          String status, int limit, int offset) {
        return jdbc.queryForList("""
                select id, tenant_id, chain_address_id, chain, network, address, memo, subject, label,
                       metadata::text as metadata_json, source, status, derivation_subject, address_version,
                       derivation_child, created_at, updated_at
                  from custody_address
                 where tenant_id = ? and (cast(? as varchar) is null or chain = ?) and (cast(? as varchar) is null or source = ?)
                   and (cast(? as varchar) is null or status = ?)
                 order by created_at desc, id desc limit ? offset ?
                """, tenantId, chain, chain, source, source, status, status,
                Math.min(Math.max(limit, 1), 500), Math.max(offset, 0));
    }

    /** 统计租户托管地址数量。 */
    public long count(UUID tenantId, String chain, String source, String status) {
        Long count = jdbc.queryForObject("""
                select count(*) from custody_address
                 where tenant_id = ? and (cast(? as varchar) is null or chain = ?) and (cast(? as varchar) is null or source = ?)
                   and (cast(? as varchar) is null or status = ?)
                """, Long.class, tenantId, chain, chain, source, source, status, status);
        return count == null ? 0 : count;
    }

    /** 将数据库地址字段转换为接口字段。 */
    public static String text(Object value) {
        return value == null ? null : value.toString();
    }

    /** 将数据库地址字段转换为时间。 */
    public static Instant instant(Object value) {
        return value instanceof Timestamp timestamp ? timestamp.toInstant()
                : value instanceof java.time.OffsetDateTime offset ? offset.toInstant() : null;
    }
}
