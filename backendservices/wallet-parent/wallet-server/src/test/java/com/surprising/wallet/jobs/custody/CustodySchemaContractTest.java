package com.surprising.wallet.jobs.custody;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CustodySchemaContractTest {
    private static final Map<String, String> PROTECTED_FILES = Map.of(
            "keypairs", "755b669714430fc2aa814bca1402907bf3bd6636a6381c746db6bae19aec0fc7");

    @Test
    void additiveSchemaContainsTenantIsolationAndReliableEvents() throws Exception {
        Path root = projectRoot();
        String sql = Files.readString(root.resolve(
                "backendservices/wallet-parent/wallet-server/src/main/resources/db/custody-schema.sql"));

        for (String table : new String[]{
                "custody_tenant", "custody_tenant_user", "custody_session", "custody_api_key",
                "custody_api_nonce", "custody_ip_rule", "custody_webhook_endpoint",
                "custody_derivation_subject", "custody_address", "custody_gas_account",
                "custody_deposit", "custody_withdrawal",
                "custody_gas_usage", "custody_ledger_entry", "custody_event", "custody_webhook_delivery",
                "custody_webhook_delivery_attempt",
                "custody_idempotency_key", "custody_audit_log"}) {
            assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS " + table), table + " is required");
        }
        assertTrue(sql.contains("UNIQUE (tenant_id, event_type, aggregate_type, aggregate_id)"));
        assertTrue(sql.contains("UNIQUE (tenant_id, entry_type, reference_type, reference_id)"));
        assertTrue(sql.contains("PRIMARY KEY (tenant_id, subject)"));
        assertTrue(sql.contains("custody_derivation_subject_path_key UNIQUE (derivation_subject)"));
        assertTrue(sql.contains("CONSTRAINT custody_address_chain_address_key UNIQUE (chain_address_id)"));
        assertTrue(sql.contains("UNIQUE (tenant_id, chain, derivation_subject, derivation_child)"));
        assertTrue(sql.contains("total_attempt_count integer NOT NULL DEFAULT 0"));
        assertTrue(sql.contains("manual_retry_count integer NOT NULL DEFAULT 0"));
        assertTrue(sql.contains("UNIQUE (delivery_id, attempt_number)"));
        assertTrue(sql.contains("UNIQUE (custody_withdrawal_id)"));
        assertTrue(sql.contains("status IN ('RESERVED', 'SETTLED', 'RELEASED', 'OVERDUE')"));
        assertTrue(sql.contains("chain_profile_one_enabled_network_idx"));
        assertTrue(sql.contains("ON chain_profile (upper(chain)) WHERE enabled = true"));
        assertTrue(sql.contains("token_config_chain_network_symbol_key\n    ON token_config (chain, network, symbol)"));
        assertTrue(sql.contains("token_config_one_enabled_network_per_asset_idx"));
        assertTrue(sql.contains("WHERE enabled = true"));
        assertTrue(sql.contains("https://developers.circle.com/stablecoins/usdc-contract-addresses"));
        assertTrue(sql.contains("https://tether.to/en/supported-protocols/"));
        assertTrue(sql.contains("https://docs.usdt0.to/technical-documentation/deployments"));
        assertTrue(sql.contains("0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48"));
        assertTrue(sql.contains("0xdAC17F958D2ee523a2206206994597C13D831ec7"));
        assertTrue(sql.contains("0xFd086bC7CD5C481DCC9C85ebE478A1C0b69FCbb9"));
        assertTrue(sql.contains("0xB8CE59FC3717ada4C02eaDF9682A9e934F625ebb"));
        assertTrue(sql.contains("0x779Ded0c9e1022225f8E0630b35a9b54bE713736"));
        assertTrue(sql.contains("0x01bFF41798a0BcF287b996046Ca68b395DbC1071"));
        assertTrue(sql.contains("0xc2132D05D31c914a87C6611C10748AEb04B58e8F"));
        assertTrue(sql.contains("0x9151434b16b9763660705744891fA906F660EcC5"));
        assertTrue(sql.contains("TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t"));
        assertTrue(sql.contains("EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"));
        assertTrue(sql.contains("stablecoin.decimals, false, 1, 1, 1, 1, false, 1"),
                "mainnet stablecoins must remain disabled for transfers and collection");
        assertTrue(sql.contains("last_checked_at timestamptz"));
        assertTrue(sql.contains("last_latency_ms bigint"));
        assertTrue(sql.matches(
                "(?s).*CREATE TABLE IF NOT EXISTS custody_idempotency_key \\(.*"
                        + "expires_at timestamptz,\\R\\s+created_at timestamptz.*"));
        assertFalse(CustodyWebhookService.ALLOWED_EVENTS.contains("ADDRESS.CREATED"));

        String databaseSeed = Files.readString(root.resolve("docs/db/surprising-wallet-init-pgsql.sql"));
        assertTrue(databaseSeed.contains(
                "token_config_chain_network_symbol_key\" UNIQUE (\"chain\", \"network\", \"symbol\")"));
        assertTrue(databaseSeed.contains(
                "token_config_chain_network_contract_address_key\" UNIQUE (\"chain\", \"network\", \"contract_address\")"));
    }

    @Test
    void localKeypairFileRemainsByteIdentical() throws Exception {
        Path root = projectRoot();
        for (Map.Entry<String, String> entry : PROTECTED_FILES.entrySet()) {
            assertEquals(entry.getValue(), sha256(root.resolve(entry.getKey())),
                    entry.getKey() + " must not be modified by wallet configuration work");
        }
    }

    private static String sha256(Path path) throws IOException, NoSuchAlgorithmException {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }

    private static Path projectRoot() throws IOException {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null) {
            if (Files.exists(current.resolve("docs/db/surprising-wallet-init-pgsql.sql"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IOException("surprising-wallet project root not found");
    }
}
