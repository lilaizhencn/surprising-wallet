package com.surprising.wallet.approval.repository;

import com.surprising.wallet.approval.model.ApprovalPolicy;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** approval.policy only. Policy changes require the independent administrator. */
@Repository
public class ApprovalPolicyRepository {
    private final JdbcTemplate jdbc;
    public ApprovalPolicyRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public Optional<ApprovalPolicy> find(UUID tenant, String chain, String network, String asset) {
        return jdbc.query("select * from approval.policy where tenant_id=? and chain=? and network=? and asset_symbol=?",
                (r,n) -> map(r), tenant, chain, network, asset).stream().findFirst();
    }
    public List<ApprovalPolicy> list(UUID tenant) {
        return jdbc.query("select * from approval.policy where tenant_id=? order by chain,network,asset_symbol", (r,n) -> map(r), tenant);
    }
    public void insert(ApprovalPolicy p) {
        jdbc.update("""
                insert into approval.policy(id,tenant_id,chain,network,asset_symbol,version,single_amount,rolling_24h_amount,
                    all_withdrawals,new_recipient,console_withdrawal,required_reviewers,expiry_seconds) values (?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, p.id(),p.tenantId(),p.chain(),p.network(),p.assetSymbol(),p.version(),p.singleAmount(),p.rolling24hAmount(),
                p.allWithdrawals(),p.newRecipient(),p.consoleWithdrawal(),p.requiredReviewers(),p.expirySeconds());
    }
    public int update(ApprovalPolicy p, long expectedVersion) {
        return jdbc.update("""
                update approval.policy set version=?,single_amount=?,rolling_24h_amount=?,all_withdrawals=?,new_recipient=?,
                    console_withdrawal=?,required_reviewers=?,expiry_seconds=?,updated_at=now()
                where tenant_id=? and id=? and version=?
                """, p.version(),p.singleAmount(),p.rolling24hAmount(),p.allWithdrawals(),p.newRecipient(),p.consoleWithdrawal(),
                p.requiredReviewers(),p.expirySeconds(),p.tenantId(),p.id(),expectedVersion);
    }
    private static ApprovalPolicy map(java.sql.ResultSet r) throws java.sql.SQLException {
        return new ApprovalPolicy(r.getObject("id",UUID.class),r.getObject("tenant_id",UUID.class),r.getString("chain"),
                r.getString("network"),r.getString("asset_symbol"),r.getLong("version"),r.getBigDecimal("single_amount"),r.getBigDecimal("rolling_24h_amount"),
                r.getBoolean("all_withdrawals"),r.getBoolean("new_recipient"),r.getBoolean("console_withdrawal"),r.getInt("required_reviewers"),r.getInt("expiry_seconds"));
    }
}
