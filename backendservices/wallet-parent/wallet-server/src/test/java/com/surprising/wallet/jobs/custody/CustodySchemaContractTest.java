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
            "keypairs", "a225b7a98148cfc8562a9a6c1d3f7ab4139cc3e856b09a544c5fa31af97c2c5f",
            "docs/db/surprising-wallet-init-pgsql.sql",
            "e04d1bf71f92413572d32ca10ca08ac596dad163819afd782db8df7d569c7b14",
            "backendservices/wallet-parent/wallet-server/src/main/resources/application-test.yaml",
            "de518605e03f0474090ffdd1285bcbad2d74f0df487b2cfe6a5a83304ea74b89",
            "backendservices/wallet-parent/wallet-server/src/main/resources/application-test2.yaml",
            "09e0ab6d55662e60881ad9dc8e85d400766a38b68411d2cab326a1e831dcf746");

    @Test
    void additiveSchemaContainsTenantIsolationAndReliableEvents() throws Exception {
        Path root = projectRoot();
        String sql = Files.readString(root.resolve(
                "backendservices/wallet-parent/wallet-server/src/main/resources/db/custody-schema.sql"));

        for (String table : new String[]{
                "custody_tenant", "custody_tenant_user", "custody_session", "custody_api_key",
                "custody_api_nonce", "custody_ip_rule", "custody_webhook_endpoint",
                "custody_address", "custody_deposit", "custody_withdrawal",
                "custody_ledger_entry", "custody_event", "custody_webhook_delivery",
                "custody_idempotency_key", "custody_audit_log"}) {
            assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS " + table), table + " is required");
        }
        assertTrue(sql.contains("UNIQUE (tenant_id, event_type, aggregate_type, aggregate_id)"));
        assertTrue(sql.contains("UNIQUE (tenant_id, entry_type, reference_type, reference_id)"));
        assertTrue(sql.contains("CREATE UNIQUE INDEX IF NOT EXISTS custody_address_allocation_key"));
        assertTrue(sql.contains("ON custody_address (tenant_id, chain, external_reference)"));
        assertTrue(sql.contains("CONSTRAINT custody_address_chain_address_key UNIQUE (chain_address_id)"));
        assertTrue(sql.contains("CONSTRAINT custody_address_derivation_subject_key UNIQUE (derivation_subject)"));
        assertTrue(sql.matches(
                "(?s).*CREATE TABLE IF NOT EXISTS custody_idempotency_key \\(.*"
                        + "expires_at timestamptz,\\R\\s+created_at timestamptz.*"));
        assertFalse(CustodyWebhookService.ALLOWED_EVENTS.contains("ADDRESS.CREATED"));
    }

    @Test
    void existingSeedsAndFundedTestConfigurationRemainByteIdentical() throws Exception {
        Path root = projectRoot();
        for (Map.Entry<String, String> entry : PROTECTED_FILES.entrySet()) {
            assertEquals(entry.getValue(), sha256(root.resolve(entry.getKey())),
                    entry.getKey() + " must not be modified by the custody refactor");
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
