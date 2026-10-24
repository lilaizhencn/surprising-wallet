package com.surprising.wallet.repository;

import com.surprising.wallet.chain.model.TokenConfiguration;
import com.surprising.wallet.common.chain.TokenDefinition;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/** token_config 单表仓储；运行时查询必须指定网络。 */
@Repository
public class TokenConfigRepository {
    private static final String COLUMNS = """
            id, chain, network, symbol, standard, contract_address,
            contract_address_base58, contract_address_hex, decimals, enabled, collect_enabled,
            min_deposit, min_withdraw, collect_threshold, gas_strategy, confirmation_required,
            created_at, updated_at
            """;
    private static final String NETWORK = "upper(chain) = upper(?) and lower(network) = lower(?)";
    private final JdbcTemplate jdbc;

    public TokenConfigRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<TokenConfiguration> listAll() {
        return query("order by chain, network, symbol");
    }

    public List<TokenConfiguration> listByChain(String chain) {
        return query("where upper(chain)=upper(?) order by network, symbol", chain);
    }

    public boolean existsByChainAndNetwork(String chain, String network) {
        return !jdbc.queryForList("select 1 from token_config where " + NETWORK + " limit 1", chain, network).isEmpty();
    }

    public List<TokenConfiguration> listByChainAndNetwork(String chain, String network) {
        return query("where " + NETWORK + " order by symbol", chain, network);
    }

    public List<TokenConfiguration> listEnabled(String chain, String network) {
        return query("where " + NETWORK + " and enabled = true order by symbol", chain, network);
    }

    public Optional<TokenConfiguration> lockById(long id) {
        return query("where id = ? for update", id).stream().findFirst();
    }

    public Optional<TokenConfiguration> findById(long id) {
        return query("where id = ?", id).stream().findFirst();
    }

    public Optional<TokenConfiguration> findEnabled(String chain, String network, String symbol) {
        return query("where " + NETWORK + " and upper(symbol) = upper(?) and enabled = true",
                chain, network, symbol).stream().findFirst();
    }

    public long insert(String chain, String network, String symbol, String standard,
                       String contractAddress, String contractAddressBase58, String contractAddressHex,
                       int decimals, boolean enabled, BigDecimal minDeposit, BigDecimal minWithdraw,
                       boolean collectEnabled, BigDecimal collectThreshold, String gasStrategy,
                       Integer confirmationRequired) {
        return jdbc.queryForObject("""
                insert into token_config(chain, network, symbol, standard, contract_address,
                    contract_address_base58, contract_address_hex, decimals, enabled, min_deposit, min_withdraw,
                    collect_enabled, collect_threshold, gas_strategy, confirmation_required)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id
                """, Long.class, chain, network, symbol, standard, contractAddress, contractAddressBase58,
                contractAddressHex, decimals, enabled, minDeposit, minWithdraw, collectEnabled,
                collectThreshold, gasStrategy, confirmationRequired);
    }

    public int update(long id, String chain, String network, String symbol, String standard,
                      String contractAddress, String contractAddressBase58, String contractAddressHex,
                      int decimals, boolean enabled, BigDecimal minDeposit, BigDecimal minWithdraw,
                      boolean collectEnabled, BigDecimal collectThreshold, String gasStrategy,
                      Integer confirmationRequired) {
        return jdbc.update("""
                update token_config set chain = ?, network = ?, symbol = ?, standard = ?,
                    contract_address = ?, contract_address_base58 = ?, contract_address_hex = ?, decimals = ?,
                    enabled = ?, min_deposit = ?, min_withdraw = ?, collect_enabled = ?, collect_threshold = ?,
                    gas_strategy = ?, confirmation_required = ?, updated_at = now() where id = ?
                """, chain, network, symbol, standard, contractAddress, contractAddressBase58, contractAddressHex,
                decimals, enabled, minDeposit, minWithdraw, collectEnabled, collectThreshold, gasStrategy,
                confirmationRequired, id);
    }

    public int updateEnabled(long id, boolean enabled, boolean collectEnabled) {
        return jdbc.update("update token_config set enabled = ?, collect_enabled = ?, updated_at = now() where id = ?",
                enabled, collectEnabled, id);
    }

    public int moveChain(String currentChain, String currentNetwork, String targetChain, String targetNetwork) {
        return jdbc.update("""
                update token_config set chain = ?, network = ?, updated_at = now()
                 where chain = ? and network = ?
                """, targetChain, targetNetwork, currentChain, currentNetwork);
    }

    public long countByChain(String chain) {
        return jdbc.queryForObject("select count(*) from token_config where upper(chain) = upper(?)", Long.class, chain);
    }

    public List<TokenConfiguration> listCollectEnabled(String chain, String network) {
        return query("where " + NETWORK + " and enabled = true and collect_enabled = true order by symbol", chain, network);
    }

    public Optional<TokenDefinition> findDefinition(String chain, String network, String symbol) {
        return findEnabled(chain, network, symbol).map(TokenConfigRepository::definition);
    }

    public Optional<TokenDefinition> findDefinitionByContract(String chain, String network, String contract) {
        return query("where " + NETWORK + """
                 and enabled = true and (lower(contract_address) = lower(?)
                     or lower(contract_address_base58) = lower(?) or lower(contract_address_hex) = lower(?))
                """, chain, network, contract, contract, contract).stream().findFirst().map(TokenConfigRepository::definition);
    }

    public List<TokenDefinition> listDefinitions(String chain, String network) {
        return listEnabled(chain, network).stream().map(TokenConfigRepository::definition).toList();
    }

    private List<TokenConfiguration> query(String clause, Object... args) {
        return jdbc.query("select " + COLUMNS + " from token_config " + clause,
                (rs, rowNum) -> map(rs), args);
    }

    private static TokenConfiguration map(ResultSet rs) throws SQLException {
        return new TokenConfiguration(rs.getLong("id"), rs.getString("chain"), rs.getString("network"),
                rs.getString("symbol"), rs.getString("standard"), rs.getString("contract_address"),
                rs.getString("contract_address_base58"), rs.getString("contract_address_hex"),
                rs.getInt("decimals"), rs.getBoolean("enabled"), rs.getBoolean("collect_enabled"),
                rs.getBigDecimal("min_deposit"), rs.getBigDecimal("min_withdraw"), rs.getBigDecimal("collect_threshold"),
                rs.getString("gas_strategy"), rs.getObject("confirmation_required", Integer.class),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }

    private static TokenDefinition definition(TokenConfiguration row) {
        return TokenDefinition.builder().id(row.id()).chain(row.chain()).network(row.network()).symbol(row.symbol())
                .standard(row.standard()).contractAddress(row.contractAddress()).decimals(row.decimals())
                .nativeAsset(false).active(row.enabled()).build();
    }
}
