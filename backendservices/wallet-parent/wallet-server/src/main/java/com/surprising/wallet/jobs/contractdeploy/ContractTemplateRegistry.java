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
    private static final String RESOURCE_ROOT = "contracts/";
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<ContractFamily, Map<TemplateType, CompiledTemplate>> templates;

    public ContractTemplateRegistry() {
        this.templates = Map.of(
                ContractFamily.EVM, Map.of(
                        TemplateType.ERC20, load(ContractFamily.EVM, TemplateType.ERC20, "TokDouERC20"),
                        TemplateType.ERC721, load(ContractFamily.EVM, TemplateType.ERC721, "TokDouERC721")),
                ContractFamily.TRON, Map.of(
                        TemplateType.ERC20, load(ContractFamily.TRON, TemplateType.ERC20, "TokDouTRC20"),
                        TemplateType.ERC721, load(ContractFamily.TRON, TemplateType.ERC721, "TokDouTRC721")),
                ContractFamily.NEAR, Map.of(
                        TemplateType.ERC20, load(ContractFamily.NEAR, TemplateType.ERC20, "TokDouNep141"),
                        TemplateType.ERC721, load(ContractFamily.NEAR, TemplateType.ERC721, "TokDouNep171")),
                ContractFamily.SOLANA, Map.of(
                        TemplateType.ERC20, load(ContractFamily.SOLANA, TemplateType.ERC20, "TokDouSplToken"),
                        TemplateType.ERC721, load(ContractFamily.SOLANA, TemplateType.ERC721, "TokDouSplNft")),
                ContractFamily.APTOS, Map.of(
                        TemplateType.ERC20, load(ContractFamily.APTOS, TemplateType.ERC20, "TokDouAptosCoin"),
                        TemplateType.ERC721, load(ContractFamily.APTOS, TemplateType.ERC721, "TokDouAptosNft")),
                ContractFamily.SUI, Map.of(
                        TemplateType.ERC20, load(ContractFamily.SUI, TemplateType.ERC20, "TokDouSuiCoin"),
                        TemplateType.ERC721, load(ContractFamily.SUI, TemplateType.ERC721, "TokDouSuiNft")),
                ContractFamily.POLKADOT, Map.of(
                        TemplateType.ERC20, load(ContractFamily.POLKADOT, TemplateType.ERC20, "TokDouAssetHubToken"),
                        TemplateType.ERC721, load(ContractFamily.POLKADOT, TemplateType.ERC721, "TokDouAssetHubAsset")),
                ContractFamily.TON, Map.of(
                        TemplateType.ERC20, load(ContractFamily.TON, TemplateType.ERC20, "TokDouJetton"),
                        TemplateType.ERC721, load(ContractFamily.TON, TemplateType.ERC721, "TokDouNftCollection")));
    }

    public List<Map<String, Object>> templateSummaries() {
        return List.of(
                summary(ContractFamily.EVM, TemplateType.ERC20),
                summary(ContractFamily.EVM, TemplateType.ERC721),
                summary(ContractFamily.TRON, TemplateType.ERC20),
                summary(ContractFamily.TRON, TemplateType.ERC721),
                summary(ContractFamily.NEAR, TemplateType.ERC20),
                summary(ContractFamily.NEAR, TemplateType.ERC721),
                summary(ContractFamily.SOLANA, TemplateType.ERC20),
                summary(ContractFamily.SOLANA, TemplateType.ERC721),
                summary(ContractFamily.APTOS, TemplateType.ERC20),
                summary(ContractFamily.APTOS, TemplateType.ERC721),
                summary(ContractFamily.SUI, TemplateType.ERC20),
                summary(ContractFamily.SUI, TemplateType.ERC721),
                summary(ContractFamily.POLKADOT, TemplateType.ERC20),
                summary(ContractFamily.POLKADOT, TemplateType.ERC721),
                summary(ContractFamily.TON, TemplateType.ERC20),
                summary(ContractFamily.TON, TemplateType.ERC721));
    }

    public CompiledTemplate require(ContractFamily family, TemplateType type) {
        Map<TemplateType, CompiledTemplate> familyTemplates = templates.get(family);
        CompiledTemplate template = familyTemplates == null ? null : familyTemplates.get(type);
        if (template == null) {
            throw new IllegalArgumentException("unsupported contract template: " + family + "/" + type);
        }
        return template;
    }

    private Map<String, Object> summary(ContractFamily family, TemplateType type) {
        CompiledTemplate template = require(family, type);
        if (family == ContractFamily.NEAR) {
            if (type == TemplateType.ERC20) {
                return Map.of(
                        "family", family.apiValue(),
                        "type", type.name(),
                        "name", "NEP-141 Fungible Token",
                        "contractName", template.contractName(),
                        "standard", "NEAR NEP-141",
                        "compilerVersion", template.compilerVersion(),
                        "evmVersion", template.evmVersion(),
                        "bytecodeHash", template.bytecodeHash(),
                        "features", List.of("near-sdk-js", "FungibleTokenCore", "Storage management", "Owner initial supply"));
            }
            return Map.of(
                    "family", family.apiValue(),
                    "type", type.name(),
                    "name", "NEP-171 NFT Collection",
                    "contractName", template.contractName(),
                    "standard", "NEAR NEP-171",
                    "compilerVersion", template.compilerVersion(),
                    "evmVersion", template.evmVersion(),
                    "bytecodeHash", template.bytecodeHash(),
                    "features", List.of("near-sdk-js", "NFT core", "Metadata", "Enumeration", "Owner mint"));
        }
        if (family == ContractFamily.SUI) {
            if (type == TemplateType.ERC20) {
                return Map.of(
                        "family", family.apiValue(),
                        "type", type.name(),
                        "name", "Sui Coin",
                        "contractName", template.contractName(),
                        "standard", "Sui Coin<T>",
                        "compilerVersion", template.compilerVersion(),
                        "evmVersion", template.evmVersion(),
                        "bytecodeHash", template.bytecodeHash(),
                        "features", List.of("Move template", "Coin Registry", "MintAuthority", "Max supply"));
            }
            return Map.of(
                    "family", family.apiValue(),
                    "type", type.name(),
                    "name", "Sui NFT Collection",
                    "contractName", template.contractName(),
                    "standard", "Sui object NFT",
                    "compilerVersion", template.compilerVersion(),
                    "evmVersion", template.evmVersion(),
                    "bytecodeHash", template.bytecodeHash(),
                    "features", List.of("Move template", "Collection object", "Owner mint", "Max supply"));
        }
        if (family == ContractFamily.SOLANA) {
            if (type == TemplateType.ERC20) {
                return Map.of(
                        "family", family.apiValue(),
                        "type", type.name(),
                        "name", "SPL Token Mint",
                        "contractName", template.contractName(),
                        "standard", "Solana SPL Token",
                        "compilerVersion", template.compilerVersion(),
                        "evmVersion", template.evmVersion(),
                        "bytecodeHash", template.bytecodeHash(),
                        "features", List.of("SPL Token Program", "Associated token account", "Initial supply", "Optional mint authority"));
            }
            return Map.of(
                    "family", family.apiValue(),
                    "type", type.name(),
                    "name", "SPL NFT Mint",
                    "contractName", template.contractName(),
                    "standard", "Solana SPL single-supply mint",
                    "compilerVersion", template.compilerVersion(),
                    "evmVersion", template.evmVersion(),
                    "bytecodeHash", template.bytecodeHash(),
                    "features", List.of("SPL Token Program", "Decimals 0", "Single supply", "Authorities revoked"));
        }
        if (family == ContractFamily.POLKADOT) {
            if (type == TemplateType.ERC20) {
                return Map.of(
                        "family", family.apiValue(),
                        "type", type.name(),
                        "name", "Asset Hub Token",
                        "contractName", template.contractName(),
                        "standard", "Polkadot Asset Hub pallet-assets",
                        "compilerVersion", template.compilerVersion(),
                        "evmVersion", template.evmVersion(),
                        "bytecodeHash", template.bytecodeHash(),
                        "features", List.of("pallet-assets", "Metadata", "Initial supply", "Owner-controlled mint role"));
            }
            return Map.of(
                    "family", family.apiValue(),
                    "type", type.name(),
                    "name", "Asset Hub Single Asset",
                    "contractName", template.contractName(),
                    "standard", "Polkadot Asset Hub pallet-assets single supply",
                    "compilerVersion", template.compilerVersion(),
                    "evmVersion", template.evmVersion(),
                    "bytecodeHash", template.bytecodeHash(),
                    "features", List.of("pallet-assets", "Decimals 0", "Single supply", "No NFT metadata"));
        }
        if (family == ContractFamily.TON) {
            if (type == TemplateType.ERC20) {
                return Map.of(
                        "family", family.apiValue(),
                        "type", type.name(),
                        "name", "TON Jetton",
                        "contractName", template.contractName(),
                        "standard", "TEP-74 Jetton",
                        "compilerVersion", template.compilerVersion(),
                        "evmVersion", template.evmVersion(),
                        "bytecodeHash", template.bytecodeHash(),
                        "features", List.of("ton4j JettonMinter", "Off-chain metadata URI", "Optional initial mint", "Owner admin"));
            }
            return Map.of(
                    "family", family.apiValue(),
                    "type", type.name(),
                    "name", "TON NFT Collection",
                    "contractName", template.contractName(),
                    "standard", "TEP-62 NFT Collection",
                    "compilerVersion", template.compilerVersion(),
                    "evmVersion", template.evmVersion(),
                    "bytecodeHash", template.bytecodeHash(),
                    "features", List.of("ton4j NftCollection", "Collection metadata URI", "Base content URI", "Owner admin"));
        }
        if (family == ContractFamily.APTOS) {
            if (type == TemplateType.ERC20) {
                return Map.of(
                        "family", family.apiValue(),
                        "type", type.name(),
                        "name", "Aptos Coin",
                        "contractName", template.contractName(),
                        "standard", "Aptos Coin<T>",
                        "compilerVersion", template.compilerVersion(),
                        "evmVersion", template.evmVersion(),
                        "bytecodeHash", template.bytecodeHash(),
                        "features", List.of("Move package", "managed_coin", "Initial supply", "Max supply guard"));
            }
            return Map.of(
                    "family", family.apiValue(),
                    "type", type.name(),
                    "name", "Aptos Single Asset",
                    "contractName", template.contractName(),
                    "standard", "Aptos Coin<T> single supply",
                    "compilerVersion", template.compilerVersion(),
                    "evmVersion", template.evmVersion(),
                    "bytecodeHash", template.bytecodeHash(),
                    "features", List.of("Move package", "managed_coin", "Decimals 0", "Single supply"));
        }
        if (type == TemplateType.ERC20) {
            return Map.of(
                    "family", family.apiValue(),
                    "type", type.name(),
                    "name", family == ContractFamily.TRON ? "TRC20 Token" : "ERC20 Token",
                    "contractName", template.contractName(),
                    "standard", family == ContractFamily.TRON ? "OpenZeppelin TRC20" : "OpenZeppelin ERC20",
                    "compilerVersion", template.compilerVersion(),
                    "evmVersion", template.evmVersion(),
                    "bytecodeHash", template.bytecodeHash(),
                    "features", List.of("Ownable", "Capped", "Pausable", "Burnable", "Permit", "Optional owner mint"));
        }
        return Map.of(
                "family", family.apiValue(),
                "type", type.name(),
                "name", family == ContractFamily.TRON ? "TRC721 NFT Collection" : "ERC721 NFT Collection",
                "contractName", template.contractName(),
                "standard", family == ContractFamily.TRON ? "OpenZeppelin TRC721" : "OpenZeppelin ERC721",
                "compilerVersion", template.compilerVersion(),
                "evmVersion", template.evmVersion(),
                "bytecodeHash", template.bytecodeHash(),
                "features", List.of("Ownable", "Enumerable", "URI storage", "Pausable", "Burnable", "Owner mint"));
    }

    private CompiledTemplate load(ContractFamily family, TemplateType type, String contractName) {
        try {
            String sourceName = contractName + sourceExtension(family);
            String familyRoot = RESOURCE_ROOT + family.resourceFolder() + "/";
            String source = readResource(familyRoot + sourceName);
            if (family == ContractFamily.NEAR) {
                byte[] wasm = readResourceBytes(familyRoot + "artifacts/" + contractName + ".wasm");
                return new CompiledTemplate(
                        family,
                        type,
                        contractName,
                        sourceName,
                        "near-sdk-js 2.0.0",
                        "",
                        source,
                        "",
                        "[]",
                        sha256Hex(wasm),
                        wasm);
            }
            if (family == ContractFamily.SUI) {
                return new CompiledTemplate(
                        family,
                        type,
                        contractName,
                        sourceName,
                        "Sui Move CLI",
                        "",
                        source,
                        "",
                        "[]",
                        sha256Hex(source),
                        new byte[0]);
            }
            if (family == ContractFamily.SOLANA) {
                return new CompiledTemplate(
                        family,
                        type,
                        contractName,
                        sourceName,
                        "Solana SPL Token Program",
                        "",
                        source,
                        "",
                        "[]",
                        sha256Hex(source),
                        new byte[0]);
            }
            if (family == ContractFamily.APTOS) {
                return new CompiledTemplate(
                        family,
                        type,
                        contractName,
                        sourceName,
                        "Aptos Move CLI",
                        "",
                        source,
                        "",
                        "[]",
                        sha256Hex(source),
                        new byte[0]);
            }
            if (family == ContractFamily.POLKADOT) {
                return new CompiledTemplate(
                        family,
                        type,
                        contractName,
                        sourceName,
                        "Polkadot Asset Hub runtime",
                        "",
                        source,
                        "",
                        "[]",
                        sha256Hex(source),
                        new byte[0]);
            }
            if (family == ContractFamily.TON) {
                return new CompiledTemplate(
                        family,
                        type,
                        contractName,
                        sourceName,
                        "ton4j 2.1.0",
                        "",
                        source,
                        "",
                        "[]",
                        sha256Hex(source),
                        new byte[0]);
            }
            JsonNode artifact = objectMapper.readTree(readResource(
                    familyRoot + "artifacts/" + contractName + ".json"));
            String bytecode = text(artifact, "bytecode");
            String compilerVersion = text(artifact, "compilerVersion");
            String evmVersion = text(artifact, "evmVersion");
            JsonNode abi = artifact.get("abi");
            return new CompiledTemplate(
                    family,
                    type,
                    contractName,
                    sourceName,
                    compilerVersion,
                    evmVersion,
                    source,
                    bytecode,
                    abi == null ? "[]" : objectMapper.writeValueAsString(abi),
                    sha256Hex(bytecode),
                    new byte[0]);
        } catch (Exception e) {
            throw new IllegalStateException("unable to load contract template " + family + "/" + contractName, e);
        }
    }

    private static String sourceExtension(ContractFamily family) {
        if (family == ContractFamily.NEAR) {
            return ".ts";
        }
        if (family == ContractFamily.SUI) {
            return ".move";
        }
        if (family == ContractFamily.SOLANA) {
            return ".md";
        }
        if (family == ContractFamily.APTOS) {
            return ".move";
        }
        if (family == ContractFamily.POLKADOT) {
            return ".md";
        }
        if (family == ContractFamily.TON) {
            return ".md";
        }
        return ".sol";
    }

    private static String readResource(String path) throws Exception {
        ClassPathResource resource = new ClassPathResource(path);
        return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
    }

    private static byte[] readResourceBytes(String path) throws Exception {
        ClassPathResource resource = new ClassPathResource(path);
        return StreamUtils.copyToByteArray(resource.getInputStream());
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? "" : value.asText();
    }

    private static String sha256Hex(String value) throws Exception {
        return sha256Hex(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256Hex(byte[] value) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(value);
        return HexFormat.of().formatHex(hash).toLowerCase(Locale.ROOT);
    }

    public enum ContractFamily {
        EVM("evm", "evm"),
        TRON("tron", "tron"),
        NEAR("near", "near"),
        SOLANA("solana", "solana"),
        APTOS("aptos", "aptos"),
        SUI("sui", "sui"),
        POLKADOT("polkadot", "polkadot"),
        TON("ton", "ton");

        private final String apiValue;
        private final String resourceFolder;

        ContractFamily(String apiValue, String resourceFolder) {
            this.apiValue = apiValue;
            this.resourceFolder = resourceFolder;
        }

        public String apiValue() {
            return apiValue;
        }

        public String resourceFolder() {
            return resourceFolder;
        }

        public static ContractFamily parseProfileFamily(String value) {
            String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
            return switch (normalized) {
                case "evm" -> EVM;
                case "tron" -> TRON;
                case "near" -> NEAR;
                case "solana" -> SOLANA;
                case "aptos" -> APTOS;
                case "sui" -> SUI;
                case "polkadot" -> POLKADOT;
                case "ton" -> TON;
                default -> throw new IllegalArgumentException("unsupported contract family: " + value);
            };
        }
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
            ContractFamily family,
            TemplateType type,
            String contractName,
            String sourceName,
            String compilerVersion,
            String evmVersion,
            String source,
            String bytecode,
            String abiJson,
            String bytecodeHash,
            byte[] binary) {
    }
}
