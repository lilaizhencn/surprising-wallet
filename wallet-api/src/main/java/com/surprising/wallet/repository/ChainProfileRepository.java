package com.surprising.wallet.repository;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * chain_profile 单表仓储。
 */
@Repository
public class ChainProfileRepository {
    /** JDBC 模板。 */
    private final JdbcTemplate jdbc;

    /** 构造链配置仓储。 */
    public ChainProfileRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 查询全部链配置。 */
    public List<Map<String, Object>> listAll() {
        return jdbc.queryForList("""
                select id, chain, network, family, runtime_currency_id, bip44_coin_type,
                       native_symbol, rpc_url, explorer_url, deposit_confirmations, withdraw_confirmations,
                       default_fee_rate, dust_threshold, enabled, chain_id, gas_policy, fee_model,
                       scan_batch_size, scan_enabled, withdraw_enabled, collection_enabled,
                       transfer_enabled, scan_start_height, scan_max_blocks_per_run,
                       created_at, updated_at
                  from chain_profile
                 order by chain, network
                """);
    }

    /** 按 ID 查询链配置。 */
    public Optional<Map<String, Object>> findById(long id) {
        try {
            return Optional.of(jdbc.queryForMap("""
                    select id, chain, network, family, runtime_currency_id, bip44_coin_type,
                           native_symbol, rpc_url, explorer_url, deposit_confirmations, withdraw_confirmations,
                           default_fee_rate, dust_threshold, enabled, chain_id, gas_policy, fee_model,
                           scan_batch_size, scan_enabled, withdraw_enabled, collection_enabled,
                           transfer_enabled, scan_start_height, scan_max_blocks_per_run,
                           created_at, updated_at
                      from chain_profile where id = ?
                    """, id));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    /** 按链和网络查询链配置。 */
    public Optional<Map<String, Object>> findByChainAndNetwork(String chain, String network) {
        try {
            return Optional.of(jdbc.queryForMap("""
                    select id, chain, network, family, chain_id, native_symbol,
                           default_fee_rate, dust_threshold, enabled,
                           scan_enabled, withdraw_enabled, collection_enabled, transfer_enabled
                      from chain_profile
                     where upper(chain) = upper(?) and lower(network) = lower(?)
                    """, chain, network));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    /** 查询用于 token 校验的链配置。 */
    public Optional<Map<String, Object>> findTokenProfile(String chain, String network) {
        return findByChainAndNetwork(chain, network);
    }

    /** 查询指定链上启用提现的链配置。 */
    public List<Map<String, Object>> listEnabledForWithdrawal(String chain) {
        return jdbc.queryForList("""
                select id, chain, network, family, native_symbol, default_fee_rate,
                       dust_threshold, enabled, withdraw_enabled
                  from chain_profile
                 where upper(chain) = upper(?) and enabled = true and withdraw_enabled = true
                 order by id
                """, chain);
    }

    /** 创建链配置并返回主键。 */
    public long insert(String chain, String network, String family, int runtimeCurrencyId,
                       int bip44CoinType, String nativeSymbol, String explorerUrl,
                       int depositConfirmations, int withdrawConfirmations, Long defaultFeeRate,
                       Long dustThreshold, boolean enabled, Long chainId, String gasPolicy,
                       String feeModel, int scanBatchSize, boolean scanEnabled,
                       boolean withdrawEnabled, boolean collectionEnabled, boolean transferEnabled,
                       long scanStartHeight, long scanMaxBlocksPerRun) {
        Long id = jdbc.queryForObject("""
                insert into chain_profile(
                    chain, network, family, runtime_currency_id, bip44_coin_type, native_symbol,
                    explorer_url, deposit_confirmations, withdraw_confirmations, default_fee_rate,
                    dust_threshold, enabled, chain_id, gas_policy, fee_model, scan_batch_size,
                    scan_enabled, withdraw_enabled, collection_enabled, transfer_enabled,
                    scan_start_height, scan_max_blocks_per_run, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
                returning id
                """, Long.class, chain, network, family, runtimeCurrencyId, bip44CoinType,
                nativeSymbol, explorerUrl, depositConfirmations, withdrawConfirmations,
                defaultFeeRate, dustThreshold, enabled, chainId, gasPolicy, feeModel,
                scanBatchSize, scanEnabled, withdrawEnabled, collectionEnabled, transferEnabled,
                scanStartHeight, scanMaxBlocksPerRun);
        return id;
    }

    /** 更新链配置。 */
    public int update(long id, String chain, String network, String family, int runtimeCurrencyId,
                      int bip44CoinType, String nativeSymbol, String explorerUrl,
                      int depositConfirmations, int withdrawConfirmations, Long defaultFeeRate,
                      Long dustThreshold, boolean enabled, Long chainId, String gasPolicy,
                      String feeModel, int scanBatchSize, boolean scanEnabled,
                      boolean withdrawEnabled, boolean collectionEnabled, boolean transferEnabled,
                      long scanStartHeight, long scanMaxBlocksPerRun) {
        return jdbc.update("""
                update chain_profile
                   set chain = ?, network = ?, family = ?, runtime_currency_id = ?,
                       bip44_coin_type = ?, native_symbol = ?, explorer_url = ?,
                       deposit_confirmations = ?, withdraw_confirmations = ?, default_fee_rate = ?,
                       dust_threshold = ?, enabled = ?, chain_id = ?, gas_policy = ?, fee_model = ?,
                       scan_batch_size = ?, scan_enabled = ?, withdraw_enabled = ?,
                       collection_enabled = ?, transfer_enabled = ?, scan_start_height = ?,
                       scan_max_blocks_per_run = ?, updated_at = now()
                 where id = ?
                """, chain, network, family, runtimeCurrencyId, bip44CoinType, nativeSymbol,
                explorerUrl, depositConfirmations, withdrawConfirmations, defaultFeeRate,
                dustThreshold, enabled, chainId, gasPolicy, feeModel, scanBatchSize,
                scanEnabled, withdrawEnabled, collectionEnabled, transferEnabled,
                scanStartHeight, scanMaxBlocksPerRun, id);
    }

    /** 更新链任务开关。 */
    public int updateSwitches(long id, boolean enabled, boolean scanEnabled,
                              boolean withdrawEnabled, boolean collectionEnabled,
                              boolean transferEnabled) {
        return jdbc.update("""
                update chain_profile
                   set enabled = ?, scan_enabled = ?, withdraw_enabled = ?,
                       collection_enabled = ?, transfer_enabled = ?, updated_at = now()
                 where id = ?
                """, enabled, scanEnabled, withdrawEnabled, collectionEnabled, transferEnabled, id);
    }

    /** 判断同一链是否存在其他启用网络。 */
    public boolean hasOtherEnabledNetwork(String chain, Long currentId) {
        Long count = jdbc.queryForObject("""
                select count(*) from chain_profile
                 where upper(chain) = upper(?) and enabled = true
                   and (cast(? as bigint) is null or id <> ?)
                """, Long.class, chain, currentId, currentId);
        return count != null && count > 0;
    }

    /** 锁定指定链的配置行，保证启用校验在当前事务内串行执行。 */
    public void lockChain(String chain) {
        jdbc.queryForList("select pg_advisory_xact_lock(hashtext(upper(?)))", chain);
        jdbc.queryForList("""
                select id from chain_profile
                 where upper(chain) = upper(?)
                 order by id
                 for update
                """, chain);
    }
}
