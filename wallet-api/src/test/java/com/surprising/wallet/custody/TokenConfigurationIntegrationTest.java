package com.surprising.wallet.custody;

import com.surprising.wallet.repository.ChainProfileRepository;
import com.surprising.wallet.repository.TokenConfigRepository;
import com.surprising.wallet.testutil.DepositTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "SW_TEST_CUSTODY_DB_URL", matches = ".+")
class TokenConfigurationIntegrationTest {
    private JdbcTemplate jdbc;

    @BeforeEach void setup() throws Exception {
        var ds = CustodyIntegrationDatabase.dataSource();
        CustodyIntegrationDatabase.reset(ds);
        jdbc = new JdbcTemplate(ds);
    }

    @Test void schemaHasOneStandardOneThresholdPairAndARequiredNetwork() {
        var columns = jdbc.queryForList("""
                select column_name from information_schema.columns
                 where table_schema = 'public' and table_name = 'token_config'
                """, String.class);
        assertTrue(columns.containsAll(List.of("standard", "min_deposit", "min_withdraw", "network")));
        assertFalse(columns.contains("token_standard"));
        assertFalse(columns.contains("min_deposit_amount"));
        assertFalse(columns.contains("min_withdraw_amount"));
        assertEquals("NO", jdbc.queryForObject("""
                select is_nullable from information_schema.columns
                 where table_schema = 'public' and table_name = 'token_config' and column_name = 'network'
                """, String.class));
    }

    @Test void runtimeNeverSelectsAnEnabledTokenFromAnotherNetwork() {
        String network = new ChainProfileRepository(jdbc).findAccountByChain("ETH").orElseThrow().getNetwork();
        var tokens = new TokenConfigRepository(jdbc);
        tokens.insert("ETH", "mainnet", "NETWORK_FENCE", "ERC20", "0x" + "98".repeat(20),
                null, null, 6, true, BigDecimal.ZERO, BigDecimal.ZERO, true, null, null, null);
        var runtime = DepositTestSupport.repository(jdbc);
        assertEquals("sepolia", network);
        assertTrue(runtime.findToken("ETH", "NETWORK_FENCE").isEmpty());
        assertTrue(runtime.findTokenByContract("ETH", "0x" + "98".repeat(20)).isEmpty());
        assertTrue(runtime.listTokens("ETH").stream().noneMatch(token -> token.getSymbol().equals("NETWORK_FENCE")));
    }

    @Test void tokenWithoutANetworkCannotEnterTheConfigurationTable() {
        var tokens = new TokenConfigRepository(jdbc);
        assertThrows(DataIntegrityViolationException.class, () -> tokens.insert(
                "ETH", null, "NULL_NETWORK", "ERC20", "0x" + "97".repeat(20), null, null,
                6, false, BigDecimal.ZERO, BigDecimal.ZERO, false, null, null, null));
        assertThrows(DataIntegrityViolationException.class, () -> tokens.insert(
                "ETH", "  ", "BLANK_NETWORK", "ERC20", "0x" + "96".repeat(20), null, null,
                6, false, BigDecimal.ZERO, BigDecimal.ZERO, false, null, null, null));
    }

    @Test void canonicalThresholdsAndMetadataSurviveCreateUpdateAndRuntimeProjection() {
        var tokens = new TokenConfigRepository(jdbc);
        BigDecimal minimum = new BigDecimal("0.000000000000000001");
        BigDecimal withdrawal = new BigDecimal("123456789012345678901234567890.123456789012345678");
        long id = tokens.insert("ETH", "sepolia", "EXACT_TOKEN", "ERC20", "0x" + "95".repeat(20),
                null, "0x95", 18, true, minimum, withdrawal, true, minimum, "NATIVE", 12);
        var row = tokens.findById(id).orElseThrow();
        assertEquals(0, minimum.compareTo(row.minDeposit()));
        assertEquals(0, withdrawal.compareTo(row.minWithdraw()));
        assertEquals(0, minimum.compareTo(row.collectThreshold()));
        assertEquals("ERC20", row.standard());
        assertEquals("NATIVE", row.gasStrategy());
        assertEquals(12, row.confirmationRequired());
        assertNotNull(row.createdAt());
        var runtime = tokens.findDefinition("eth", "SEPOLIA", "exact_token").orElseThrow();
        assertEquals(id, runtime.getId());
        assertEquals("sepolia", runtime.getNetwork());
        assertEquals(row.contractAddress(), runtime.getContractAddress());
        assertEquals(runtime, tokens.findDefinitionByContract("ETH", "sepolia", "0x95").orElseThrow());

        assertEquals(1, tokens.update(id, "ETH", "sepolia", "EXACT_TOKEN", "ERC20", row.contractAddress(),
                null, "0x95", 18, true, withdrawal, minimum, false, withdrawal, "RECEIPT", 3));
        row = tokens.findById(id).orElseThrow();
        assertEquals(0, withdrawal.compareTo(row.minDeposit()));
        assertEquals(0, minimum.compareTo(row.minWithdraw()));
        assertEquals(0, withdrawal.compareTo(row.collectThreshold()));
        assertEquals("RECEIPT", row.gasStrategy());
        assertEquals(3, row.confirmationRequired());
        assertFalse(row.collectEnabled());
    }

    @Test void networkScopedQueriesPreserveDisabledConfigurationWithoutExposingItToRuntime() {
        var tokens = new TokenConfigRepository(jdbc);
        long test = tokens.insert("ETH", "sepolia", "TWIN_TOKEN", "ERC20", "0x" + "94".repeat(20),
                null, null, 6, true, BigDecimal.ZERO, BigDecimal.ONE, true, null, null, null);
        long main = tokens.insert("ETH", "mainnet", "TWIN_TOKEN", "ERC20", "0x" + "93".repeat(20),
                null, null, 18, false, BigDecimal.ONE, BigDecimal.TEN, false, null, null, null);
        assertEquals(test, tokens.findEnabled("ETH", "sepolia", "TWIN_TOKEN").orElseThrow().id());
        assertTrue(tokens.findEnabled("ETH", "mainnet", "TWIN_TOKEN").isEmpty());
        assertTrue(tokens.findDefinition("ETH", "mainnet", "TWIN_TOKEN").isEmpty());
        assertTrue(tokens.findDefinitionByContract("ETH", "sepolia", "0x" + "93".repeat(20)).isEmpty());
        assertEquals(List.of(main), tokens.listByChainAndNetwork("eth", "MAINNET").stream()
                .filter(row -> row.symbol().equals("TWIN_TOKEN")).map(row -> row.id()).toList());
        assertTrue(tokens.listCollectEnabled("ETH", "sepolia").stream().anyMatch(row -> row.id() == test));
        tokens.updateEnabled(test, false, false);
        assertTrue(tokens.findDefinition("ETH", "sepolia", "TWIN_TOKEN").isEmpty());
        assertTrue(tokens.listCollectEnabled("ETH", "sepolia").stream().noneMatch(row -> row.id() == test));
        assertFalse(tokens.findById(test).orElseThrow().enabled());
    }

    @Test void unverifiedChainsAndMainnetStablecoinsRemainDisabledInTheInitializedDatabase() {
        var tokens = new TokenConfigRepository(jdbc).listAll();
        var mainnetStablecoins = tokens.stream().filter(row -> row.network().equals("mainnet"))
                .filter(row -> List.of("USDC", "USDT", "USDT0", "USDC_E").contains(row.symbol())).toList();
        assertFalse(mainnetStablecoins.isEmpty());
        for (var token : mainnetStablecoins) {
            assertFalse(token.enabled(), token.chain() + "/" + token.symbol());
            assertFalse(token.collectEnabled(), token.chain() + "/" + token.symbol());
        }
        for (String chain : List.of("ADA", "DOT", "NEAR", "XMR")) {
            assertEquals(Boolean.TRUE, jdbc.queryForObject(
                    "select native_asset from chain_asset where chain = ? and symbol = ?", Boolean.class, chain, chain));
            assertEquals(Boolean.FALSE, jdbc.queryForObject(
                    "select active from chain_asset where chain = ? and symbol = ?", Boolean.class, chain, chain));
            var chainTokens = tokens.stream().filter(row -> row.chain().equals(chain)).toList();
            var tokenAssetStates = jdbc.queryForList(
                    "select active from chain_asset where chain = ? and native_asset = false", Boolean.class, chain);
            if (!chain.equals("XMR")) {
                assertFalse(chainTokens.isEmpty(), chain);
                assertFalse(tokenAssetStates.isEmpty(), chain);
            }
            assertTrue(tokenAssetStates.stream().noneMatch(Boolean.TRUE::equals), chain);
            for (var token : chainTokens) {
                assertFalse(token.enabled());
                assertFalse(token.collectEnabled());
            }
        }
        for (String profile : List.of("ADA|preprod", "ADA|mainnet", "DOT|westend", "DOT|mainnet",
                "NEAR|testnet", "NEAR|mainnet", "XMR|regtest", "XMR|stagenet", "XMR|mainnet")) {
            String[] key = profile.split("\\|");
            var row = jdbc.queryForMap("""
                    select enabled, scan_enabled, withdraw_enabled, collection_enabled, transfer_enabled
                      from chain_profile where chain = ? and network = ?
                    """, key[0], key[1]);
            row.forEach((flag, enabled) -> assertEquals(Boolean.FALSE, enabled, profile + "/" + flag));
        }
    }

    @Test void placeholderContractsAndRpcCredentialsCannotBecomeActiveThroughInitialization() {
        assertEquals(0, jdbc.queryForObject("""
                select count(*) from token_config where enabled = true
                 and (btrim(contract_address) = '' or lower(contract_address) ~ '(change_me|your_|replace_me|todo_)')
                """, Integer.class));
        assertEquals(0, jdbc.queryForObject("""
                select count(*) from chain_asset where active = true and native_asset = false
                 and (coalesce(btrim(contract_address), '') = ''
                      or lower(contract_address) ~ '(change_me|your_|replace_me|todo_)')
                """, Integer.class));
        assertEquals(0, jdbc.queryForObject("""
                select count(*) from chain_rpc_node where enabled = true
                 and (coalesce(btrim(rpc_url), '') = ''
                      or lower(concat_ws(' ', rpc_url, api_key, username, password)) ~ '(change_me|your_|replace_me|todo_)')
                """, Integer.class));
    }
}
