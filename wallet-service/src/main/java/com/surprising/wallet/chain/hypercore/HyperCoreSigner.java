package com.surprising.wallet.chain.hypercore;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import org.bitcoinj.crypto.ECKey;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Sign;
import org.web3j.crypto.StructuredDataEncoder;
import org.web3j.utils.Numeric;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigInteger;

/**
 * 负责交易或消息签名，并保持签名材料和编码规则一致。
 */
@Component
public class HyperCoreSigner {
    /**
     * 定义 {@code SIGNATURE_CHAIN_ID} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    static final String SIGNATURE_CHAIN_ID = "0x66eee";
    /**
     * 定义 {@code DOMAIN_NAME} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    private static final String DOMAIN_NAME = "HyperliquidSignTransaction";
    /**
     * 定义 {@code DOMAIN_VERSION} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    private static final String DOMAIN_VERSION = "1";
    /**
     * 定义 {@code ZERO_VERIFYING_CONTRACT} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    private static final String ZERO_VERIFYING_CONTRACT = "0x0000000000000000000000000000000000000000";
    /**
     * 保存 {@code objectMapper}，用于保存业务集合或索引状态。
     */
    private final ObjectMapper objectMapper;
    /**
     * 构造 {@code HyperCoreSigner}，初始化该组件运行所需的状态和依赖。
     */
    public HyperCoreSigner() {
        this(new ObjectMapper());
    }

    /**
     * 构造 {@code HyperCoreSigner}，初始化该组件运行所需的状态和依赖。
     */
    HyperCoreSigner(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }
    /**
     * 为 {@code signUsdSend} 对应的交易或消息生成签名，并保持原始数据不被改变。
     */
    public ObjectNode signUsdSend(ObjectNode action, ECKey key, boolean mainnet) {
        addCommonFields(action, mainnet);
        return sign(action, key, "HyperliquidTransaction:UsdSend", usdSendTypes());
    }
    /**
     * 为 {@code signSpotSend} 对应的交易或消息生成签名，并保持原始数据不被改变。
     */
    public ObjectNode signSpotSend(ObjectNode action, ECKey key, boolean mainnet) {
        addCommonFields(action, mainnet);
        return sign(action, key, "HyperliquidTransaction:SpotSend", spotSendTypes());
    }
    /**
     * 判断 {@code hashUsdSend} 对应的条件是否成立，并返回明确的布尔结果。
     */
    byte[] hashUsdSend(ObjectNode action, boolean mainnet) {
        addCommonFields(action, mainnet);
        return hash(action, "HyperliquidTransaction:UsdSend", usdSendTypes());
    }
    /**
     * 为 {@code sign} 对应的交易或消息生成签名，并保持原始数据不被改变。
     */
    private ObjectNode sign(ObjectNode action, ECKey key, String primaryType, ArrayNode payloadTypes) {
        byte[] hash = hash(action, primaryType, payloadTypes);
        ECKeyPair keyPair = ECKeyPair.create(key.getPrivKey());
        Sign.SignatureData signature = Sign.signMessage(hash, keyPair, false);
        ObjectNode node = objectMapper.createObjectNode();
        node.put("r", Numeric.toHexString(signature.getR()));
        node.put("s", Numeric.toHexString(signature.getS()));
        node.put("v", signature.getV()[0] & 0xFF);
        return node;
    }
    /**
     * 判断 {@code hash} 对应的条件是否成立，并返回明确的布尔结果。
     */
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
    /**
     * 添加 {@code addCommonFields} 对应的业务对象，并更新当前组件的集合或索引。
     */
    private void addCommonFields(ObjectNode action, boolean mainnet) {
        action.put("signatureChainId", SIGNATURE_CHAIN_ID);
        action.put("hyperliquidChain", mainnet ? "Mainnet" : "Testnet");
    }
    /**
     * 执行 {@code usdSendTypes} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private ArrayNode usdSendTypes() {
        ArrayNode types = objectMapper.createArrayNode();
        addType(types, "hyperliquidChain", "string");
        addType(types, "destination", "string");
        addType(types, "amount", "string");
        addType(types, "time", "uint64");
        return types;
    }
    /**
     * 执行 {@code spotSendTypes} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private ArrayNode spotSendTypes() {
        ArrayNode types = objectMapper.createArrayNode();
        addType(types, "hyperliquidChain", "string");
        addType(types, "destination", "string");
        addType(types, "token", "string");
        addType(types, "amount", "string");
        addType(types, "time", "uint64");
        return types;
    }
    /**
     * 执行 {@code domainTypes} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private ArrayNode domainTypes() {
        ArrayNode types = objectMapper.createArrayNode();
        addType(types, "name", "string");
        addType(types, "version", "string");
        addType(types, "chainId", "uint256");
        addType(types, "verifyingContract", "address");
        return types;
    }
    /**
     * 添加 {@code addType} 对应的业务对象，并更新当前组件的集合或索引。
     */
    private void addType(ArrayNode types, String name, String type) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("name", name);
        node.put("type", type);
        types.add(node);
    }
}
