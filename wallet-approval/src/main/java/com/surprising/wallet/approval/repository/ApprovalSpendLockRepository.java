package com.surprising.wallet.approval.repository;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** approval.spend_lock only. Original outpoint ownership is permanent, including after an unknown signature/broadcast. */
@Repository
public class ApprovalSpendLockRepository {
    private final JdbcTemplate jdbc;
    public ApprovalSpendLockRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public int claim(UUID tenant,UUID transaction,String chain,String network,String txId,int index) {
        return jdbc.update("insert into approval.spend_lock(tenant_id,transaction_id,chain,network,tx_id,output_index) values (?,?,?,?,?,?) on conflict do nothing",
                tenant,transaction,chain,network,txId,index);
    }
    public Optional<UUID> owner(UUID tenant,String chain,String network,String txId,int index) {
        return jdbc.query("select transaction_id from approval.spend_lock where tenant_id=? and chain=? and network=? and tx_id=? and output_index=?",
                (r,n)->r.getObject("transaction_id",UUID.class),tenant,chain,network,txId,index).stream().findFirst();
    }
}
