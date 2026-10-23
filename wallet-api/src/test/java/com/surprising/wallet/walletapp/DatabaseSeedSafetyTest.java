package com.surprising.wallet.walletapp;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;

/** Root key material must remain outside the database configuration model. */
class DatabaseSeedSafetyTest {
    @Test
    void databaseBaselineNeverDefinesWalletRootSeeds() throws Exception {
        assertFalse(databaseSeed().contains("wallet_key_config"),
                "wallet root seeds must never be modeled or stored in the database baseline");
    }

    /**
     * 验证 {@code databaseSeed} 对应的测试场景，明确输入、预期结果和异常边界。
     */
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
