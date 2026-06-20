package com.surprising.wallet.service.chain.tron;

import org.bitcoinj.crypto.ECKey;
import org.junit.jupiter.api.Test;
import org.tron.trident.utils.Numeric;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Trc20AbiEncodingTest {
    @Test
    void transfer_shouldEncodeFunctionSelectorAndAmount() {
        String recipient = TronTridentKeyFactory.toBase58Address(ECKey.fromPrivate(BigInteger.valueOf(50), true));
        String encoded = Trc20AbiCodec.encodeTransfer(recipient, new BigDecimal("1.25"), 6);
        assertTrue(encoded.startsWith("a9059cbb"));
        assertTrue(encoded.endsWith("00000000000000000000000000000000000000000000000000000000001312d0"));
    }

    @Test
    void transferEvent_shouldDecodeTopicsAndAmount() {
        String contract = TronTridentKeyFactory.toHexAddress(ECKey.fromPrivate(BigInteger.valueOf(51), true));
        String from = TronTridentKeyFactory.toBase58Address(ECKey.fromPrivate(BigInteger.valueOf(52), true));
        String to = TronTridentKeyFactory.toBase58Address(ECKey.fromPrivate(BigInteger.valueOf(53), true));
        String data = Numeric.toHexStringNoPrefixZeroPadded(BigInteger.valueOf(12_500_000L), 64);

        Trc20AbiCodec.TransferLog log = Trc20AbiCodec.decodeTransferLog(contract, List.of(
                Trc20AbiCodec.TRANSFER_TOPIC,
                topic(from),
                topic(to)
        ), data, 6);

        assertEquals(TronAddressCodec.hexToBase58(contract), log.contractAddress());
        assertEquals(from, log.fromAddress());
        assertEquals(to, log.toAddress());
        assertEquals(new BigDecimal("12.5"), log.amount().stripTrailingZeros());
        assertEquals(BigInteger.valueOf(12_500_000L), log.rawAmount());
    }

    @Test
    void amountWithTooManyDecimals_shouldFail() {
        String recipient = TronTridentKeyFactory.toBase58Address(ECKey.fromPrivate(BigInteger.valueOf(54), true));
        assertThrows(IllegalArgumentException.class,
                () -> Trc20AbiCodec.encodeTransfer(recipient, new BigDecimal("0.0000001"), 6));
    }

    private static String topic(String base58Address) {
        return "000000000000000000000000" + TronAddressCodec.base58ToHex(base58Address).substring(2);
    }
}
