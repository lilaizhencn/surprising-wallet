package com.surprising.wallet.chain.tron;

import org.tron.trident.abi.FunctionEncoder;
import org.tron.trident.abi.datatypes.Address;
import org.tron.trident.abi.datatypes.Function;
import org.tron.trident.abi.datatypes.Type;
import org.tron.trident.abi.datatypes.generated.Uint256;
import org.tron.trident.utils.Numeric;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Locale;

/**
 * TRC20 代币 ABI 编解码器，负责编码 transfer 函数调用和解码 Transfer 事件日志。
 *
 * <p>使用 trident 库的 {@link FunctionEncoder} 构造 TRC20 transfer(address,uint256) 的 ABI 编码输入数据。
 * 同时提供 Transfer 事件的 topic 常量和对数解码。
 * 金额计算使用整数（最小单位），不使用浮点。
 */
public final class Trc20AbiCodec {
    /**
     * 定义 {@code TRANSFER_TOPIC} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    public static final String TRANSFER_TOPIC = "ddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef";
    /**
     * 构造 {@code Trc20AbiCodec}，初始化该组件运行所需的状态和依赖。
     */
    private Trc20AbiCodec() {
    }
    /**
     * 编码或序列化 {@code encodeTransfer} 对应的数据，生成链上或接口需要的表示。
     */
    public static String encodeTransfer(String recipientBase58, BigDecimal amount, int decimals) {
        BigInteger rawAmount = toRawAmount(amount, decimals);
        List<Type> inputs = List.of(
                new Address(TronAddressCodec.toAbiAddress(recipientBase58)),
                new Uint256(rawAmount)
        );
        return Numeric.cleanHexPrefix(FunctionEncoder.encode(new Function("transfer", inputs, List.of())));
    }
    /**
     * 解析或转换 {@code decodeTransferLog} 对应的数据，并校验其格式和边界。
     */
    public static TransferLog decodeTransferLog(String contractHex, List<String> topics, String data, int decimals) {
        if (topics.size() < 3) {
            throw new IllegalArgumentException("TRC20 Transfer log requires at least 3 topics");
        }
        String topic0 = Numeric.cleanHexPrefix(topics.get(0)).toLowerCase(Locale.ROOT);
        if (!TRANSFER_TOPIC.equals(topic0)) {
            throw new IllegalArgumentException("not a TRC20 Transfer log");
        }
        String from = TronAddressCodec.topicAddressToBase58(topics.get(1));
        String to = TronAddressCodec.topicAddressToBase58(topics.get(2));
        BigInteger raw = Numeric.toBigInt(Numeric.cleanHexPrefix(data));
        return new TransferLog(TronAddressCodec.hexToBase58(contractHex), from, to, fromRawAmount(raw, decimals), raw);
    }
    /**
     * 编码 {@code toRawAmount} 对应的数据，生成链上或接口所需的表示。
     */
    public static BigInteger toRawAmount(BigDecimal amount, int decimals) {
        BigDecimal scaled = amount.movePointRight(decimals).stripTrailingZeros();
        if (scaled.scale() > 0) {
            throw new IllegalArgumentException("amount has more decimal places than token decimals");
        }
        BigInteger raw = scaled.toBigIntegerExact();
        if (raw.signum() < 0) {
            throw new IllegalArgumentException("amount must not be negative");
        }
        return raw;
    }
    /**
     * 解析 {@code fromRawAmount} 对应的输入，并转换为当前业务模型。
     */
    public static BigDecimal fromRawAmount(BigInteger rawAmount, int decimals) {
        if (rawAmount.signum() < 0) {
            throw new IllegalArgumentException("raw amount must not be negative");
        }
        return new BigDecimal(rawAmount).movePointLeft(decimals);
    }

    public record TransferLog(String contractAddress, String fromAddress, String toAddress,
                              BigDecimal amount, BigInteger rawAmount) {
    }
}
