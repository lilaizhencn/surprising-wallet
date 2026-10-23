package com.surprising.wallet.chain.utxo;

import com.surprising.wallet.common.key.WalletKeyMaterialProvider;
import com.surprising.wallet.common.signing.*;
import com.surprising.wallet.sdk.bitcoinj.bip.Bip32Node;
import com.surprising.wallet.sdk.bitcoinj.core.BitcoinLikeNetwork;
import com.surprising.wallet.sdk.bitcoinj.dogecoin.DogecoinFeePolicy;
import com.surprising.wallet.sig.first.config.PubKeyConfig;
import com.surprising.wallet.sig.first.service.*;
import com.surprising.wallet.sig.second.Sig2KeyDerivation;
import com.surprising.wallet.sig.second.impl.*;
import org.bitcoinj.base.LegacyAddress;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.crypto.ECKey;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class UtxoSigningCryptoTest {
    private final ObjectMapper json = new ObjectMapper();
    private final WalletKeyMaterialProvider firstKeys = WalletKeyMaterialProvider.forSig1(seed(0x11),
            Bip32Node.getMasterKey(bytes(0x22)).pubSerialize(0, false),
            Bip32Node.getMasterKey(bytes(0x33)).pubSerialize(0, false));
    private final PubKeyConfig publicKeys = new PubKeyConfig(firstKeys);
    private final Sig2KeyDerivation secondKeys = new Sig2KeyDerivation(WalletKeyMaterialProvider.forSig2(seed(0x22)));

    @ParameterizedTest
    @CsvSource({"BTC,MAINNET", "BTC,TESTNET", "BTC,REGTEST", "LTC,MAINNET", "LTC,TESTNET", "LTC,REGTEST",
            "BCH,MAINNET", "BCH,TESTNET", "BCH,REGTEST", "DOGE,MAINNET", "DOGE,TESTNET", "DOGE,REGTEST"})
    void twoIndependentSignaturesConserveInputsOnTheRequestedNetwork(String chain, BitcoinLikeNetwork network) {
        var request = request(chain, network, chain.equals("DOGE") ? "10" : "0.001", chain.equals("DOGE") ? "5" : "0.0005");
        var partial = first(chain).sign(request);
        var wire = json.readValue(json.writeValueAsString(new UtxoPartialSigningTask(new UtxoSigningTask(1, request), partial)),
                UtxoPartialSigningTask.class);
        assertEquals(request, wire.task().request());
        var tx = Transaction.read(ByteBuffer.wrap(HexFormat.of().parseHex(second(chain).sign(wire.task().request(), wire.signature()))));
        long output = tx.getOutputs().stream().mapToLong(value -> value.getValue().getValue()).sum();
        assertEquals(request.inputAtomic(), Math.addExact(output, partial.feeAtomic()));
        assertEquals(request.asset().atomic(request.payments().getFirst().amount()), tx.getOutput(0).getValue().getValue());
        if (partial.scriptType() == UtxoPartialSignature.ScriptType.P2WSH) {
            var witness = tx.getInput(0).getWitness();
            assertEquals(4, witness.getPushCount());
            assertFalse(Arrays.equals(witness.getPush(1), witness.getPush(2)));
        } else {
            var chunks = tx.getInput(0).getScriptSig().chunks();
            assertEquals(4, chunks.size());
            assertFalse(Arrays.equals(chunks.get(1).data, chunks.get(2).data));
        }
        assertFalse(json.valueToTree(request).has("balance"));
        assertFalse(json.valueToTree(request).has("signature"));
    }

    @ParameterizedTest @ValueSource(strings = {"BTC", "LTC"})
    void rejectsFractionalInputUnits(String chain) {
        var request = request(chain, BitcoinLikeNetwork.TESTNET, "0.001000009", "0.0005");
        assertThrows(ArithmeticException.class, () -> first(chain).sign(request));
    }

    @ParameterizedTest @ValueSource(strings = {"BTC", "LTC"})
    void rejectsFractionalOutputUnits(String chain) {
        var request = request(chain, BitcoinLikeNetwork.TESTNET, "0.001", "0.000500009");
        assertThrows(ArithmeticException.class, () -> first(chain).sign(request));
    }

    @ParameterizedTest @ValueSource(strings = {"BTC", "LTC"})
    void rejectsMissingChangeDestination(String chain) {
        var request = request(chain, BitcoinLikeNetwork.TESTNET, "0.001", "0.0005");
        var missing = new UtxoSigningRequest(request.tenantId(), request.requestId(), request.asset(), request.operation(),
                null, request.inputs(), request.payments(), null, request.feeRate(), request.dustThreshold());
        assertThrows(IllegalArgumentException.class, () -> first(chain).sign(missing));
    }

    @ParameterizedTest @ValueSource(strings = {"BTC", "LTC"})
    void retainsSubDustChangeAsFee(String chain) {
        var request = request(chain, BitcoinLikeNetwork.TESTNET, "0.000505", "0.0005");
        var partial = first(chain).sign(request);
        assertEquals(500, partial.feeAtomic());
        var tx = Transaction.read(ByteBuffer.wrap(HexFormat.of().parseHex(partial.transactionHex())));
        assertEquals(1, tx.getOutputs().size());
    }

    @ParameterizedTest @ValueSource(strings = {"BTC", "LTC"})
    void rejectsFeeArithmeticOverflow(String chain) {
        var request = request(chain, BitcoinLikeNetwork.TESTNET, "0.001", "0.0005");
        var overflow = request.replacement(UUID.randomUUID(), Long.MAX_VALUE, request.payments());
        assertThrows(ArithmeticException.class, () -> first(chain).sign(overflow));
    }

    private UtxoSigningRequest request(String chain, BitcoinLikeNetwork network, String input, String payment) {
        int coinType = switch (chain) { case "BTC" -> 0; case "LTC" -> 2; case "DOGE" -> 3; default -> 145; };
        var asset = new UtxoSigningRequest.Asset(chain, network, 1, 8, coinType);
        String destination = LegacyAddress.fromKey(network.parameters(chain), ECKey.fromPrivate(BigInteger.valueOf(5))).toBase58();
        return new UtxoSigningRequest(UUID.randomUUID(), UUID.randomUUID(), asset, UtxoSigningRequest.Operation.WITHDRAWAL,
                null, List.of(new UtxoSigningRequest.Input("aa".repeat(32), 0, "source", new BigDecimal(input),
                        new UtxoSigningRequest.Key(1, 1, 0, null))),
                List.of(new UtxoSigningRequest.Payment("WD-1", 1, destination, new BigDecimal(payment), BigDecimal.ZERO)),
                destination, chain.equals("DOGE") ? DogecoinFeePolicy.DEFAULT_FEE_RATE_KOINU_PER_BYTE : 2, 546);
    }

    private ISignService first(String chain) {
        return switch (chain) {
            case "BTC" -> new BtcFirstSignService(publicKeys, firstKeys);
            case "LTC" -> new LtcFirstSignService(publicKeys, firstKeys);
            case "BCH" -> new BchFirstSignService(publicKeys, firstKeys);
            case "DOGE" -> new DogeFirstSignService(publicKeys, firstKeys);
            default -> throw new IllegalArgumentException(chain);
        };
    }
    private com.surprising.wallet.sig.second.ISignService second(String chain) {
        return switch (chain) {
            case "BTC" -> new BtcSecondSignService(secondKeys);
            case "LTC" -> new LtcSecondSignService(secondKeys);
            case "BCH" -> new BchSecondSignService(secondKeys);
            case "DOGE" -> new DogeSecondSignService(secondKeys);
            default -> throw new IllegalArgumentException(chain);
        };
    }
    private static byte[] bytes(int fill) { var bytes = new byte[32]; Arrays.fill(bytes, (byte) fill); return bytes; }
    private static String seed(int fill) { return Base64.getEncoder().encodeToString(bytes(fill)); }
}
