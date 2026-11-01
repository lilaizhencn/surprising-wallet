package com.surprising.wallet.approval.repository;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** approval.tenant only; its row serializes cumulative threshold admission. */
@Repository
public class ApprovalTenantRepository {
    private final JdbcTemplate jdbc;
    public ApprovalTenantRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public void enroll(UUID tenant,String slug) {
        jdbc.update("insert into approval.tenant(id,slug) values (?,?) on conflict (id) do nothing",tenant,slug);
        String actual=jdbc.queryForObject("select slug from approval.tenant where id=? for update",String.class,tenant);
        if(!slug.equals(actual)) throw new IllegalArgumentException("existing independent tenant identity differs");
    }
    public boolean active(UUID tenant) {
        return !jdbc.queryForList("select id from approval.tenant where id=? and status='ACTIVE'", UUID.class, tenant).isEmpty();
    }
    public boolean lockActive(UUID tenant) {
        return !jdbc.queryForList("select id from approval.tenant where id=? and status='ACTIVE' for no key update", UUID.class, tenant).isEmpty();
    }
}
