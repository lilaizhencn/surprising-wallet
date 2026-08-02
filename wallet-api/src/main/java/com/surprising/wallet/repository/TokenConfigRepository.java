package com.surprising.wallet.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** token_config 单表仓储。 */
@Repository
public class TokenConfigRepository {
    /** JDBC 模板。 */
    private final JdbcTemplate jdbc;

    /** 构造代币配置仓储。 */
    public TokenConfigRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 查询全部代币配置。 */
    public List<Map<String, Object>> listAll() {
        return jdbc.queryForList("""
                select id, chain, network, symbol,
                       coalesce(nullif(token_standard, ''), standard) as standard,
                       contract_address, contract_address_base58, contract_address_hex,
                       decimals, enabled, collect_enabled,
                       coalesce(min_deposit_amount, min_deposit) as min_deposit,
                       coalesce(min_withdraw_amount, min_withdraw) as min_withdraw,
                       collect_threshold, gas_strategy, confirmation_required,
                       created_at, updated_at
                  from token_config order by chain, network, symbol
                """);
    }

    /** 按链和网络查询启用的代币配置。 */
    public List<Map<String, Object>> listEnabled(String chain, String network) {
        return listAll().stream()
                .filter(row -> chain.equalsIgnoreCase(String.valueOf(row.get("chain"))))
                .filter(row -> network.equalsIgnoreCase(String.valueOf(row.get("network"))))
                .filter(row -> Boolean.TRUE.equals(row.get("enabled"))).toList();
    }

    /** 按主键查询代币配置。 */
    public List<Map<String, Object>> findById(long id) {
        return listAll().stream().filter(row -> ((Number) row.get("id")).longValue() == id).toList();
    }

    /** 查询指定链网络下启用的代币配置。 */
    public Optional<Map<String, Object>> findEnabled(String chain, String network, String symbol) {
        return listAll().stream()
                .filter(row -> chain.equalsIgnoreCase(String.valueOf(row.get("chain"))))
                .filter(row -> network.equalsIgnoreCase(String.valueOf(row.get("network"))))
                .filter(row -> symbol.equalsIgnoreCase(String.valueOf(row.get("symbol"))))
                .filter(row -> Boolean.TRUE.equals(row.get("enabled"))).findFirst();
    }

    /** 创建代币配置并返回主键。 */
    public long insert(String chain, String network, String symbol, String standard,
                       String contractAddress, String contractAddressBase58, String contractAddressHex,
                       int decimals, boolean enabled, BigDecimal minDeposit, BigDecimal minWithdraw,
                       boolean collectEnabled, BigDecimal collectThreshold, String gasStrategy,
                       Integer confirmationRequired) {
        Long id = jdbc.queryForObject("""
                insert into token_config(chain, network, symbol, standard, token_standard, contract_address,
                    contract_address_base58, contract_address_hex, decimals, enabled, min_deposit, min_withdraw,
                    min_deposit_amount, min_withdraw_amount, collect_enabled, collect_threshold, gas_strategy,
                    confirmation_required, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now()) returning id
                """, Long.class, chain, network, symbol, standard, standard, contractAddress,
                contractAddressBase58, contractAddressHex, decimals, enabled, minDeposit, minWithdraw,
                minDeposit, minWithdraw, collectEnabled, collectThreshold, gasStrategy, confirmationRequired);
        return id;
    }

    /** 更新代币配置。 */
    public int update(long id, String chain, String network, String symbol, String standard,
                      String contractAddress, String contractAddressBase58, String contractAddressHex,
                      int decimals, boolean enabled, BigDecimal minDeposit, BigDecimal minWithdraw,
                      boolean collectEnabled, BigDecimal collectThreshold, String gasStrategy,
                      Integer confirmationRequired) {
        return jdbc.update("""
                update token_config set chain = ?, network = ?, symbol = ?, standard = ?, token_standard = ?,
                    contract_address = ?, contract_address_base58 = ?, contract_address_hex = ?, decimals = ?,
                    enabled = ?, min_deposit = ?, min_withdraw = ?, min_deposit_amount = ?,
                    min_withdraw_amount = ?, collect_enabled = ?, collect_threshold = ?, gas_strategy = ?,
                    confirmation_required = ?, updated_at = now() where id = ?
                """, chain, network, symbol, standard, standard, contractAddress, contractAddressBase58,
                contractAddressHex, decimals, enabled, minDeposit, minWithdraw, minDeposit, minWithdraw,
                collectEnabled, collectThreshold, gasStrategy, confirmationRequired, id);
    }

    /** 修改代币启用状态。 */
    public int updateEnabled(long id, boolean enabled, boolean collectEnabled) {
        return jdbc.update("update token_config set enabled = ?, collect_enabled = ?, updated_at = now() where id = ?",
                enabled, collectEnabled, id);
    }

    /** 将代币配置迁移到新的链或网络名称。 */
    public int moveChain(String currentChain, String currentNetwork, String targetChain, String targetNetwork) {
        return jdbc.update("""
                update token_config set chain = ?, network = ?, updated_at = now()
                 where chain = ? and network = ?
                """, targetChain, targetNetwork, currentChain, currentNetwork);
    }

    /** 统计指定链的代币数量。 */
    public long countByChain(String chain) {
        Long count = jdbc.queryForObject("select count(*) from token_config where upper(chain) = upper(?)",
                Long.class, chain);
        return count == null ? 0 : count;
    }

    /** 查询启用且允许归集的代币配置。 */
    public List<Map<String, Object>> listCollectEnabled(String chain, String network) {
        return jdbc.queryForList("""
                select id, chain, network, symbol, contract_address, decimals
                  from token_config
                 where chain = ? and (network = ? or network is null)
                   and enabled = true and collect_enabled = true
                """, chain, network);
    }

    /** 查询指定代币配置。 */
    public List<Map<String, Object>> find(String chain, String network, String symbol) {
        return jdbc.queryForList("""
                select id, chain, network, symbol, contract_address, decimals,
                       enabled, collect_enabled
                  from token_config
                 where chain = ? and symbol = ? and (network = ? or network is null)
                """, chain, symbol, network);
    }
}
