package com.surprising.wallet.walletapp;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseSeedSafetyTest {
    @Test
    void unverifiedNewChainsStayDisabledInFreshDatabaseSeed() throws Exception {
        String sql = databaseSeed();

        Map<String, String> nativeSymbols = Map.of(
                "ADA", "ADA",
                "DOT", "DOT",
                "NEAR", "NEAR",
                "XMR", "XMR");
        nativeSymbols.forEach((chain, symbol) -> assertRegex(sql,
                "\\('" + chain + "',\\s*'" + symbol + "',\\s*'NATIVE',[^\\n]+true,\\s*false,",
                chain + "/" + symbol + " native chain_asset must stay inactive by default"));

        assertTrue(sql.contains("SELECT chain, symbol, asset_kind, contract_address, decimals, false, false"),
                "new-chain token chain_asset rows must stay inactive by default");
        assertTrue(sql.contains("SELECT chain, symbol, standard, contract_address, decimals, false"),
                "new-chain token_config rows must stay disabled by default");
        assertTrue(sql.contains("min_deposit, min_withdraw, false, now(), now()"),
                "new-chain token collection must stay disabled by default");
        assertTrue(sql.contains("seeded RPC rows with placeholder URLs or credentials must stay disabled"),
                "database seed must keep placeholder RPC nodes disabled");
        assertTrue(sql.contains("upper(coalesce(\"rpc_url\", '')) LIKE '%CHANGE_ME%'"),
                "database seed must disable RPC nodes with placeholder URLs");
        assertTrue(sql.contains("upper(coalesce(\"api_key\", '')) LIKE '%CHANGE_ME%'"),
                "database seed must disable RPC nodes with placeholder credentials");
        assertTrue(sql.contains("seeded token rows with placeholder or empty contract addresses"),
                "database seed must keep placeholder token_config rows disabled");
        assertTrue(sql.contains("UPDATE \"public\".\"token_config\""),
                "database seed must include a token_config placeholder safety guard");
        assertTrue(sql.contains("normalized token assets with placeholder or empty contracts"),
                "database seed must keep placeholder token chain_asset rows inactive");
        assertTrue(sql.contains("UPDATE \"public\".\"chain_asset\""),
                "database seed must include a chain_asset placeholder safety guard");

        for (String profile : new String[]{
                "ADA|preprod", "ADA|mainnet", "DOT|westend", "DOT|mainnet",
                "NEAR|testnet", "NEAR|mainnet", "XMR|regtest", "XMR|stagenet", "XMR|mainnet"}) {
            String[] parts = profile.split("\\|");
            assertRegex(sql,
                    "\\('" + parts[0] + "',\\s*'" + parts[1] + "',[\\s\\S]*?false,\\s*now\\(\\),\\s*now\\(\\),[\\s\\S]*?false,\\s*false,\\s*false,\\s*false",
                    profile + " chain_profile must keep runtime jobs disabled by default");
        }
    }

    private static void assertRegex(String value, String regex, String message) {
        assertTrue(Pattern.compile(regex).matcher(value).find(), message);
    }

    private static String databaseSeed() throws IOException {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve("resources/docs/db/surprising-wallet-init-pgsql.sql");
            if (Files.exists(candidate)) {
                return Files.readString(candidate);
            }
            candidate = current.resolve("docs/db/surprising-wallet-init-pgsql.sql");
            if (Files.exists(candidate)) {
                return Files.readString(candidate);
            }
            current = current.getParent();
        }
        throw new IOException("docs/db/surprising-wallet-init-pgsql.sql not found from user.dir");
    }
}
