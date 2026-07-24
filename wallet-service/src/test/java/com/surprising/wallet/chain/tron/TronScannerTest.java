package com.surprising.wallet.chain.tron;

import com.google.protobuf.ByteString;
import org.bitcoinj.crypto.ECKey;
import org.junit.jupiter.api.Test;
import org.tron.trident.proto.Response;
import org.tron.trident.utils.Numeric;

import java.math.BigInteger;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TronScannerTest {
    @Test
    void transactionInfoLog_shouldDecodeTrc20Transfer() {
        String contractHex = TronTridentKeyFactory.toHexAddress(ECKey.fromPrivate(BigInteger.valueOf(70), true));
        String from = TronTridentKeyFactory.toBase58Address(ECKey.fromPrivate(BigInteger.valueOf(71), true));
        String to = TronTridentKeyFactory.toBase58Address(ECKey.fromPrivate(BigInteger.valueOf(72), true));

        Response.TransactionInfo txInfo = Response.TransactionInfo.newBuilder()
                .setBlockNumber(12345L)
                .addLog(Response.TransactionInfo.Log.newBuilder()
                        .setAddress(ByteString.copyFrom(Numeric.hexStringToByteArray(contractHex)))
                        .addTopics(ByteString.copyFrom(Numeric.hexStringToByteArray(Trc20AbiCodec.TRANSFER_TOPIC)))
                        .addTopics(ByteString.copyFrom(Numeric.hexStringToByteArray(topic(from))))
                        .addTopics(ByteString.copyFrom(Numeric.hexStringToByteArray(topic(to))))
                        .setData(ByteString.copyFrom(Numeric.hexStringToByteArray(
                                Numeric.toHexStringNoPrefixZeroPadded(BigInteger.valueOf(25_000_000L), 64))))
                        .build())
                .build();

        TronScanner scanner = new TronScanner();
        var events = scanner.decodeTrc20Transfers(txInfo,
                Map.of(contractHex, new TronScanner.TokenConfig("USDT", contractHex, 6)));

        assertEquals(1, events.size());
        assertEquals("USDT", events.getFirst().symbol());
        assertEquals(from, events.getFirst().fromAddress());
        assertEquals(to, events.getFirst().toAddress());
        assertEquals(0, events.getFirst().amount().compareTo(new java.math.BigDecimal("25")));
        assertEquals(12345L, events.getFirst().blockHeight());
    }

    @Test
    void transactionInfoLog_shouldDecodeNileTwentyByteContractAddress() {
        String contractHex = "41eca9bc828a3005b9a3b909f2cc5c2a54794de05f";
        String from = "TB1x9vmH5SbBd1EUaUePGbZzqmXGosFtxK";
        String to = "TQVqCyip5GwbNyURNWvDAkqcScmsvrBwzW";

        Response.TransactionInfo txInfo = Response.TransactionInfo.newBuilder()
                .setBlockNumber(68491716L)
                .addLog(Response.TransactionInfo.Log.newBuilder()
                        .setAddress(ByteString.copyFrom(Numeric.hexStringToByteArray(contractHex.substring(2))))
                        .addTopics(ByteString.copyFrom(Numeric.hexStringToByteArray(Trc20AbiCodec.TRANSFER_TOPIC)))
                        .addTopics(ByteString.copyFrom(Numeric.hexStringToByteArray(topic(from))))
                        .addTopics(ByteString.copyFrom(Numeric.hexStringToByteArray(topic(to))))
                        .setData(ByteString.copyFrom(Numeric.hexStringToByteArray(
                                Numeric.toHexStringNoPrefixZeroPadded(BigInteger.valueOf(30_000_000L), 64))))
                        .build())
                .build();

        TronScanner scanner = new TronScanner();
        var events = scanner.decodeTrc20Transfers(txInfo,
                Map.of(contractHex, new TronScanner.TokenConfig("USDT", contractHex, 6)));

        assertEquals(1, events.size());
        assertEquals(to, events.getFirst().toAddress());
        assertEquals(0, events.getFirst().amount().compareTo(new java.math.BigDecimal("30")));
    }

    private static String topic(String base58Address) {
        return "000000000000000000000000" + TronAddressCodec.base58ToHex(base58Address).substring(2);
    }
}
