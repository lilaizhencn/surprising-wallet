package com.surprising.wallet.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/** custody_derivation_subject 单表仓储。 */
@Repository
public class CustodyDerivationSubjectRepository {
    /** JDBC 模板。 */
    private final JdbcTemplate jdbc;

    /** 构造派生主题仓储。 */
    public CustodyDerivationSubjectRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 幂等获取租户主题的派生编号。 */
    public int resolve(UUID tenantId, String subject) {
        jdbc.update("""
                insert into custody_derivation_subject(tenant_id, subject)
                values (?, ?) on conflict (tenant_id, subject) do nothing
                """, tenantId, subject);
        Integer value = jdbc.queryForObject("""
                select derivation_subject from custody_derivation_subject
                 where tenant_id = ? and subject = ?
                """, Integer.class, tenantId, subject);
        if (value == null) throw new IllegalStateException("derivation subject allocation failed");
        return value;
    }
}
