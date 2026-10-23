package com.surprising.wallet.chain.utxo;

import com.surprising.wallet.common.json.JacksonJson;
import com.surprising.wallet.common.key.WalletKeyMaterialProvider;
import com.surprising.wallet.common.pojo.Address;
import com.surprising.wallet.common.pojo.UtxoTransaction;
import com.surprising.wallet.common.pojo.WithdrawRecord;
import com.surprising.wallet.common.pojo.WithdrawTransaction;
import com.surprising.wallet.sdk.bitcoinj.bip.Bip32Node;
import com.surprising.wallet.sig.first.config.PubKeyConfig;
import com.surprising.wallet.sig.first.service.BtcFirstSignService;
import com.surprising.wallet.sig.first.service.ISignService;
import com.surprising.wallet.sig.first.service.LtcFirstSignService;
import org.bitcoinj.base.LegacyAddress;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.crypto.ECKey;
import org.bitcoinj.params.TestNet3Params;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SegwitFirstSigningTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final String recipient = LegacyAddress.fromKey(TestNet3Params.get(),
            ECKey.fromPrivate(BigInteger.valueOf(5))).toBase58();

    @ParameterizedTest
    @ValueSource(strings = {"BTC", "LTC"})
    void signsExactAmountsWithAConservedFee(String chain) {
        var result = sign(chain, "0.001", "0.001", "0.0005", recipient);
        assertTrue(result.path("valid").asBoolean(), result.path("error").asText());
        var tx = Transaction.read(ByteBuffer.wrap(HexFormat.of().parseHex(result.path("firstSignTx").asText())));
        long outputs = tx.getOutputs().stream().mapToLong(output -> output.getValue().getValue()).sum();
        assertEquals(100_000L, Math.addExact(outputs, result.path("fee").asLong()));
        assertEquals(50_000L, tx.getOutput(0).getValue().getValue());
        assertEquals(3, tx.getInput(0).getWitness().getPushCount());
    }

    @ParameterizedTest
    @ValueSource(strings = {"BTC", "LTC"})
    void rejectsFractionalInputUnits(String chain) {
        assertFalse(sign(chain, "0.001000009", "0.001000009", "0.0005", recipient).path("valid").asBoolean());
    }

    @ParameterizedTest
    @ValueSource(strings = {"BTC", "LTC"})
    void rejectsFractionalAggregateUnits(String chain) {
        assertFalse(sign(chain, "0.001", "0.001000009", "0.0005", recipient).path("valid").asBoolean());
    }

    @ParameterizedTest
    @ValueSource(strings = {"BTC", "LTC"})
    void rejectsFractionalOutputUnits(String chain) {
        assertFalse(sign(chain, "0.001", "0.001", "0.000500009", recipient).path("valid").asBoolean());
    }

    @ParameterizedTest
    @ValueSource(strings = {"BTC", "LTC"})
    void rejectsAnAggregateDifferentFromItsInputs(String chain) {
        assertFalse(sign(chain, "0.001", "0.002", "0.0005", recipient).path("valid").asBoolean());
    }

    @ParameterizedTest
    @ValueSource(strings = {"BTC", "LTC"})
    void rejectsMissingChangeDestination(String chain) {
        assertFalse(sign(chain, "0.001", "0.001", "0.0005", null).path("valid").asBoolean());
    }

    @ParameterizedTest
    @ValueSource(strings = {"BTC", "LTC"})
    void retainsSubDustChangeAsTheFee(String chain) {
        var result = sign(chain, "0.000505", "0.000505", "0.0005", null);
        assertTrue(result.path("valid").asBoolean(), result.path("error").asText());
        assertEquals(500L, result.path("fee").asLong());
        var tx = Transaction.read(ByteBuffer.wrap(HexFormat.of().parseHex(result.path("firstSignTx").asText())));
        assertEquals(1, tx.getOutputs().size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"BTC", "LTC"})
    void rejectsFeeArithmeticOverflow(String chain) {
        var result = sign(chain, "0.001", "0.001", "0.0005", recipient, Long.MAX_VALUE);
        assertFalse(result.path("valid").asBoolean());
        assertFalse(result.has("firstSignTx"));
    }

    private ObjectNode sign(String chain, String input, String total, String payment, String changeAddress) {
        return sign(chain, input, total, payment, changeAddress, 2L);
    }

    private ObjectNode sign(String chain, String input, String total, String payment,
                             String changeAddress, long feeRate) {
        var keys = WalletKeyMaterialProvider.forSig1(Base64.getEncoder().encodeToString(seed((byte) 0x11)),
                Bip32Node.getMasterKey(seed((byte) 0x22)).pubSerialize(0, false),
                Bip32Node.getMasterKey(seed((byte) 0x33)).pubSerialize(0, false));
        var publicKeys = new PubKeyConfig(keys);
        ISignService signer = "BTC".equals(chain) ? new BtcFirstSignService(publicKeys, keys, mapper, "test")
                : new LtcFirstSignService(publicKeys, keys, mapper);
        ObjectNode payload = mapper.createObjectNode();
        payload.set("utxos", mapper.valueToTree(List.of(UtxoTransaction.builder().txId("aa".repeat(32))
                .seq((short) 0).balance(new BigDecimal(input)).build())));
        payload.set("addresses", mapper.valueToTree(List.of(Address.builder().userId(1L).biz(1).index(0).build())));
        payload.set("withdraw", mapper.valueToTree(List.of(WithdrawRecord.builder().address(recipient)
                .balance(new BigDecimal(payment)).build())));
        payload.put("feeRate", feeRate);
        payload.put("changeAddress", changeAddress);
        var transaction = WithdrawTransaction.builder().chain(chain).assetSymbol(chain).assetDecimals(8)
                .bip44CoinType("BTC".equals(chain) ? 0 : 2).balance(new BigDecimal(total))
                .signature(JacksonJson.writeValue(mapper, payload)).build();
        signer.signTransaction(transaction);
        return JacksonJson.readObject(mapper, transaction.getSignature());
    }

    private static byte[] seed(byte fill) {
        var value = new byte[32];
        Arrays.fill(value, fill);
        return value;
    }
}
