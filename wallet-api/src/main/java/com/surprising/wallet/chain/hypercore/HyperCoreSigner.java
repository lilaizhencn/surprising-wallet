package com.surprising.wallet.chain.hypercore;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import org.web3j.crypto.StructuredDataEncoder;

import java.io.IOException;
import java.math.BigInteger;

/**
 * 负责交易或消息签名，并保持签名材料和编码规则一致。
 */
public class HyperCoreSigner {
    static final String SIGNATURE_CHAIN_ID = "0x66eee";
    private static final String DOMAIN_NAME = "HyperliquidSignTransaction";
    private static final String DOMAIN_VERSION = "1";
    private static final String ZERO_VERIFYING_CONTRACT = "0x0000000000000000000000000000000000000000";
    private final ObjectMapper objectMapper;
    public HyperCoreSigner() {
        this(new ObjectMapper());
    }

    HyperCoreSigner(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }
    byte[] hashUsdSend(ObjectNode action, boolean mainnet) {
        addCommonFields(action, mainnet);
        return hash(action, "HyperliquidTransaction:UsdSend", usdSendTypes());
    }
    byte[] hashSpotSend(ObjectNode action, boolean mainnet) {
        addCommonFields(action, mainnet);
        return hash(action, "HyperliquidTransaction:SpotSend", spotSendTypes());
    }
    private byte[] hash(ObjectNode action, String primaryType, ArrayNode payloadTypes) {
        try {
            ObjectNode typed = objectMapper.createObjectNode();
            ObjectNode domain = objectMapper.createObjectNode();
            domain.put("name", DOMAIN_NAME);
            domain.put("version", DOMAIN_VERSION);
            domain.put("chainId", new BigInteger(SIGNATURE_CHAIN_ID.substring(2), 16));
            domain.put("verifyingContract", ZERO_VERIFYING_CONTRACT);
            typed.set("domain", domain);

            ObjectNode types = objectMapper.createObjectNode();
            types.set(primaryType, payloadTypes);
            types.set("EIP712Domain", domainTypes());
            typed.set("types", types);
            typed.put("primaryType", primaryType);
            typed.set("message", action);
            return new StructuredDataEncoder(objectMapper.writeValueAsString(typed)).hashStructuredData();
        } catch (IOException e) {
            throw new IllegalStateException("HyperCore typed-data serialization failed", e);
        }
    }
    private void addCommonFields(ObjectNode action, boolean mainnet) {
        action.put("signatureChainId", SIGNATURE_CHAIN_ID);
        action.put("hyperliquidChain", mainnet ? "Mainnet" : "Testnet");
    }
    private ArrayNode usdSendTypes() {
        ArrayNode types = objectMapper.createArrayNode();
        addType(types, "hyperliquidChain", "string");
        addType(types, "destination", "string");
        addType(types, "amount", "string");
        addType(types, "time", "uint64");
        return types;
    }
    private ArrayNode spotSendTypes() {
        ArrayNode types = objectMapper.createArrayNode();
        addType(types, "hyperliquidChain", "string");
        addType(types, "destination", "string");
        addType(types, "token", "string");
        addType(types, "amount", "string");
        addType(types, "time", "uint64");
        return types;
    }
    private ArrayNode domainTypes() {
        ArrayNode types = objectMapper.createArrayNode();
        addType(types, "name", "string");
        addType(types, "version", "string");
        addType(types, "chainId", "uint256");
        addType(types, "verifyingContract", "address");
        return types;
    }
    private void addType(ArrayNode types, String name, String type) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("name", name);
        node.put("type", type);
        types.add(node);
    }
}
