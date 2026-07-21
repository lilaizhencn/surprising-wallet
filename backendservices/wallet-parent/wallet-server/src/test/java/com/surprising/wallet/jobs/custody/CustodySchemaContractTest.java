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
        assertTrue(sql.contains("last_checked_at timestamptz"));
        assertTrue(sql.contains("last_latency_ms bigint"));
        assertTrue(sql.matches(
                "(?s).*CREATE TABLE IF NOT EXISTS custody_idempotency_key \\(.*"
                        + "expires_at timestamptz,\\R\\s+created_at timestamptz.*"));
        assertFalse(CustodyWebhookService.ALLOWED_EVENTS.contains("ADDRESS.CREATED"));
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
