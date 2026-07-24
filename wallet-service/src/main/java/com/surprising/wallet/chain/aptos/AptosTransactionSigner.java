package com.surprising.wallet.chain.aptos;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.surprising.wallet.common.key.Ed25519DerivedKey;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Aptos 交易签名器，负责构造和签名 Aptos 交易（entry function 模式）。
 *
 * <p>签名流程：先用 {@link AptosBcs} 对交易进行 BCS 序列化，再对序列化结果拼接
 * SHA3-256("APTOS::RawTransaction") 前缀后进行哈希，最后用 Ed25519 签名。</p>
 *
 * <p>支持以下交易类型：
 * <ul>
 *   <li>APT 原生转账（aptos_account::transfer）</li>
 *   <li>FA 同质化资产转账（primary_fungible_store::transfer）</li>
 *   <li>通用 entry function 调用</li>
 *   <li>合约发布（code::publish_package_txn）</li>
 * </ul></p>
 */
@Component
@RequiredArgsConstructor
public class AptosTransactionSigner {

    /** 交易签名前缀：SHA3-256("APTOS::RawTransaction") */
    private static final byte[] RAW_TRANSACTION_PREFIX = sha3("APTOS::RawTransaction".getBytes());

    /** APT 转账模块 */
    private static final String APTOS_ACCOUNT_MODULE = "0x1::aptos_account";

    /** FA 转账模块 */
    private static final String PRIMARY_FUNGIBLE_STORE_MODULE = "0x1::primary_fungible_store";

    /** FA 元数据类型参数 */
    private static final String FUNGIBLE_ASSET_METADATA_TYPE = "0x1::fungible_asset::Metadata";

    private static final HexFormat HEX = HexFormat.of();

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Aptos 密钥服务 */
    private final AptosKeyService keyService;

    /**
     * 签名 APT 原生转账（简化模式）。
     *
     * @param derivationIndex 派生索引
     * @param sender          发送方地址
     * @param sequenceNumber  账户序列号
     * @param recipient       接收方地址
     * @param amountOctas     转账金额（octas）
     * @param maxGasAmount    最大 Gas 用量
     * @param gasUnitPrice     Gas 单价
     * @param chainId         链 ID
     * @return 已签名交易
     */
    public SignedTransaction nativeTransfer(long derivationIndex, String sender, long sequenceNumber,
                                            String recipient, long amountOctas, long maxGasAmount,
                                            long gasUnitPrice, int chainId) {
        return nativeTransfer(0L, 0, derivationIndex, sender, sequenceNumber,
                recipient, amountOctas, maxGasAmount, gasUnitPrice, chainId);
    }

    /**
     * 签名 APT 原生转账（完整模式，含 userId 和 biz）。
     *
     * @see #nativeTransfer(long, String, long, String, long, long, long, int)
     */
    public SignedTransaction nativeTransfer(long userId, int biz, long derivationIndex,
                                            String sender, long sequenceNumber,
                                            String recipient, long amountOctas, long maxGasAmount,
                                            long gasUnitPrice, int chainId) {
        return sign(userId, biz, derivationIndex, sender, sequenceNumber,
                APTOS_ACCOUNT_MODULE, "transfer", List.of(),
                List.of(FunctionArgument.address(recipient), FunctionArgument.u64(amountOctas)),
                maxGasAmount, gasUnitPrice, chainId);
    }

    /**
     * 签名 FA 同质化资产转账（简化模式）。
     *
     * @param derivationIndex 派生索引
     * @param sender          发送方地址
     * @param sequenceNumber  账户序列号
     * @param metadataAddress FA 元数据地址
     * @param recipient       接收方地址
     * @param amountAtomic    转账金额（原子单位）
     * @param maxGasAmount    最大 Gas 用量
     * @param gasUnitPrice     Gas 单价
     * @param chainId         链 ID
     * @return 已签名交易
     */
    public SignedTransaction fungibleAssetTransfer(long derivationIndex, String sender, long sequenceNumber,
                                                    String metadataAddress, String recipient, long amountAtomic,
                                                    long maxGasAmount, long gasUnitPrice, int chainId) {
        return fungibleAssetTransfer(0L, 0, derivationIndex, sender, sequenceNumber,
                metadataAddress, recipient, amountAtomic, maxGasAmount, gasUnitPrice, chainId);
    }

    /**
     * 签名 FA 同质化资产转账（完整模式）。
     */
    public SignedTransaction fungibleAssetTransfer(long userId, int biz, long derivationIndex,
                                                    String sender, long sequenceNumber,
                                                    String metadataAddress, String recipient, long amountAtomic,
                                                    long maxGasAmount, long gasUnitPrice, int chainId) {
        return sign(userId, biz, derivationIndex, sender, sequenceNumber,
                PRIMARY_FUNGIBLE_STORE_MODULE, "transfer", List.of(FUNGIBLE_ASSET_METADATA_TYPE),
                List.of(FunctionArgument.address(metadataAddress), FunctionArgument.address(recipient),
                        FunctionArgument.u64(amountAtomic)),
                maxGasAmount, gasUnitPrice, chainId);
    }

    /**
     * 签名通用 entry function 调用（简化模式）。
     *
     * @param derivationIndex 派生索引
     * @param sender          发送方地址
     * @param sequenceNumber  账户序列号
     * @param module          Move 模块地址，如 "0x1::aptos_account"
     * @param function        函数名，如 "transfer"
     * @param typeArguments   类型参数列表
     * @param arguments       函数参数列表
     * @param maxGasAmount    最大 Gas 用量
     * @param gasUnitPrice     Gas 单价
     * @param chainId         链 ID
     * @return 已签名交易
     */
    public SignedTransaction entryFunction(long derivationIndex, String sender, long sequenceNumber,
                                           String module, String function, List<String> typeArguments,
                                           List<FunctionArgument> arguments, long maxGasAmount,
                                           long gasUnitPrice, int chainId) {
        return entryFunction(0L, 0, derivationIndex, sender, sequenceNumber,
                module, function, typeArguments, arguments, maxGasAmount, gasUnitPrice, chainId);
    }

    /**
     * 签名通用 entry function 调用（完整模式）。
     */
    public SignedTransaction entryFunction(long userId, int biz, long derivationIndex,
                                           String sender, long sequenceNumber,
                                           String module, String function, List<String> typeArguments,
                                           List<FunctionArgument> arguments, long maxGasAmount,
                                           long gasUnitPrice, int chainId) {
        return sign(userId, biz, derivationIndex, sender, sequenceNumber, module, function, typeArguments,
                arguments, maxGasAmount, gasUnitPrice, chainId);
    }

    /**
     * 签名合约发布交易。
     *
     * <p>调用 <code>0x1::code::publish_package_txn</code> 发布 Move 包。</p>
     *
     * @param userId          用户 ID
     * @param biz             业务 ID
     * @param derivationIndex 派生索引
     * @param sender          发送方地址
     * @param sequenceNumber  账户序列号
     * @param metadata        包元数据字节
     * @param modules         模块字节码列表
     * @param maxGasAmount    最大 Gas 用量
     * @param gasUnitPrice     Gas 单价
     * @param chainId         链 ID
     * @return 已签名交易
     */
    public SignedTransaction publishPackage(long userId, int biz, long derivationIndex,
                                            String sender, long sequenceNumber,
                                            byte[] metadata,
                                            List<byte[]> modules,
                                            long maxGasAmount,
                                            long gasUnitPrice,
                                            int chainId) {
        return sign(userId, biz, derivationIndex, sender, sequenceNumber,
                "0x1::code", "publish_package_txn", List.of(),
                List.of(FunctionArgument.u8Vector(metadata), FunctionArgument.u8VectorVector(modules)),
                maxGasAmount, gasUnitPrice, chainId);
    }

    /**
     * 统一签名入口：BCS 序列化交易 -> 计算签名消息 -> Ed25519 签名 -> 构造 JSON 请求体。
     *
     * <p>过期时间设置为当前时间 + 600 秒。</p>
     */
    private SignedTransaction sign(long userId, int biz, long derivationIndex,
                                   String sender, long sequenceNumber,
                                   String module, String function, List<String> typeArguments,
                                   List<FunctionArgument> arguments, long maxGasAmount, long gasUnitPrice,
                                   int chainId) {
        long expiration = Instant.now().plusSeconds(600).getEpochSecond();
        AptosBcs.EntryFunctionPayload payload = new AptosBcs.EntryFunctionPayload(
                module, function, typeArguments, arguments.stream().map(FunctionArgument::bcs).toList());
        byte[] raw = AptosBcs.rawEntryFunctionTransaction(sender, sequenceNumber, payload,
                maxGasAmount, gasUnitPrice, expiration, chainId);
        byte[] signingMessage = signingMessage(raw);
        byte[] signature = keyService.sign(userId, biz, derivationIndex, signingMessage);
        Ed25519DerivedKey key = keyService.derive(userId, biz, derivationIndex);

        ObjectNode request = objectMapper.createObjectNode();
        request.put("sender", AptosHex.normalizeAddress(sender));
        request.put("sequence_number", Long.toUnsignedString(sequenceNumber));
        request.put("max_gas_amount", Long.toUnsignedString(maxGasAmount));
        request.put("gas_unit_price", Long.toUnsignedString(gasUnitPrice));
        request.put("expiration_timestamp_secs", Long.toUnsignedString(expiration));
        request.set("payload", jsonPayload(module, function, typeArguments, arguments));
        ObjectNode sig = objectMapper.createObjectNode();
        sig.put("type", "ed25519_signature");
        sig.put("public_key", AptosHex.withPrefix(key.publicKey()));
        sig.put("signature", AptosHex.withPrefix(signature));
        request.set("signature", sig);
        return new SignedTransaction(sequenceNumber, maxGasAmount, gasUnitPrice, expiration, raw, request);
    }

    /**
     * 构造 entry_function_payload 的 JSON 表示。
     *
     * @param module        Move 模块地址
     * @param function      函数名
     * @param typeArguments 类型参数
     * @param arguments     entry function 参数
     * @return payload JSON 对象
     */
    private ObjectNode jsonPayload(String module, String function, List<String> typeArguments,
                                   List<FunctionArgument> arguments) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("type", "entry_function_payload");
        payload.put("function", module + "::" + function);
        ArrayNode typeArgs = objectMapper.createArrayNode();
        typeArguments.forEach(typeArgs::add);
        payload.set("type_arguments", typeArgs);
        ArrayNode args = objectMapper.createArrayNode();
        arguments.forEach(argument -> args.add(objectMapper.valueToTree(argument.jsonValue())));
        payload.set("arguments", args);
        return payload;
    }

    /**
     * 构造签名消息：SHA3-256("APTOS::RawTransaction") || rawTransaction。
     *
     * @param rawTransaction BCS 序列化的原始交易
     * @return 签名消息字节
     */
    private static byte[] signingMessage(byte[] rawTransaction) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(RAW_TRANSACTION_PREFIX, 0, RAW_TRANSACTION_PREFIX.length);
        out.write(rawTransaction, 0, rawTransaction.length);
        return out.toByteArray();
    }

    /**
     * 对字节数组进行 SHA3-256 哈希。
     *
     * @param value 待哈希的字节
     * @return 哈希结果（32 字节）
     * @throws IllegalStateException 如果 SHA3-256 不可用
     */
    private static byte[] sha3(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA3-256").digest(value);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA3-256 is required for Aptos signing", e);
        }
    }

    /**
     * 已签名的 Aptos 交易。
     *
     * @param sequenceNumber         序列号
     * @param maxGasAmount           最大 Gas 用量
     * @param gasUnitPrice            Gas 单价
     * @param expirationTimestampSecs 过期时间戳（秒）
     * @param rawTransaction          BCS 序列化的原始交易字节
     * @param json                    用于 JSON 提交的交易对象
     */
    public record SignedTransaction(long sequenceNumber, long maxGasAmount, long gasUnitPrice,
                                    long expirationTimestampSecs, byte[] rawTransaction,
                                    ObjectNode json) {
    }

    /**
     * entry function 参数，包含 BCS 序列化字节和 JSON 值两种表示。
     *
     * <p>提供工厂方法创建不同类型的参数：{@link #address}, {@link #u64}, {@link #u8Vector}, {@link #u8VectorVector}。</p>
     *
     * @param bcs       BCS 序列化后的字节
     * @param jsonValue JSON 表示的值
     */
    public record FunctionArgument(byte[] bcs, Object jsonValue) {
        public FunctionArgument {
            Objects.requireNonNull(bcs, "bcs");
            Objects.requireNonNull(jsonValue, "jsonValue");
        }

        /**
         * 创建地址类型参数。
         */
        public static FunctionArgument address(String address) {
            return new FunctionArgument(AptosBcs.addressArg(address), AptosHex.normalizeAddress(address));
        }

        /**
         * 创建 u64 类型参数。
         */
        public static FunctionArgument u64(long value) {
            return new FunctionArgument(AptosBcs.u64Arg(value), Long.toUnsignedString(value));
        }

        /**
         * 创建 u8 vector 类型参数。
         */
        public static FunctionArgument u8Vector(byte[] value) {
            return new FunctionArgument(AptosBcs.u8VectorArg(value), "0x" + HEX.formatHex(value));
        }

        /**
         * 创建 u8 vector of vectors 类型参数。
         */
        public static FunctionArgument u8VectorVector(List<byte[]> values) {
            List<String> json = values.stream()
                    .map(bytes -> "0x" + HEX.formatHex(bytes))
                    .toList();
            return new FunctionArgument(AptosBcs.u8VectorVectorArg(values), json);
        }
    }
}
