package com.surprising.wallet.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

/** custody_tenant_chain 单表仓储。 */
@Repository
public class CustodyTenantChainTableRepository {
    /** JDBC 模板。 */
    private final JdbcTemplate jdbc;

    /** 构造租户链单表仓储。 */
    public CustodyTenantChainTableRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 查询租户的链状态记录。 */
    public List<ChainRow> list(UUID tenantId) {
        return jdbc.query("""
                select chain, status, opened_at, closed_at
                  from custody_tenant_chain
                 where tenant_id = ?
                """, (rs, rowNum) -> new ChainRow(
                rs.getString("chain"), rs.getString("status"),
                instant(rs.getTimestamp("opened_at")), instant(rs.getTimestamp("closed_at"))), tenantId);
    }

    /** 判断租户是否已启用指定链。 */
    public boolean active(UUID tenantId, String chain) {
        return !jdbc.queryForList("""
                select chain
                  from custody_tenant_chain
                 where tenant_id = ? and upper(chain) = upper(?) and status = 'ACTIVE'
                """, tenantId, chain).isEmpty();
    }

    /** 查询租户当前已启用的链名称。 */
    public List<String> listActiveChains(UUID tenantId) {
        return jdbc.queryForList("""
                select chain
                  from custody_tenant_chain
                 where tenant_id = ? and status = 'ACTIVE'
                """, String.class, tenantId);
    }

    /** 更新租户链启用状态。 */
    public void setStatus(UUID tenantId, String chain, String status, UUID actorId) {
        jdbc.update("""
                insert into custody_tenant_chain(
                    tenant_id, chain, status, opened_by, opened_at,
                    closed_by, closed_at, updated_at)
                values (?, ?, ?,
                        case when ? = 'ACTIVE' then ?::uuid end,
                        case when ? = 'ACTIVE' then now() end,
                        case when ? = 'CLOSED' then ?::uuid end,
                        case when ? = 'CLOSED' then now() end,
                        now())
                on conflict (tenant_id, chain) do update set
                    status = excluded.status,
                    opened_by = case when excluded.status = 'ACTIVE' then excluded.opened_by
                                     else custody_tenant_chain.opened_by end,
                    opened_at = case when excluded.status = 'ACTIVE' then now()
                                     else custody_tenant_chain.opened_at end,
                    closed_by = case when excluded.status = 'CLOSED' then excluded.closed_by
                                     else null end,
                    closed_at = case when excluded.status = 'CLOSED' then now() else null end,
                    updated_at = now()
                """, tenantId, chain, status,
                status, actorId, status, status, actorId, status);
    }

    /** 转换可空时间字段。 */
    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    /** 租户链数据库记录。 */
    public record ChainRow(
            String chain,
            String status,
            Instant openedAt,
            Instant closedAt
    ) {
    }
}
