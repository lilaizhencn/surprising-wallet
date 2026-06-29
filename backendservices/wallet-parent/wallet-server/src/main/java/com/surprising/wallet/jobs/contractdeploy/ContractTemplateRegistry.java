package com.surprising.wallet.jobs.contractdeploy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class ContractTemplateRegistry {
    private static final String RESOURCE_ROOT = "contracts/evm/";
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<TemplateType, CompiledTemplate> templates;

    public ContractTemplateRegistry() {
        this.templates = Map.of(
                TemplateType.ERC20, load(TemplateType.ERC20, "TokDouERC20"),
                TemplateType.ERC721, load(TemplateType.ERC721, "TokDouERC721"));
    }

    public List<Map<String, Object>> templateSummaries() {
        return List.of(summary(TemplateType.ERC20), summary(TemplateType.ERC721));
    }

    public CompiledTemplate require(TemplateType type) {
        CompiledTemplate template = templates.get(type);
        if (template == null) {
            throw new IllegalArgumentException("unsupported contract template: " + type);
        }
        return template;
    }

    private Map<String, Object> summary(TemplateType type) {
        CompiledTemplate template = require(type);
        if (type == TemplateType.ERC20) {
            return Map.of(
                    "type", type.name(),
                    "name", "ERC20 Token",
                    "contractName", template.contractName(),
                    "standard", "OpenZeppelin ERC20",
                    "compilerVersion", template.compilerVersion(),
                    "bytecodeHash", template.bytecodeHash(),
                    "features", List.of("Ownable", "Capped", "Pausable", "Burnable", "Permit", "Optional owner mint"));
        }
        return Map.of(
                "type", type.name(),
                "name", "ERC721 NFT Collection",
                "contractName", template.contractName(),
                "standard", "OpenZeppelin ERC721",
                "compilerVersion", template.compilerVersion(),
                "bytecodeHash", template.bytecodeHash(),
                "features", List.of("Ownable", "Enumerable", "URI storage", "Pausable", "Burnable", "Owner mint"));
    }

    private CompiledTemplate load(TemplateType type, String contractName) {
        try {
            String sourceName = contractName + ".sol";
            String source = readResource(RESOURCE_ROOT + sourceName);
            JsonNode artifact = objectMapper.readTree(readResource(
                    RESOURCE_ROOT + "artifacts/" + contractName + ".json"));
            String bytecode = text(artifact, "bytecode");
            String compilerVersion = text(artifact, "compilerVersion");
            JsonNode abi = artifact.get("abi");
            return new CompiledTemplate(
                    type,
                    contractName,
                    sourceName,
                    compilerVersion,
                    source,
                    bytecode,
                    abi == null ? "[]" : objectMapper.writeValueAsString(abi),
                    sha256Hex(bytecode));
        } catch (Exception e) {
            throw new IllegalStateException("unable to load contract template " + contractName, e);
        }
    }

    private static String readResource(String path) throws Exception {
        ClassPathResource resource = new ClassPathResource(path);
        return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? "" : value.asText();
    }

    private static String sha256Hex(String value) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hash).toLowerCase(Locale.ROOT);
    }

    public enum TemplateType {
        ERC20,
        ERC721;

        public static TemplateType parse(String value) {
            try {
                return TemplateType.valueOf((value == null ? "" : value.trim()).toUpperCase(Locale.ROOT));
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("contract type must be ERC20 or ERC721");
            }
        }
    }

    public record CompiledTemplate(
            TemplateType type,
            String contractName,
            String sourceName,
            String compilerVersion,
            String source,
            String bytecode,
            String abiJson,
            String bytecodeHash) {
    }
}
