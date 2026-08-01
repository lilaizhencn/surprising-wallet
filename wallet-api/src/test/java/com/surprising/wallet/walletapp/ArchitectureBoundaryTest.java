package com.surprising.wallet.walletapp;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 MVC 入口边界，防止 Controller 或 Job 重新承担数据库和业务工作流职责。
 */
class ArchitectureBoundaryTest {
    /** 验证 Controller、Job、Service 和 Repository 的目录、依赖与注解边界。 */
    @Test
    void walletApiLayerBoundariesRemainExplicit() throws IOException {
        Path sourceRoot = sourceRoot();
        Path serviceRoot = sourceRoot.resolve("service");
        Path repositoryRoot = sourceRoot.resolve("repository");
        Path jobRoot = sourceRoot.resolve("job");
        Path controllerRoot = sourceRoot.resolve("controller");

        assertTrue(Files.isDirectory(serviceRoot), "service package must exist");
        assertTrue(Files.isDirectory(repositoryRoot), "repository package must exist");
        assertFalse(Files.exists(sourceRoot.resolve("account/service")),
                "business services must not return to account subpackages");
        assertFalse(Files.exists(sourceRoot.resolve("custody/service")),
                "business services must not return to custody subpackages");
        assertFalse(Files.exists(sourceRoot.resolve("deposit/service")),
                "business services must not return to deposit subpackages");

        for (Path file : javaFiles(jobRoot)) {
            String source = Files.readString(file);
            assertFalse(source.contains("import com.surprising.wallet.repository."),
                    "Job must not access Repository directly: " + file);
            assertFalse(source.contains("JdbcTemplate"),
                    "Job must not contain JDBC access: " + file);
        }
        for (Path file : javaFiles(controllerRoot)) {
            String source = Files.readString(file);
            assertFalse(source.contains("import com.surprising.wallet.repository."),
                    "Controller must not access Repository directly: " + file);
            assertFalse(source.contains("JdbcTemplate"),
                    "Controller must not contain JDBC access: " + file);
        }
        for (Path file : javaFiles(serviceRoot)) {
            String source = Files.readString(file);
            assertFalse(source.contains("JdbcTemplate"),
                    "Service must not access JDBC directly: " + file);
            assertFalse(source.lines().anyMatch(line -> line.trim().toLowerCase()
                            .matches(".*\\b(select\\s+.+\\s+from|insert\\s+into|update\\s+[a-z_]+\\s+set|delete\\s+from)\\b.*")),
                    "Service must not contain SQL: " + file);
        }
        for (Path file : javaFiles(sourceRoot)) {
            String source = Files.readString(file);
            if (file.startsWith(jobRoot) || file.startsWith(controllerRoot)) {
                continue;
            }
            assertFalse(source.lines().anyMatch(line -> line.trim().startsWith("@Scheduled")),
                    "scheduled entry points must stay under job/**: " + file);
        }
    }

    /** 读取目录下的 Java 源文件。 */
    private static List<Path> javaFiles(Path root) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(path -> path.toString().endsWith(".java")).toList();
        }
    }

    /** 从测试运行目录向上定位 wallet-api 源码根目录。 */
    private static Path sourceRoot() throws IOException {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve("wallet-api/src/main/java/com/surprising/wallet");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new IOException("wallet-api source root not found from user.dir");
    }
}
