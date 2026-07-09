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
public class AptosMoveCompiler {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String aptosCliPath;
    private final Duration timeout;

    public AptosMoveCompiler(@Value("${sw.wallet.contract.aptos.cli:aptos}") String aptosCliPath,
                             @Value("${sw.wallet.contract.aptos.timeout-seconds:180}") long timeoutSeconds) {
        this.aptosCliPath = aptosCliPath == null || aptosCliPath.isBlank() ? "aptos" : aptosCliPath.trim();
        this.timeout = Duration.ofSeconds(Math.max(30L, timeoutSeconds));
    }

    public CompiledAptosPackage compile(RenderedAptosPackage movePackage) {
        Path workDir = null;
        try {
            workDir = Files.createTempDirectory("sw-aptos-contract-");
            Path sourcesDir = workDir.resolve("sources");
            Files.createDirectories(sourcesDir);
            Files.writeString(workDir.resolve("Move.toml"), movePackage.moveToml(), StandardCharsets.UTF_8);
            Files.writeString(sourcesDir.resolve(movePackage.sourceName()), movePackage.source(), StandardCharsets.UTF_8);
            Path payloadPath = workDir.resolve("publish-payload.json");

            String output = run(workDir,
                    aptosCliPath,
                    "move",
                    "build-publish-payload",
                    "--package-dir",
                    workDir.toString(),
                    "--named-addresses",
                    movePackage.addressName() + "=" + movePackage.publisherAddress(),
                    "--json-output-file",
                    payloadPath.toString(),
                    "--assume-yes",
                    "--included-artifacts",
                    "none");
            CompiledAptosPackage compiled = readPayload(payloadPath, output);
            if (compiled.modules().isEmpty() || compiled.metadata().length == 0) {
                throw new IllegalStateException("Aptos compiler returned empty metadata or modules");
            }
            return compiled;
        } catch (IOException e) {
            throw new IllegalStateException("unable to execute Aptos CLI `" + aptosCliPath + "`", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Aptos Move compile interrupted", e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Aptos Move compile failed", e);
        } finally {
            deleteTempDirectory(workDir);
        }
    }

    private String run(Path workDir, String... command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command)
                .directory(workDir.toFile())
                .redirectErrorStream(true)
                .start();
        boolean finished = process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS);
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException("Aptos Move compiler timed out after " + timeout.toSeconds() + " seconds");
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException("Aptos Move compile failed: " + trimCompilerOutput(output));
        }
        return output;
    }

    private CompiledAptosPackage readPayload(Path payloadPath, String output) throws Exception {
        if (!Files.exists(payloadPath)) {
            throw new IllegalStateException("Aptos CLI did not write publish payload JSON");
        }
        JsonNode payload = findPublishPayload(objectMapper.readTree(payloadPath.toFile()));
        JsonNode args = payload.path("args");
        if (!args.isArray() || args.size() < 2) {
            args = payload.path("arguments");
        }
        if (!args.isArray() || args.size() < 2) {
            throw new IllegalStateException("Aptos publish payload does not contain metadata and modules");
        }
        byte[] metadata = hexToBytes(argumentHex(args.get(0)));
        List<byte[]> modules = argumentHexArray(args.get(1)).stream()
                .map(AptosMoveCompiler::hexToBytes)
                .toList();
        String bytecodeHash = sha256Hex(metadata, modules);
        return new CompiledAptosPackage(metadata, modules, bytecodeHash, output);
    }

    private JsonNode findPublishPayload(JsonNode node) {
        if (node == null || node.isNull()) {
            throw new IllegalStateException("empty Aptos publish payload");
        }
        String function = node.path("function").asText(node.path("function_id").asText(""));
        if (function.contains("0x1::code::publish_package_txn")) {
            return node;
        }
        if (node.has("payload")) {
            return findPublishPayload(node.get("payload"));
        }
        if (node.has("Result")) {
            return findPublishPayload(node.get("Result"));
        }
        if (node.isObject() || node.isArray()) {
            for (JsonNode child : node) {
                try {
                    return findPublishPayload(child);
                } catch (RuntimeException ignored) {
                    // Continue searching nested wrapper fields.
                }
            }
        }
        throw new IllegalStateException("Aptos publish payload JSON missing publish_package_txn");
    }

    private static String argumentHex(JsonNode node) {
        if (node == null || node.isNull()) {
            throw new IllegalStateException("Aptos publish argument is empty");
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.has("value")) {
            return argumentHex(node.get("value"));
        }
        if (node.has("bytes")) {
            return argumentHex(node.get("bytes"));
        }
        throw new IllegalStateException("Aptos publish argument is not hex: " + node);
    }

    private static List<String> argumentHexArray(JsonNode node) {
        if (node == null || node.isNull()) {
            throw new IllegalStateException("Aptos publish module argument is empty");
        }
        if (node.has("value")) {
            return argumentHexArray(node.get("value"));
        }
        List<String> values = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode child : node) {
                values.add(argumentHex(child));
            }
            return values;
        }
        throw new IllegalStateException("Aptos publish modules argument is not an array: " + node);
    }

    private static byte[] hexToBytes(String value) {
        String hex = value == null ? "" : value.trim();
        if (hex.startsWith("0x") || hex.startsWith("0X")) {
            hex = hex.substring(2);
        }
        return HexFormat.of().parseHex(hex);
    }

    private static String sha256Hex(byte[] metadata, List<byte[]> modules) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(metadata);
        for (byte[] module : modules) {
            digest.update(module);
        }
        return HexFormat.of().formatHex(digest.digest()).toLowerCase(Locale.ROOT);
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
        if (!normalized.getFileName().toString().startsWith("sw-aptos-contract-")) {
            log.warn("refusing to delete unexpected Aptos compiler directory: {}", normalized);
            return;
        }
        try (Stream<Path> paths = Files.walk(normalized)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    log.debug("unable to delete Aptos compiler temp path {}", path, e);
                }
            });
        } catch (IOException e) {
            log.debug("unable to clean Aptos compiler temp directory {}", normalized, e);
        }
    }

    public record RenderedAptosPackage(String packageName,
                                       String addressName,
                                       String moduleName,
                                       String sourceName,
                                       String publisherAddress,
                                       String source,
                                       String moveToml) {
    }

    public record CompiledAptosPackage(byte[] metadata,
                                       List<byte[]> modules,
                                       String bytecodeHash,
                                       String compilerOutput) {
    }
}
