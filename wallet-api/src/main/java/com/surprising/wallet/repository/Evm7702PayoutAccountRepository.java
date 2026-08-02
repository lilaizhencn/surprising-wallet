package com.surprising.wallet.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigInteger;
import java.util.UUID;

/** evm_7702_payout_account 单表仓储。 */
@Repository
public class Evm7702PayoutAccountRepository {
    /** JDBC 模板。 */
    private final JdbcTemplate jdbc;

    /** 构造 EIP-7702 提现账户仓储。 */
    public Evm7702PayoutAccountRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 创建或更新提现账户投影。 */
    public int upsert(UUID id, UUID tenantId, String chain, String network, long chainAddressId,
                      String authorityAddress) {
        return jdbc.update("""
                insert into evm_7702_payout_account(id, tenant_id, chain, network, chain_address_id, authority_address)
                values (?, ?, ?, ?, ?, ?)
                on conflict (tenant_id, chain) do update set chain_address_id = excluded.chain_address_id,
                    authority_address = excluded.authority_address, network = excluded.network, updated_at = now()
                """, id, tenantId, chain, network, chainAddressId, authorityAddress);
    }

    /** 更新正常完成后的提现账户投影。 */
    public int markCompleted(UUID tenantId, String chain, boolean authorizationIncluded,
                             BigInteger authorizationNonce, BigInteger operationNonce,
                             String activationTxHash, String delegateAddress, int delegateVersion) {
        return jdbc.update("""
                update evm_7702_payout_account
                   set delegation_status = 'ACTIVE', delegate_address = ?, delegate_version = ?,
                       activation_tx_hash = case when ? then coalesce(activation_tx_hash, ?) else activation_tx_hash end,
                       observed_authority_nonce = case when ? then greatest(coalesce(observed_authority_nonce, 0), coalesce(?, 0))
                           else observed_authority_nonce end,
                       observed_operation_nonce = coalesce(?, observed_operation_nonce), updated_at = now()
                 where tenant_id = ? and chain = ?
                """, delegateAddress, delegateVersion, authorizationIncluded, activationTxHash,
                authorizationIncluded, authorizationNonce, operationNonce, tenantId, chain);
    }

    /** 更新外层回滚后的提现账户投影。 */
    public int markReverted(UUID tenantId, String chain, boolean authorizationIncluded,
                            BigInteger authorizationNonce, BigInteger operationNonce,
                            String activationTxHash, String delegateAddress, int delegateVersion) {
        return jdbc.update("""
                update evm_7702_payout_account
                   set delegation_status = case when ? then 'ACTIVE' else delegation_status end,
                       delegate_address = case when ? then ? else delegate_address end,
                       delegate_version = case when ? then ? else delegate_version end,
                       activation_tx_hash = case when ? then coalesce(activation_tx_hash, ?) else activation_tx_hash end,
                       observed_authority_nonce = case when ? then greatest(coalesce(observed_authority_nonce, 0), coalesce(?, 0))
                           else observed_authority_nonce end,
                       observed_operation_nonce = coalesce(?, observed_operation_nonce), updated_at = now()
                 where tenant_id = ? and chain = ?
                """, authorizationIncluded, authorizationIncluded, delegateAddress, authorizationIncluded,
                delegateVersion, authorizationIncluded, activationTxHash, authorizationIncluded, authorizationNonce,
                operationNonce, tenantId, chain);
    }
}
