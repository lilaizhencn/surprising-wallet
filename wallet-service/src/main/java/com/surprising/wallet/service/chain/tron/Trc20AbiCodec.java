package com.surprising.wallet.service.chain.tron;

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
 * Encodes TRC20 transfer calls and decodes Transfer logs.
 * Amount conversion is integer based: token decimals come from token_config and
 * no floating point arithmetic is used.
 */
public final class Trc20AbiCodec {
    public static final String TRANSFER_TOPIC = "ddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef";    private Trc20AbiCodec() {
    }
    public static String encodeTransfer(String recipientBase58, BigDecimal amount, int decimals) {
        BigInteger rawAmount = toRawAmount(amount, decimals);
        List<Type> inputs = List.of(
                new Address(TronAddressCodec.toAbiAddress(recipientBase58)),
                new Uint256(rawAmount)
        );
        return Numeric.cleanHexPrefix(FunctionEncoder.encode(new Function("transfer", inputs, List.of())));
    }
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
