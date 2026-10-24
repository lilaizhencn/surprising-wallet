package com.surprising.wallet.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.UUID;

/** evm_7702_account 单表仓储。 */
@Repository
public class Evm7702AccountRepository {
    /** JDBC 模板。 */
    private final JdbcTemplate jdbc;

    /** 构造 EIP-7702 账户投影仓储。 */
    public Evm7702AccountRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 创建账户投影，重复调用保持幂等。 */
    public void createProjection(UUID tenantId, UUID custodyAddressId, String chain,
                                 String network, String authorityAddress) {
        jdbc.update("""
                insert into evm_7702_account(id, tenant_id, custody_address_id, chain, network, authority_address)
                values (?, ?, ?, ?, ?, ?)
                on conflict (tenant_id, custody_address_id, chain) do nothing
                """, UUID.randomUUID(), tenantId, custodyAddressId, chain, network, authorityAddress);
    }



    /** 判断指定托管地址是否已有 EIP-7702 账户投影。 */
    public boolean exists(UUID tenantId, UUID custodyAddressId, String chain, String network) {
        return !jdbc.queryForList("""
                select 1 from evm_7702_account
                 where tenant_id = ? and custody_address_id = ? and chain = ? and network = ? limit 1
                """, Integer.class, tenantId, custodyAddressId, chain, network).isEmpty();
    }

    /** 更新归集完成后的账户投影。 */
    public int markCollectionCompleted(UUID tenantId, UUID custodyAddressId, boolean authorizationIncluded,
                                       String activationTxHash, String delegateAddress,
                                       int delegateVersion, long operationNonce) {
        return jdbc.update("""
                update evm_7702_account
                   set delegation_status = 'ACTIVE',
                       activation_tx_hash = case when ? then coalesce(activation_tx_hash, ?) else activation_tx_hash end,
                       delegate_address = ?, delegate_version = ?,
                       observed_operation_nonce = case when ?
                           then greatest(coalesce(observed_operation_nonce, 0), coalesce(?, 0))
                           else observed_operation_nonce end,
                       updated_at = now()
                 where tenant_id = ? and custody_address_id = ?
                """, authorizationIncluded, activationTxHash, delegateAddress, delegateVersion,
                authorizationIncluded, operationNonce, tenantId, custodyAddressId);
    }

    /** 更新提现完成后的账户投影。 */
    public int markWithdrawalCompleted(UUID tenantId, UUID custodyAddressId, boolean authorizationIncluded,
                                       String activationTxHash, String delegateAddress,
                                       int delegateVersion, long operationNonce) {
        return markCollectionCompleted(tenantId, custodyAddressId, authorizationIncluded, activationTxHash,
                delegateAddress, delegateVersion, operationNonce);
    }
}
