package com.surprising.wallet.jobs.contractdeploy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

@Slf4j
@Component
public class SuiMoveCompiler {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String suiCliPath;
    private final Duration timeout;

    public SuiMoveCompiler(@Value("${sw.wallet.contract.sui.cli:sui}") String suiCliPath,
                           @Value("${sw.wallet.contract.sui.timeout-seconds:180}") long timeoutSeconds) {
        this.suiCliPath = suiCliPath == null || suiCliPath.isBlank() ? "sui" : suiCliPath.trim();
        this.timeout = Duration.ofSeconds(Math.max(30L, timeoutSeconds));
    }

    public CompiledMovePackage compile(RenderedMovePackage movePackage) {
        Path workDir = null;
        try {
            workDir = Files.createTempDirectory("sw-sui-contract-");
            Path sourcesDir = workDir.resolve("sources");
            Files.createDirectories(sourcesDir);
            Files.writeString(workDir.resolve("Move.toml"), movePackage.moveToml(), StandardCharsets.UTF_8);
            Files.writeString(sourcesDir.resolve(movePackage.sourceName()), movePackage.source(), StandardCharsets.UTF_8);

            Process process = new ProcessBuilder(
                    suiCliPath,
                    "move",
                    "build",
                    "--dump-bytecode-as-base64",
                    "--path",
                    workDir.toString())
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS);
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!finished) {
                process.destroyForcibly();
                throw new IllegalStateException("Sui Move compiler timed out after " + timeout.toSeconds() + " seconds");
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException("Sui Move compile failed: " + trimCompilerOutput(output));
            }
            JsonNode json = findCompilerJson(output);
            List<String> modules = textArray(json.path("modules"));
            List<String> dependencies = textArray(json.path("dependencies"));
            if (modules.isEmpty() || dependencies.isEmpty()) {
                throw new IllegalStateException("Sui Move compiler returned empty modules or dependencies");
            }
            String bytecodeHash = sha256Hex(objectMapper.writeValueAsBytes(json));
            return new CompiledMovePackage(modules, dependencies, bytecodeHash, output);
        } catch (IOException e) {
            throw new IllegalStateException("unable to execute Sui CLI `" + suiCliPath + "`", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Sui Move compile interrupted", e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Sui Move compile failed", e);
        } finally {
            deleteTempDirectory(workDir);
        }
    }

    private JsonNode findCompilerJson(String output) throws IOException {
        for (String line : output.split("\\R")) {
            String value = line.trim();
            if (value.startsWith("{") && value.contains("\"modules\"") && value.contains("\"dependencies\"")) {
                return objectMapper.readTree(value);
            }
        }
        throw new IllegalStateException("Sui Move compiler did not print bytecode JSON");
    }

    private static List<String> textArray(JsonNode array) {
        List<String> values = new ArrayList<>();
        if (array != null && array.isArray()) {
            for (JsonNode value : array) {
                values.add(value.asText());
            }
        }
        return values;
    }

    private static String sha256Hex(byte[] value) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(value);
        return HexFormat.of().formatHex(hash).toLowerCase(Locale.ROOT);
    }

    private static String trimCompilerOutput(String output) {
        if (output == null || output.isBlank()) {
            return "<empty>";
        }
        String compact = output.trim();
        return compact.length() <= 2000 ? compact : compact.substring(0, 2000) + "...";
    }

    private void deleteTempDirectory(Path workDir) {
        if (workDir == null) {
            return;
        }
        Path normalized = workDir.toAbsolutePath().normalize();
        if (!normalized.getFileName().toString().startsWith("sw-sui-contract-")) {
            log.warn("refusing to delete unexpected Sui compiler directory: {}", normalized);
            return;
        }
        try (Stream<Path> paths = Files.walk(normalized)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    log.debug("unable to delete Sui compiler temp path {}", path, e);
                }
            });
        } catch (IOException e) {
            log.debug("unable to clean Sui compiler temp directory {}", normalized, e);
        }
    }

    public record RenderedMovePackage(String packageName,
                                      String moduleName,
                                      String sourceName,
                                      String source,
                                      String moveToml) {
    }

    public record CompiledMovePackage(List<String> modules,
                                      List<String> dependencies,
                                      String bytecodeHash,
                                      String compilerOutput) {
    }
}
