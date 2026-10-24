package com.surprising.wallet.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/**
 * evm_7702_config 单表仓储。
 */
@Repository
public class Evm7702ConfigRepository {

    private static final String COLUMNS = """
        id, chain, network, chain_id, version, delegate_address, delegate_code_hash,
        collector_address, collector_code_hash, payout_delegate_address, payout_delegate_code_hash,
        relayer_address, relayer_chain_address_id, status, max_batch_items, max_batch_gas,
        block_gas_ratio, gas_limit_multiplier, signature_ttl_seconds, required_confirmations,
        native_collection_enabled, batch_withdrawal_enabled, withdrawal_max_wait_ms,
        withdrawal_max_batch_items
        """;

    /**
     * JDBC 模板。
     */
    private final JdbcTemplate jdbc;

    /**
     * 构造 EIP-7702 配置仓储。
     */
    public Evm7702ConfigRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 判断指定链和网络是否存在启用的 EIP-7702 配置。
     */
    public boolean existsActive(String chain, String network) {
        return !jdbc.queryForList("""
            select id
              from evm_7702_config
             where lower(chain) = lower(?) and lower(network) = lower(?)
               and status = 'ACTIVE'
             limit 1
            """, chain, network).isEmpty();
    }

    /**
     * 判断配置是否由 EIP-7702 托管。
     */
    public boolean existsManaged(String chain, String network) {
        return !jdbc.queryForList("""
            select id from evm_7702_config
             where lower(chain) = lower(?) and lower(network) = lower(?)
               and status in ('ACTIVE', 'PAUSED') limit 1
            """, chain, network).isEmpty();
    }

    /**
     * 判断批量提现是否由 EIP-7702 接管，包括启用和暂停状态。
     */
    public boolean existsManagedBatchWithdrawal(String chain, String network) {
        return !jdbc.queryForList("""
            select id from evm_7702_config
             where lower(chain) = lower(?) and lower(network) = lower(?)
               and status in ('ACTIVE', 'PAUSED')
               and batch_withdrawal_enabled = true
             limit 1
            """, chain, network).isEmpty();
    }

    /**
     * 判断原生资产归集是否启用。
     */
    public boolean existsActiveNativeCollection(String chain, String network) {
        return !jdbc.queryForList("""
            select id from evm_7702_config
             where lower(chain) = lower(?) and lower(network) = lower(?)
               and status = 'ACTIVE' and native_collection_enabled = true limit 1
            """, chain, network).isEmpty();
    }

    /**
     * 判断批量提现是否启用。
     */
    public boolean existsActiveBatchWithdrawal(String chain, String network) {
        return !jdbc.queryForList("""
            select id from evm_7702_config
             where lower(chain) = lower(?) and lower(network) = lower(?)
               and status = 'ACTIVE' and batch_withdrawal_enabled = true limit 1
            """, chain, network).isEmpty();
    }

    /**
     * 查询完整 EIP-7702 配置字段。
     */
    public List<Configuration> find(String chain, String network, String status, Integer version) {
        StringBuilder predicate = new StringBuilder("chain = ? and network = ?");
        List<Object> args = new java.util.ArrayList<>(List.of(chain, network));
        if (status != null) {
            predicate.append(" and status = ?");
            args.add(status);
        }
        if (version != null) {
            predicate.append(" and version = ?");
            args.add(version);
        }
        return jdbc.query(
                "select " + COLUMNS + " from evm_7702_config where " + predicate,
                Evm7702ConfigRepository::mapConfiguration,
                args.toArray());
    }

    /**
     * 查询全部配置，供服务层与链配置组合运行目标。
     */
    public List<Configuration> listAll() {
        return jdbc.query("select " + COLUMNS + " from evm_7702_config", Evm7702ConfigRepository::mapConfiguration);
    }

    private static Configuration mapConfiguration(ResultSet rs, int row) throws SQLException {
        return new Configuration(
                rs.getObject("id", UUID.class),
                rs.getString("chain"),
                rs.getString("network"),
                rs.getBigDecimal("chain_id").toBigIntegerExact(),
                rs.getInt("version"),
                rs.getString("delegate_address"),
                rs.getString("delegate_code_hash"),
                rs.getString("collector_address"),
                rs.getString("collector_code_hash"),
                rs.getString("payout_delegate_address"),
                rs.getString("payout_delegate_code_hash"),
                rs.getString("relayer_address"),
                rs.getLong("relayer_chain_address_id"),
                rs.getString("status"),
                rs.getInt("max_batch_items"),
                rs.getLong("max_batch_gas"),
                rs.getBigDecimal("block_gas_ratio"),
                rs.getBigDecimal("gas_limit_multiplier"),
                rs.getInt("signature_ttl_seconds"),
                rs.getInt("required_confirmations"),
                rs.getBoolean("native_collection_enabled"),
                rs.getBoolean("batch_withdrawal_enabled"),
                rs.getInt("withdrawal_max_wait_ms"),
                rs.getInt("withdrawal_max_batch_items"));
    }

    public record Configuration(
            UUID id,
            String chain,
            String network,
            BigInteger chainId,
            int version,
            String delegateAddress,
            String delegateCodeHash,
            String collectorAddress,
            String collectorCodeHash,
            String payoutDelegateAddress,
            String payoutDelegateCodeHash,
            String relayerAddress,
            long relayerChainAddressId,
            String status,
            int maxBatchItems,
            long maxBatchGas,
            BigDecimal blockGasRatio,
            BigDecimal gasLimitMultiplier,
            int signatureTtlSeconds,
            int requiredConfirmations,
            boolean nativeCollectionEnabled,
            boolean batchWithdrawalEnabled,
            int withdrawalMaxWaitMs,
            int withdrawalMaxBatchItems) {
    }
}
