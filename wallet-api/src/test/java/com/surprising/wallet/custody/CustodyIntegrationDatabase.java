package com.surprising.wallet.custody;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.regex.Pattern;

/** 复用本机 PostgreSQL 18，在明确命名的隔离测试库中加载当前基线。 */
public final class CustodyIntegrationDatabase {
    /**
     * 保存 {@code LOCAL_TEST_DATABASE_URL}，用于承载当前测试夹具的配置或运行数据。
     */
    private static final Pattern LOCAL_TEST_DATABASE_URL = Pattern.compile(
            "^jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost):5432/"
                    + "surprising_wallet_test_[a-z0-9_]+(?:\\?.*)?$");

    /**
     * 验证 {@code CustodyIntegrationDatabase} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private CustodyIntegrationDatabase() {
    }

    /** 在创建连接前校验测试数据库地址。 */
    public static DriverManagerDataSource dataSource() {
        String url = requiredEnvironment("SW_TEST_CUSTODY_DB_URL");
        requireTestDatabaseUrl(url);
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(url);
        dataSource.setUsername(System.getenv().getOrDefault(
                "SW_TEST_CUSTODY_DB_USERNAME", System.getProperty("user.name")));
        dataSource.setPassword(System.getenv().getOrDefault("SW_TEST_CUSTODY_DB_PASSWORD", ""));
        return dataSource;
    }

    /** 只允许对隔离测试库加载基线，调用方必须显式提供连接配置。 */
    public static void reset(DriverManagerDataSource dataSource) throws Exception {
        requireTestDatabaseUrl(dataSource.getUrl());
        try (Connection connection = dataSource.getConnection()) {
            requirePostgreSql18(connection);
            var resource = new EncodedResource(new FileSystemResource(
                    projectRoot().resolve("resources/docs/db/surprising-wallet-init-pgsql.sql")));
            ScriptUtils.executeSqlScript(
                    connection,
                    resource,
                    false,
                    false,
                    ScriptUtils.DEFAULT_COMMENT_PREFIXES,
                    ScriptUtils.EOF_STATEMENT_SEPARATOR,
                    ScriptUtils.DEFAULT_BLOCK_COMMENT_START_DELIMITER,
                    ScriptUtils.DEFAULT_BLOCK_COMMENT_END_DELIMITER);
        }
    }

    private static void requireTestDatabaseUrl(String url) {
        if (url == null || !LOCAL_TEST_DATABASE_URL.matcher(url).matches()) {
            throw new IllegalStateException("test database must target jdbc:postgresql://127.0.0.1:5432/surprising_wallet_test_*");
        }
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name
                    + " is required; use resources/scripts/regtest/run-custody-db-tests.sh");
        }
        return value.trim();
    }

    /**
     * 验证 {@code requirePostgreSql18} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private static void requirePostgreSql18(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("show server_version_num")) {
            if (!result.next() || Integer.parseInt(result.getString(1)) / 10_000 != 18) {
                throw new IllegalStateException("custody integration tests require local PostgreSQL 18");
            }
        }
    }

    /**
     * 验证 {@code projectRoot} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private static Path projectRoot() throws IOException {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null) {
            if (Files.exists(current.resolve("resources/docs/db/surprising-wallet-init-pgsql.sql"))
                    || Files.exists(current.resolve("docs/db/surprising-wallet-init-pgsql.sql"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IOException("surprising-wallet project root not found");
    }
}
