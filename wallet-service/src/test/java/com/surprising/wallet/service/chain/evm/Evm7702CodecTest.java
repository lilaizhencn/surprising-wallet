package com.surprising.wallet.service.chain.evm;

import org.junit.jupiter.api.Test;
import org.web3j.crypto.AuthorizationTuple;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.Hash;
import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;
import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.security.SignatureException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Evm7702CodecTest {
    private static final Credentials AUTHORITY = Credentials.create(
            "0x59c6995e998f97a5a0044966f0945389dc9e86dae88c7a8412f4603b6b78690d");
    private static final Credentials RELAYER = Credentials.create(
            "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80");
    private static final String COLLECTOR = "0x1111111111111111111111111111111111111111";
    private static final String DELEGATE = "0x2222222222222222222222222222222222222222";
    private static final String TOKEN = "0x3333333333333333333333333333333333333333";
    private static final String RECIPIENT = "0x4444444444444444444444444444444444444444";

    @Test
    void shouldSignRecoverableEip712RequestAndEncodeBatch() throws SignatureException {
        byte[] batchId = Hash.sha3("tenant-a:batch-1".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Evm7702CollectionRequest request = new Evm7702CollectionRequest(
                batchId, BigInteger.ZERO, AUTHORITY.getAddress(), COLLECTOR, TOKEN, RECIPIENT,
                BigInteger.valueOf(25_000_000L), BigInteger.ZERO,
                BigInteger.valueOf(2_000_000_000L), BigInteger.valueOf(180_000L));
        Evm7702OperationSigner signer = new Evm7702OperationSigner();
        byte[] digest = signer.digest(BigInteger.valueOf(31337), request);
        byte[] signature = signer.sign(BigInteger.valueOf(31337), request, AUTHORITY);
        Sign.SignatureData signatureData = new Sign.SignatureData(
                signature[64], java.util.Arrays.copyOfRange(signature, 0, 32),
                java.util.Arrays.copyOfRange(signature, 32, 64));
        BigInteger recovered = Sign.signedMessageHashToKey(digest, signatureData);
        assertEquals(AUTHORITY.getAddress(), "0x" + Keys.getAddress(recovered));

        String calldata = new Evm7702ContractCodec().encodeCollectBatch(List.of(request), List.of(signature));
        String expectedSelector = Hash.sha3String("collectBatch((bytes32,uint256,address,address,address,address,uint256,uint256,uint256,uint256)[],bytes[])")
                .substring(0, 10);
        assertTrue(calldata.startsWith(expectedSelector));
    }

    @Test
    void shouldCreateChainBoundAuthorizationAndType4Transaction() throws Exception {
        Evm7702AuthorizationService authorizationService = new Evm7702AuthorizationService();
        AuthorizationTuple authorization = authorizationService.authorize(
                BigInteger.valueOf(31337), DELEGATE, BigInteger.ZERO, AUTHORITY);
        assertEquals(AUTHORITY.getAddress(), Sign.recoverAuthorizationSigner(authorization));
        assertEquals(BigInteger.valueOf(31337), authorization.getChainId());

        Evm7702BatchTransactionService.SignedType4Transaction signed =
                new Evm7702BatchTransactionService().sign(
                        31337L, BigInteger.ZERO, BigInteger.ONE, BigInteger.TWO,
                        BigInteger.valueOf(500_000L), COLLECTOR, "0x12345678",
                        List.of(authorization), RELAYER);
        byte[] raw = Numeric.hexStringToByteArray(signed.rawTransaction());
        assertEquals(0x04, raw[0] & 0xff);
        assertArrayEquals(Hash.sha3(raw), Numeric.hexStringToByteArray(signed.transactionHash()));
    }
}
