package com.surprising.wallet.jobs.custody;

import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;

final class CustodyIntegrationDatabase {
    private CustodyIntegrationDatabase() {
    }

    static DriverManagerDataSource dataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(System.getenv().getOrDefault(
                "SW_TEST_CUSTODY_DB_URL",
                "jdbc:postgresql://127.0.0.1:5432/wallet_custody_test"));
        dataSource.setUsername(System.getenv().getOrDefault(
                "SW_TEST_CUSTODY_DB_USERNAME", System.getProperty("user.name")));
        dataSource.setPassword(System.getenv().getOrDefault("SW_TEST_CUSTODY_DB_PASSWORD", ""));
        return dataSource;
    }

    static void reset(DriverManagerDataSource dataSource) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(
                    projectRoot().resolve("docs/db/surprising-wallet-init-pgsql.sql")));
        }
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
