package com.surprising.wallet.jobs.custody;

import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.regex.Pattern;

final class CustodyIntegrationDatabase {
    private static final Pattern LOCAL_TEST_DATABASE_URL = Pattern.compile(
            "^jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost):5432/"
                    + "surprising_wallet_test_[a-z0-9_]+(?:\\?.*)?$");

    private CustodyIntegrationDatabase() {
    }

    static DriverManagerDataSource dataSource() {
        String url = requiredEnvironment("SW_TEST_CUSTODY_DB_URL");
        if (!LOCAL_TEST_DATABASE_URL.matcher(url).matches()) {
            throw new IllegalStateException("SW_TEST_CUSTODY_DB_URL must target "
                    + "jdbc:postgresql://127.0.0.1:5432/surprising_wallet_test_*");
        }
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(url);
        dataSource.setUsername(System.getenv().getOrDefault(
                "SW_TEST_CUSTODY_DB_USERNAME", System.getProperty("user.name")));
        dataSource.setPassword(System.getenv().getOrDefault("SW_TEST_CUSTODY_DB_PASSWORD", ""));
        return dataSource;
    }

    static void reset(DriverManagerDataSource dataSource) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            requirePostgreSql18(connection);
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(
                    projectRoot().resolve("docs/db/surprising-wallet-init-pgsql.sql")));
        }
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required; use scripts/regtest/run-custody-db-tests.sh");
        }
        return value.trim();
    }

    private static void requirePostgreSql18(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("show server_version_num")) {
            if (!result.next() || Integer.parseInt(result.getString(1)) / 10_000 != 18) {
                throw new IllegalStateException("custody integration tests require local PostgreSQL 18");
            }
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
