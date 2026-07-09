package com.surprising.wallet.service.chain.sui;

import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

@Component
public class SuiPtbTransactionBuilder {
    private static final String BASE58_ALPHABET =
            "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
    private static final BigInteger BASE58_RADIX = BigInteger.valueOf(58);
    private static final int SUI_ADDRESS_LENGTH = 32;
    private static final int SUI_DIGEST_LENGTH = 32;

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

    private void writeSuiTransferPtb(BcsWriter out, String recipient, long amountMist) {
        out.vectorLength(2);
        writePure(out, u64Bytes(amountMist));
        writePure(out, SuiHex.addressBytes(recipient));

        out.vectorLength(2);
        writeSplitCoins(out, Argument.gasCoin(), List.of(Argument.input(0)));
        writeTransferObjects(out, List.of(Argument.result(0)), Argument.input(1));
    }

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

    private void writeGasData(BcsWriter out, List<SuiRpcClient.SuiCoin> gasPayment,
                              String owner, long gasPrice, long gasBudget) {
        out.vectorLength(gasPayment.size());
        gasPayment.forEach(coin -> writeObjectRef(out, coin));
        out.address(owner);
        out.u64(gasPrice);
        out.u64(gasBudget);
    }

    private void writePure(BcsWriter out, byte[] value) {
        out.variant(0); // CallArg::Pure
        out.bytes(value);
    }

    private void writeObjectInput(BcsWriter out, SuiRpcClient.SuiCoin coin) {
        out.variant(1); // CallArg::Object
        out.variant(0); // ObjectArg::ImmOrOwnedObject
        writeObjectRef(out, coin);
    }

    private void writeObjectRef(BcsWriter out, SuiRpcClient.SuiCoin coin) {
        Objects.requireNonNull(coin, "coin");
        out.fixedBytes(SuiHex.addressBytes(coin.objectId()), SUI_ADDRESS_LENGTH);
        out.u64(Long.parseUnsignedLong(coin.version()));
        out.fixedBytes(decodeDigest(coin.digest()), SUI_DIGEST_LENGTH);
    }

    private void writeTransferObjects(BcsWriter out, List<Argument> objects, Argument recipient) {
        out.variant(1); // Command::TransferObjects
        out.vectorLength(objects.size());
        objects.forEach(argument -> argument.write(out));
        recipient.write(out);
    }

    private void writeSplitCoins(BcsWriter out, Argument coin, List<Argument> amounts) {
        out.variant(2); // Command::SplitCoins
        coin.write(out);
        out.vectorLength(amounts.size());
        amounts.forEach(argument -> argument.write(out));
    }

    private void writeMergeCoins(BcsWriter out, Argument target, List<Argument> sources) {
        out.variant(3); // Command::MergeCoins
        target.write(out);
        out.vectorLength(sources.size());
        sources.forEach(argument -> argument.write(out));
    }

    private void validateGas(List<SuiRpcClient.SuiCoin> gasPayment, long gasPrice, long gasBudget) {
        if (gasPayment == null || gasPayment.isEmpty()) {
            throw new IllegalArgumentException("Sui transaction requires at least one gas coin");
        }
        requirePositive(gasPrice, "gasPrice");
        requirePositive(gasBudget, "gasBudget");
    }

    private void requirePositive(long value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private BigDecimal selectedTotal(List<SuiRpcClient.SuiCoin> coins) {
        BigDecimal total = BigDecimal.ZERO;
        for (SuiRpcClient.SuiCoin coin : coins) {
            total = total.add(coin.balance());
        }
        return total;
    }

    private byte[] u64Bytes(long value) {
        BcsWriter writer = new BcsWriter();
        writer.u64(value);
        return writer.bytes();
    }

    private byte[] decodeDigest(String base58Digest) {
        byte[] decoded = decodeBase58(base58Digest);
        if (decoded.length != SUI_DIGEST_LENGTH) {
            throw new IllegalArgumentException("Sui object digest must decode to 32 bytes");
        }
        return decoded;
    }

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
        static Argument gasCoin() {
            return new Argument(0, 0, 0, 0);
        }

        static Argument input(int index) {
            return new Argument(1, index, 0, 0);
        }

        static Argument result(int index) {
            return new Argument(2, 0, index, 0);
        }

        static Argument nestedResult(int result, int subresult) {
            return new Argument(3, 0, result, subresult);
        }

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

    @FunctionalInterface
    private interface CommandWriter {
        void write(BcsWriter out);
    }

    private static final class BcsWriter {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        void variant(int value) {
            uleb128(value);
        }

        void vectorLength(int value) {
            if (value < 0) {
                throw new IllegalArgumentException("BCS vector length is negative");
            }
            uleb128(value);
        }

        void bytes(byte[] value) {
            vectorLength(value.length);
            fixedBytes(value, value.length);
        }

        void address(String address) {
            fixedBytes(SuiHex.addressBytes(address), SUI_ADDRESS_LENGTH);
        }

        void fixedBytes(byte[] value, int expectedLength) {
            if (value.length != expectedLength) {
                throw new IllegalArgumentException("expected " + expectedLength + " bytes");
            }
            out.writeBytes(value);
        }

        void u16(int value) {
            if (value < 0 || value > 0xffff) {
                throw new IllegalArgumentException("BCS u16 out of range");
            }
            out.write(value & 0xff);
            out.write((value >>> 8) & 0xff);
        }

        void u64(long value) {
            for (int i = 0; i < Long.BYTES; i++) {
                out.write((int) ((value >>> (8 * i)) & 0xff));
            }
        }

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

        byte[] bytes() {
            return out.toByteArray();
        }

        @SuppressWarnings("unused")
        void string(String value) {
            bytes(value.getBytes(StandardCharsets.UTF_8));
        }
    }
}
