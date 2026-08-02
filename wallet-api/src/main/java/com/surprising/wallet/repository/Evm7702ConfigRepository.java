package com.surprising.wallet.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/** evm_7702_config 单表仓储。 */
@Repository
public class Evm7702ConfigRepository {
    /** JDBC 模板。 */
    private final JdbcTemplate jdbc;

    /** 构造 EIP-7702 配置仓储。 */
    public Evm7702ConfigRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 判断指定链和网络是否存在启用的 EIP-7702 配置。 */
    public boolean existsActive(String chain, String network) {
        return !jdbc.queryForList("""
                select id
                  from evm_7702_config
                 where lower(chain) = lower(?) and lower(network) = lower(?)
                   and status = 'ACTIVE'
                 limit 1
                """, chain, network).isEmpty();
    }

    /** 判断配置是否由 EIP-7702 托管。 */
    public boolean existsManaged(String chain, String network) {
        return !jdbc.queryForList("""
                select id from evm_7702_config
                 where lower(chain) = lower(?) and lower(network) = lower(?)
                   and status in ('ACTIVE', 'PAUSED') limit 1
                """, chain, network).isEmpty();
    }

    /** 判断原生资产归集是否启用。 */
    public boolean existsActiveNativeCollection(String chain, String network) {
        return !jdbc.queryForList("""
                select id from evm_7702_config
                 where lower(chain) = lower(?) and lower(network) = lower(?)
                   and status = 'ACTIVE' and native_collection_enabled = true limit 1
                """, chain, network).isEmpty();
    }

    /** 判断批量提现是否启用。 */
    public boolean existsActiveBatchWithdrawal(String chain, String network) {
        return !jdbc.queryForList("""
                select id from evm_7702_config
                 where lower(chain) = lower(?) and lower(network) = lower(?)
                   and status = 'ACTIVE' and batch_withdrawal_enabled = true limit 1
                """, chain, network).isEmpty();
    }

    /** 查询完整 EIP-7702 配置字段。 */
    public List<Map<String, Object>> find(String chain, String network, String status, Integer version) {
        String predicate = version == null
                ? "chain = ? and network = ? and status = ?"
                : "chain = ? and network = ? and version = ?";
        Object[] args = version == null ? new Object[]{chain, network, status} : new Object[]{chain, network, version};
        return jdbc.queryForList("""
                select id, chain, network, chain_id, version, delegate_address, delegate_code_hash,
                       collector_address, collector_code_hash, payout_delegate_address, payout_delegate_code_hash,
                       relayer_address, relayer_chain_address_id, status, max_batch_items, max_batch_gas,
                       block_gas_ratio, gas_limit_multiplier, signature_ttl_seconds, required_confirmations,
                       native_collection_enabled, batch_withdrawal_enabled, withdrawal_max_wait_ms,
                       withdrawal_max_batch_items
                  from evm_7702_config where """ + predicate, args);
    }

    /** 查询全部配置，供服务层与链配置组合运行目标。 */
    public List<Map<String, Object>> listAll() {
        return jdbc.queryForList("""
                select id, chain, network, status, native_collection_enabled, batch_withdrawal_enabled,
                       version, required_confirmations, collector_address, relayer_address
                  from evm_7702_config
                """);
    }
}
