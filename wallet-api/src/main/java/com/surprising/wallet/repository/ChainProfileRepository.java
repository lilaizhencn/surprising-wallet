package com.surprising.wallet.repository;

import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.chain.model.BitcoinLikeChainProfile;
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

    /** 查询启用的 Bitcoin-like 链配置。 */
    public Optional<BitcoinLikeChainProfile> findBitcoinLike(String chain, String network) {
        return jdbc.query("""
                select chain, network, family, runtime_currency_id, bip44_coin_type, native_symbol,
                       rpc_url, explorer_url, deposit_confirmations, withdraw_confirmations, default_fee_rate,
                       dust_threshold, enabled, chain_id, gas_policy, scan_batch_size, scan_enabled,
                       withdraw_enabled, collection_enabled, transfer_enabled, scan_start_height, scan_max_blocks_per_run
                  from chain_profile where chain = ? and network = ? and enabled = true
                """, (rs, rowNum) -> BitcoinLikeChainProfile.builder()
                .chain(rs.getString("chain")).network(rs.getString("network")).family(rs.getString("family"))
                .runtimeCurrencyId(rs.getInt("runtime_currency_id")).bip44CoinType(rs.getInt("bip44_coin_type"))
                .nativeSymbol(rs.getString("native_symbol")).rpcUrl(rs.getString("rpc_url"))
                .explorerUrl(rs.getString("explorer_url")).depositConfirmations(rs.getInt("deposit_confirmations"))
                .withdrawConfirmations(rs.getInt("withdraw_confirmations"))
                .defaultFeeRate(rs.getObject("default_fee_rate", Long.class))
                .dustThreshold(rs.getObject("dust_threshold", Long.class)).enabled(rs.getBoolean("enabled"))
                .chainId(rs.getObject("chain_id", Long.class)).gasPolicy(rs.getString("gas_policy"))
                .scanBatchSize(rs.getObject("scan_batch_size", Integer.class)).scanEnabled(rs.getBoolean("scan_enabled"))
                .withdrawEnabled(rs.getBoolean("withdraw_enabled")).collectionEnabled(rs.getBoolean("collection_enabled"))
                .transferEnabled(rs.getBoolean("transfer_enabled"))
                .scanStartHeight(rs.getObject("scan_start_height", Long.class))
                .scanMaxBlocksPerRun(rs.getObject("scan_max_blocks_per_run", Long.class)).build(), chain, network)
                .stream().findFirst();
    }

    /** 查询启用的账户链配置。 */
    public Optional<AccountChainProfile> findAccount(String chain, String network) {
        return queryAccount("where chain = ? and network = ? and enabled = true", chain, network)
                .stream().findFirst();
    }

    /** 按运行时货币 ID 查询账户链配置。 */
    public Optional<AccountChainProfile> findAccountByRuntimeCurrency(int runtimeCurrencyId) {
        return queryAccount("""
                where runtime_currency_id = ? and enabled = true
                order by case network when 'regtest' then 0 when 'testnet' then 1
                    when 'testnet3' then 1 when 'devnet' then 1 else 2 end limit 1
                """, runtimeCurrencyId).stream().findFirst();
    }

    /** 按链名称查询账户链配置。 */
    public Optional<AccountChainProfile> findAccountByChain(String chain) {
        return queryAccount("""
                where upper(chain) = upper(?) and enabled = true
                order by case network when 'regtest' then 0 when 'testnet' then 1
                    when 'testnet3' then 1 when 'devnet' then 1 else 2 end limit 1
                """, chain).stream().findFirst();
    }

    /** 查询运行时货币对应的链名。 */
    public Optional<String> findChainByRuntimeCurrency(int runtimeCurrencyId) {
        return jdbc.queryForList("""
                select distinct chain from chain_profile
                 where runtime_currency_id = ? and enabled = true order by chain limit 1
                """, String.class, runtimeCurrencyId).stream().findFirst();
    }

    /** 查询运行时货币对应的网络名。 */
    public Optional<String> findNetworkByRuntimeCurrency(int runtimeCurrencyId) {
        return jdbc.queryForList("""
                select distinct network from chain_profile
                 where runtime_currency_id = ? and enabled = true order by network limit 1
                """, String.class, runtimeCurrencyId).stream().findFirst();
    }

    /** 判断运行时货币的链族。 */
    public boolean isRuntimeCurrencyFamily(int runtimeCurrencyId, String family) {
        return !jdbc.queryForList("""
                select 1 from chain_profile
                 where runtime_currency_id = ? and lower(family) = lower(?) and enabled = true limit 1
                """, runtimeCurrencyId, family).isEmpty();
    }

    /** 查询账户链配置。 */
    private List<AccountChainProfile> queryAccount(String predicate, Object... args) {
        String sql = """
                select chain, network, family, runtime_currency_id, bip44_coin_type, native_symbol,
                       rpc_url, explorer_url, deposit_confirmations, withdraw_confirmations, default_fee_rate,
                       dust_threshold, enabled, chain_id, gas_policy, fee_model, scan_batch_size, scan_enabled,
                       withdraw_enabled, collection_enabled, transfer_enabled, scan_start_height, scan_max_blocks_per_run
                  from chain_profile
                """ + predicate;
        return jdbc.query(sql, (rs, rowNum) -> AccountChainProfile.builder()
                .chain(rs.getString("chain")).network(rs.getString("network")).family(rs.getString("family"))
                .runtimeCurrencyId(rs.getInt("runtime_currency_id")).bip44CoinType(rs.getInt("bip44_coin_type"))
                .nativeSymbol(rs.getString("native_symbol")).rpcUrl(rs.getString("rpc_url"))
                .explorerUrl(rs.getString("explorer_url")).depositConfirmations(rs.getInt("deposit_confirmations"))
                .withdrawConfirmations(rs.getInt("withdraw_confirmations"))
                .defaultFee(rs.getObject("default_fee_rate", Long.class))
                .dustThreshold(rs.getObject("dust_threshold", Long.class)).enabled(rs.getBoolean("enabled"))
                .chainId(rs.getObject("chain_id", Long.class)).gasPolicy(rs.getString("gas_policy"))
                .feeModel(rs.getString("fee_model")).scanBatchSize(rs.getObject("scan_batch_size", Integer.class))
                .scanEnabled(rs.getBoolean("scan_enabled")).withdrawEnabled(rs.getBoolean("withdraw_enabled"))
                .collectionEnabled(rs.getBoolean("collection_enabled")).transferEnabled(rs.getBoolean("transfer_enabled"))
                .scanStartHeight(rs.getObject("scan_start_height", Long.class))
                .scanMaxBlocksPerRun(rs.getObject("scan_max_blocks_per_run", Long.class)).build(), args);
    }
}
