package com.surprising.wallet.chain.hypercore;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.bitcoinj.crypto.ECKey;
import org.junit.jupiter.api.Test;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;
import org.web3j.utils.Numeric;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HyperCoreSignerTest {
    @Test
    void usdSendSignatureRecoversSignerAddress() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        HyperCoreSigner signer = new HyperCoreSigner(mapper);
        BigInteger privateKey = new BigInteger(
                "1f1e1d1c1b1a191817161514131211100f0e0d0c0b0a09080706050403020100", 16);
        ECKey bitcoinKey = ECKey.fromPrivate(privateKey);
        ObjectNode action = mapper.createObjectNode();
        action.put("destination", "0x0000000000000000000000000000000000000001");
        action.put("amount", "1.25");
        action.put("time", 1710000000000L);
        action.put("type", "usdSend");

        byte[] hash = signer.hashUsdSend(action.deepCopy(), false);
        ObjectNode signature = signer.signUsdSend(action, bitcoinKey, false);
        Sign.SignatureData signatureData = new Sign.SignatureData(
                (byte) signature.path("v").asInt(),
                Numeric.hexStringToByteArray(signature.path("r").asText()),
                Numeric.hexStringToByteArray(signature.path("s").asText()));

        BigInteger recovered = Sign.signedMessageHashToKey(hash, signatureData);
        String recoveredAddress = "0x" + Keys.getAddress(recovered);
        String expectedAddress = "0x" + Keys.getAddress(ECKeyPair.create(privateKey));

        assertEquals(expectedAddress, recoveredAddress);
        assertEquals(HyperCoreSigner.SIGNATURE_CHAIN_ID, action.path("signatureChainId").asText());
        assertEquals("Testnet", action.path("hyperliquidChain").asText());
    }
}
