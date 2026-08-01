package com.surprising.wallet.chain.sui;

import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

/**
 * Sui PTB（Programmable Transaction Block）交易构造器。
 *
 * <p>手写 BCS 序列化逻辑，构造 Sui 的 Programmable Transaction 字节。
 * 支持 SUI 原生币转账和 Coin&lt;T&gt; 代币转账两种交易类型。</p>
 *
 * <p>交易结构：TransactionData::V1 -> TransactionKind::ProgrammableTransaction
 * -> sender -> gas data -> TransactionExpiration::None。</p>
 *
 * @see SuiTransactionSigner
 */
@Component
public
class SuiPtbTransactionBuilder {

    /** Base58 字符表 */
    private static final String BASE58_ALPHABET =
            "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
    /**
     * 定义 {@code BASE58_RADIX} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    private static final BigInteger BASE58_RADIX = BigInteger.valueOf(58);
    /**
     * 定义 {@code SUI_ADDRESS_LENGTH} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    private static final int SUI_ADDRESS_LENGTH = 32;
    /**
     * 定义 {@code SUI_DIGEST_LENGTH} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    private static final int SUI_DIGEST_LENGTH = 32;

    /**
     * 构建或生成 {@code buildSuiTransfer} 对应的结果，并执行输入和状态校验。
     */
    public String buildSuiTransfer(String sender, List<SuiRpcClient.SuiCoin> gasPayment,
                                   String recipient, long amountMist, long gasPrice, long gasBudget) {
        requirePositive(amountMist, "amountMist");
        validateGas(gasPayment, gasPrice, gasBudget);
        BcsWriter out = new BcsWriter();
        out.variant(0); // TransactionData::V1
        out.variant(0); // TransactionKind::ProgrammableTransaction
        writeSuiTransferPtb(out, recipient, amountMist);
        out.address(sender);
        writeGasData(out, gasPayment, sender, gasPrice, gasBudget);
        out.variant(0); // TransactionExpiration::None
        return Base64.getEncoder().encodeToString(out.bytes());
    }

    /**
     * 构建或生成 {@code buildCoinTransfer} 对应的结果，并执行输入和状态校验。
     */
    public String buildCoinTransfer(String sender, List<SuiRpcClient.SuiCoin> inputCoins,
                                    List<SuiRpcClient.SuiCoin> gasPayment, String recipient,
                                    long amountAtomic, long gasPrice, long gasBudget) {
        requirePositive(amountAtomic, "amountAtomic");
        validateGas(gasPayment, gasPrice, gasBudget);
        if (inputCoins == null || inputCoins.isEmpty()) {
            throw new IllegalArgumentException("Sui coin transfer requires at least one input coin");
        }
        BcsWriter out = new BcsWriter();
        out.variant(0); // TransactionData::V1
        out.variant(0); // TransactionKind::ProgrammableTransaction
        writeCoinTransferPtb(out, inputCoins, recipient, amountAtomic);
        out.address(sender);
        writeGasData(out, gasPayment, sender, gasPrice, gasBudget);
        out.variant(0); // TransactionExpiration::None
        return Base64.getEncoder().encodeToString(out.bytes());
    }
    /**
     * 执行 {@code writeSuiTransferPtb} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private void writeSuiTransferPtb(BcsWriter out, String recipient, long amountMist) {
        out.vectorLength(2);
        writePure(out, SuiHex.addressBytes(recipient));
        writePure(out, u64Bytes(amountMist));

        out.vectorLength(2);
        writeSplitCoins(out, Argument.gasCoin(), List.of(Argument.input(1)));
        writeTransferObjects(out, List.of(Argument.result(0)), Argument.input(0));
    }

    /**
     * 执行 {@code writeCoinTransferPtb} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private void writeCoinTransferPtb(BcsWriter out, List<SuiRpcClient.SuiCoin> inputCoins,
                                      String recipient, long amountAtomic) {
        boolean transferWholeInput = selectedTotal(inputCoins).compareTo(BigDecimal.valueOf(amountAtomic)) == 0;
        int pureInputs = transferWholeInput ? 1 : 2;
        int recipientInputIndex = inputCoins.size() + pureInputs - 1;
        out.vectorLength(inputCoins.size() + pureInputs);
        for (SuiRpcClient.SuiCoin coin : inputCoins) {
            writeObjectInput(out, coin);
        }
        if (!transferWholeInput) {
            writePure(out, u64Bytes(amountAtomic));
        }
        writePure(out, SuiHex.addressBytes(recipient));

        List<CommandWriter> commands = new ArrayList<>();
        if (inputCoins.size() > 1) {
            List<Argument> mergeSources = new ArrayList<>();
            for (int i = 1; i < inputCoins.size(); i++) {
                mergeSources.add(Argument.input(i));
            }
            commands.add(writer -> writeMergeCoins(writer, Argument.input(0), mergeSources));
        }
        if (transferWholeInput) {
            commands.add(writer -> writeTransferObjects(writer, List.of(Argument.input(0)),
                    Argument.input(recipientInputIndex)));
        } else {
            int splitCommandIndex = commands.size();
            commands.add(writer -> writeSplitCoins(writer, Argument.input(0), List.of(Argument.input(inputCoins.size()))));
            commands.add(writer -> writeTransferObjects(writer, List.of(Argument.nestedResult(splitCommandIndex, 0)),
                    Argument.input(recipientInputIndex)));
        }

        out.vectorLength(commands.size());
        commands.forEach(command -> command.write(out));
    }

    /**
     * 执行 {@code writeGasData} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private void writeGasData(BcsWriter out, List<SuiRpcClient.SuiCoin> gasPayment,
                              String owner, long gasPrice, long gasBudget) {
        out.vectorLength(gasPayment.size());
        gasPayment.forEach(coin -> writeObjectRef(out, coin));
        out.address(owner);
        out.u64(gasPrice);
        out.u64(gasBudget);
    }
    /**
     * 执行 {@code writePure} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private void writePure(BcsWriter out, byte[] value) {
        out.variant(0); // CallArg::Pure
        out.bytes(value);
    }
    /**
     * 执行 {@code writeObjectInput} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private void writeObjectInput(BcsWriter out, SuiRpcClient.SuiCoin coin) {
        out.variant(1); // CallArg::Object
        out.variant(0); // ObjectArg::ImmOrOwnedObject
        writeObjectRef(out, coin);
    }
    /**
     * 执行 {@code writeObjectRef} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private void writeObjectRef(BcsWriter out, SuiRpcClient.SuiCoin coin) {
        Objects.requireNonNull(coin, "coin");
        out.fixedBytes(SuiHex.addressBytes(coin.objectId()), SUI_ADDRESS_LENGTH);
        out.u64(Long.parseUnsignedLong(coin.version()));
        out.bytes(decodeDigest(coin.digest()));
    }
    /**
     * 执行 {@code writeTransferObjects} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private void writeTransferObjects(BcsWriter out, List<Argument> objects, Argument recipient) {
        out.variant(1); // Command::TransferObjects
        out.vectorLength(objects.size());
        objects.forEach(argument -> argument.write(out));
        recipient.write(out);
    }
    /**
     * 执行 {@code writeSplitCoins} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private void writeSplitCoins(BcsWriter out, Argument coin, List<Argument> amounts) {
        out.variant(2); // Command::SplitCoins
        coin.write(out);
        out.vectorLength(amounts.size());
        amounts.forEach(argument -> argument.write(out));
    }
    /**
     * 执行 {@code writeMergeCoins} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private void writeMergeCoins(BcsWriter out, Argument target, List<Argument> sources) {
        out.variant(3); // Command::MergeCoins
        target.write(out);
        out.vectorLength(sources.size());
        sources.forEach(argument -> argument.write(out));
    }
    /**
     * 校验 {@code validateGas} 对应的前置条件，不满足时抛出明确异常。
     */
    private void validateGas(List<SuiRpcClient.SuiCoin> gasPayment, long gasPrice, long gasBudget) {
        if (gasPayment == null || gasPayment.isEmpty()) {
            throw new IllegalArgumentException("Sui transaction requires at least one gas coin");
        }
        requirePositive(gasPrice, "gasPrice");
        requirePositive(gasBudget, "gasBudget");
    }
    /**
     * 校验 {@code requirePositive} 对应的前置条件，不满足时抛出明确异常。
     */
    private void requirePositive(long value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
    /**
     * 获取或查询 {@code selectedTotal} 对应的数据，并向调用方返回当前业务状态。
     */
    private BigDecimal selectedTotal(List<SuiRpcClient.SuiCoin> coins) {
        BigDecimal total = BigDecimal.ZERO;
        for (SuiRpcClient.SuiCoin coin : coins) {
            total = total.add(coin.balance());
        }
        return total;
    }
    /**
     * 执行 {@code u64Bytes} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private byte[] u64Bytes(long value) {
        BcsWriter writer = new BcsWriter();
        writer.u64(value);
        return writer.bytes();
    }
    /**
     * 解析或转换 {@code decodeDigest} 对应的数据，并校验其格式和边界。
     */
    private byte[] decodeDigest(String base58Digest) {
        byte[] decoded = decodeBase58(base58Digest);
        if (decoded.length != SUI_DIGEST_LENGTH) {
            throw new IllegalArgumentException("Sui object digest must decode to 32 bytes");
        }
        return decoded;
    }
    /**
     * 解析或转换 {@code decodeBase58} 对应的数据，并校验其格式和边界。
     */
    private byte[] decodeBase58(String input) {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("base58 value is blank");
        }
        BigInteger value = BigInteger.ZERO;
        for (int i = 0; i < input.length(); i++) {
            int digit = BASE58_ALPHABET.indexOf(input.charAt(i));
            if (digit < 0) {
                throw new IllegalArgumentException("invalid base58 character");
            }
            value = value.multiply(BASE58_RADIX).add(BigInteger.valueOf(digit));
        }
        byte[] raw = value.equals(BigInteger.ZERO) ? new byte[0] : value.toByteArray();
        if (raw.length > 0 && raw[0] == 0) {
            byte[] unsigned = new byte[raw.length - 1];
            System.arraycopy(raw, 1, unsigned, 0, unsigned.length);
            raw = unsigned;
        }
        int zeros = 0;
        while (zeros < input.length() && input.charAt(zeros) == '1') {
            zeros++;
        }
        byte[] decoded = new byte[zeros + raw.length];
        System.arraycopy(raw, 0, decoded, zeros, raw.length);
        return decoded;
    }
    private record Argument(int kind, int input, int result, int subresult) {
        /**
         * 执行 {@code gasCoin} 对应的辅助逻辑，完成数据处理并维护状态边界。
         */
        static Argument gasCoin() {
            return new Argument(0, 0, 0, 0);
        }

        /**
         * 执行 {@code input} 对应的辅助逻辑，完成数据处理并维护状态边界。
         */
        static Argument input(int index) {
            return new Argument(1, index, 0, 0);
        }

        /**
         * 执行 {@code result} 对应的辅助逻辑，完成数据处理并维护状态边界。
         */
        static Argument result(int index) {
            return new Argument(2, 0, index, 0);
        }

        /**
         * 执行 {@code nestedResult} 对应的辅助逻辑，完成数据处理并维护状态边界。
         */
        static Argument nestedResult(int result, int subresult) {
            return new Argument(3, 0, result, subresult);
        }

        /**
         * 执行 {@code write} 对应的辅助逻辑，完成数据处理并维护状态边界。
         */
        void write(BcsWriter out) {
            out.variant(kind);
            switch (kind) {
                case 1 -> out.u16(input);
                case 2 -> out.u16(result);
                case 3 -> {
                    out.u16(result);
                    out.u16(subresult);
                }
                default -> {
                }
            }
        }
    }

    /**
     * 该类型封装所在链或钱包模块的配置、业务状态和校验逻辑。
     */
    @FunctionalInterface
    private interface CommandWriter {
        /**
         * 执行 {@code write} 对应的辅助逻辑，完成数据处理并维护状态边界。
         */
        void write(BcsWriter out);
    }
    /**
     * 该类型封装所在链或钱包模块的配置、业务状态和校验逻辑。
     */
    private static final class BcsWriter {
        /**
         * 保存 {@code out}，用于承载当前对象的运行配置或业务数据。
         */
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        /**
         * 执行 {@code variant} 对应的辅助逻辑，完成数据处理并维护状态边界。
         */
        void variant(int value) {
            uleb128(value);
        }

        /**
         * 执行 {@code vectorLength} 对应的辅助逻辑，完成数据处理并维护状态边界。
         */
        void vectorLength(int value) {
            if (value < 0) {
                throw new IllegalArgumentException("BCS vector length is negative");
            }
            uleb128(value);
        }

        /**
         * 执行 {@code bytes} 对应的辅助逻辑，完成数据处理并维护状态边界。
         */
        void bytes(byte[] value) {
            vectorLength(value.length);
            fixedBytes(value, value.length);
        }

        /**
         * 添加 {@code address} 对应的业务对象，并更新当前组件的集合或索引。
         */
        void address(String address) {
            fixedBytes(SuiHex.addressBytes(address), SUI_ADDRESS_LENGTH);
        }

        /**
         * 执行 {@code fixedBytes} 对应的辅助逻辑，完成数据处理并维护状态边界。
         */
        void fixedBytes(byte[] value, int expectedLength) {
            if (value.length != expectedLength) {
                throw new IllegalArgumentException("expected " + expectedLength + " bytes");
            }
            out.writeBytes(value);
        }

        /**
         * 执行 {@code u16} 对应的辅助逻辑，完成数据处理并维护状态边界。
         */
        void u16(int value) {
            if (value < 0 || value > 0xffff) {
                throw new IllegalArgumentException("BCS u16 out of range");
            }
            out.write(value & 0xff);
            out.write((value >>> 8) & 0xff);
        }

        /**
         * 执行 {@code u64} 对应的辅助逻辑，完成数据处理并维护状态边界。
         */
        void u64(long value) {
            for (int i = 0; i < Long.BYTES; i++) {
                out.write((int) ((value >>> (8 * i)) & 0xff));
            }
        }

        /**
         * 执行 {@code uleb128} 对应的辅助逻辑，完成数据处理并维护状态边界。
         */
        void uleb128(int value) {
            int remaining = value;
            do {
                int digit = remaining & 0x7f;
                remaining >>>= 7;
                if (remaining != 0) {
                    digit |= 0x80;
                }
                out.write(digit);
            } while (remaining != 0);
        }

        /**
         * 执行 {@code bytes} 对应的辅助逻辑，完成数据处理并维护状态边界。
         */
        byte[] bytes() {
            return out.toByteArray();
        }

        /**
         * 转换或计算 {@code string} 对应的值，统一金额、格式和边界规则。
         */
        @SuppressWarnings("unused")
        void string(String value) {
            bytes(value.getBytes(StandardCharsets.UTF_8));
        }
    }
}
