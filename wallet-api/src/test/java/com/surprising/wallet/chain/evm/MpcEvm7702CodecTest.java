package com.surprising.wallet.chain.evm;

import com.surprising.wallet.sdk.signing.evm7702.MpcEvm7702Codec;
import com.surprising.wallet.sdk.signing.evm7702.Evm7702PayoutRequest;
import com.surprising.wallet.sdk.signing.evm7702.Evm7702PayoutItem;
import com.surprising.wallet.sdk.signing.evm7702.Evm7702PayoutSigner;
import com.surprising.wallet.sdk.signing.evm7702.Evm7702CollectionRequest;
import com.surprising.wallet.sdk.signing.evm7702.Evm7702OperationSigner;

import com.surprising.wallet.sdk.signing.MpcEvmTransactionCodec;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import org.bitcoinj.base.Sha256Hash;
import org.bitcoinj.crypto.ECKey;
import org.junit.jupiter.api.Test;
import org.web3j.crypto.AuthorizationTuple;
import org.web3j.crypto.Hash;
import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;
import org.web3j.crypto.SignedRawTransaction;
import org.web3j.crypto.TransactionDecoder;
import org.web3j.crypto.transaction.type.Transaction7702;
import org.web3j.utils.Numeric;

import static org.junit.jupiter.api.Assertions.*;

/** Local ECDSA is an explicit codec fixture; actual MPC/chain acceptance is separate. */
class MpcEvm7702CodecTest {
    private static final BigInteger CHAIN = BigInteger.valueOf(31337);
    private static final String DELEGATE = "0x2222222222222222222222222222222222222222";
    private static final String COLLECTOR = "0x3333333333333333333333333333333333333333";
    private static final String RECIPIENT = "0x4444444444444444444444444444444444444444";
    private static final String NATIVE = "0x" + "00".repeat(20);
    private static final BigInteger MAX_NONCE = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);
    private static final BigInteger ORDER = org.bouncycastle.crypto.ec.CustomNamedCurves.getByName("secp256k1").getN();

    @Test void authorizationHashMatchesIndependentEthersRlpVectors() {
        var nonces = List.of(BigInteger.ZERO, BigInteger.ONE, MAX_NONCE.subtract(BigInteger.ONE));
        var hashes = List.of("4c8e6ac850ccef8c8cbda52244f4e96a6ebdfd54a95e38f3f7246967e43ef6ed",
                "07eedff6489ea5716359f3cbed5ca57078012d973203ed76d5bc06f3932fbb4c",
                "2f1fe06a291069314af360ad918b323afc91a3d9468f95d898cf7077595d216f");
        for (int index = 0; index < nonces.size(); index++) {
            var accepted = new MpcEvm7702Codec.Authorization(CHAIN, RECIPIENT, DELEGATE, nonces.get(index));
            assertEquals(hashes.get(index), Numeric.toHexStringNoPrefix(MpcEvm7702Codec.digest(accepted)));
        }
    }

    @Test void externalAuthorizationRecoversJointOwnerAndCanonicalizesHighS() throws Exception {
        var key = new ECKey();
        var accepted = authorization(key, BigInteger.ZERO);
        var signed = key.sign(Sha256Hash.wrap(MpcEvm7702Codec.digest(accepted)));
        var canonical = MpcEvm7702Codec.authorization(accepted, key.getPubKey(), signed.encodeToDER());
        var high = new ECKey.ECDSASignature(signed.r, ORDER.subtract(signed.s)).encodeToDER();
        assertEquals(canonical, MpcEvm7702Codec.authorization(accepted, key.getPubKey(), high));
        assertEquals(owner(key), Sign.recoverAuthorizationSigner(canonical));
        assertTrue(canonical.getS().compareTo(ORDER.shiftRight(1)) <= 0);
        assertTrue(canonical.getYParity().equals(BigInteger.ZERO) || canonical.getYParity().equals(BigInteger.ONE));
    }

    @Test void authorizationRejectsDifferentOwnerNonceChainAndDelegate() {
        var key = new ECKey();
        var accepted = authorization(key, BigInteger.ZERO);
        var signature = der(key, MpcEvm7702Codec.digest(accepted));
        for (var changed : List.of(new MpcEvm7702Codec.Authorization(CHAIN, RECIPIENT, DELEGATE, BigInteger.ZERO),
                new MpcEvm7702Codec.Authorization(CHAIN.add(BigInteger.ONE), owner(key), DELEGATE, BigInteger.ZERO),
                new MpcEvm7702Codec.Authorization(CHAIN, owner(key), COLLECTOR, BigInteger.ZERO),
                authorization(key, BigInteger.ONE))) {
            assertThrows(IllegalArgumentException.class, () -> MpcEvm7702Codec.authorization(changed, key.getPubKey(), signature));
        }
        assertThrows(IllegalArgumentException.class, () -> MpcEvm7702Codec.authorization(accepted, new ECKey().getPubKey(), signature));
    }

    @Test void paymentAuthorizationRequiresSpecificChainDelegateAndProcessableUint64Nonce() {
        for (var nonce : List.of(BigInteger.valueOf(-1), MAX_NONCE, MAX_NONCE.add(BigInteger.ONE))) {
            assertThrows(IllegalArgumentException.class, () -> new MpcEvm7702Codec.Authorization(CHAIN, RECIPIENT, DELEGATE, nonce));
        }
        for (var chain : List.of(BigInteger.ZERO, BigInteger.valueOf(-1), BigInteger.ONE.shiftLeft(256))) {
            assertThrows(IllegalArgumentException.class, () -> new MpcEvm7702Codec.Authorization(chain, RECIPIENT, DELEGATE, BigInteger.ZERO));
        }
        assertThrows(IllegalArgumentException.class, () -> new MpcEvm7702Codec.Authorization(CHAIN, RECIPIENT, NATIVE, BigInteger.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new MpcEvm7702Codec.Authorization(CHAIN, NATIVE, DELEGATE, BigInteger.ZERO));
    }

    @Test void operationSignaturesBindWholeCollectionAndPayoutRequests() throws Exception {
        var key = new ECKey();
        var collection = collection(owner(key), RECIPIENT, BigInteger.ONE);
        byte[] collectionDigest = new Evm7702OperationSigner().digest(CHAIN, collection);
        var collectionDer = der(key, collectionDigest);
        var collectionWire = MpcEvm7702Codec.collectionSignature(CHAIN, collection, key.getPubKey(), collectionDer);
        assertEquals(owner(key), recover(collectionDigest, collectionWire));
        assertThrows(IllegalArgumentException.class, () -> MpcEvm7702Codec.collectionSignature(CHAIN,
                collection(owner(key), COLLECTOR, BigInteger.ONE), key.getPubKey(), collectionDer));
        assertThrows(IllegalArgumentException.class, () -> MpcEvm7702Codec.collectionSignature(CHAIN,
                collection(owner(key), RECIPIENT, BigInteger.TWO), key.getPubKey(), collectionDer));
        assertThrows(IllegalArgumentException.class, () -> MpcEvm7702Codec.collectionSignature(CHAIN.add(BigInteger.ONE),
                collection, key.getPubKey(), collectionDer));
        var payout = payout(owner(key), RECIPIENT, BigInteger.ONE);
        byte[] payoutDigest = new Evm7702PayoutSigner().digest(CHAIN, payout);
        var payoutDer = der(key, payoutDigest);
        assertEquals(owner(key), recover(payoutDigest, MpcEvm7702Codec.payoutSignature(CHAIN, payout, key.getPubKey(), payoutDer)));
        assertThrows(IllegalArgumentException.class, () -> MpcEvm7702Codec.payoutSignature(CHAIN,
                payout(owner(key), RECIPIENT, BigInteger.TWO), key.getPubKey(), payoutDer));
        assertThrows(IllegalArgumentException.class, () -> MpcEvm7702Codec.payoutSignature(CHAIN, payout, key.getPubKey(), collectionDer));
        assertThrows(IllegalArgumentException.class, () -> MpcEvm7702Codec.payoutSignature(CHAIN, payout, new ECKey().getPubKey(), payoutDer));
    }

    @Test void outerTypeFourAndTypeTwoPreserveAcceptedInnerSignaturesAndRelayer() throws Exception {
        var authority = new ECKey();
        var relayer = new ECKey();
        var accepted = authorization(authority, BigInteger.ZERO);
        var tuple = MpcEvm7702Codec.authorization(accepted, authority.getPubKey(), der(authority, MpcEvm7702Codec.digest(accepted)));
        for (var tuples : List.of(List.of(tuple), List.<AuthorizationTuple>of())) {
            var raw = MpcEvm7702Codec.batch(CHAIN.longValueExact(), BigInteger.TWO, BigInteger.ONE, BigInteger.TWO,
                    BigInteger.valueOf(500_000), COLLECTOR, "0x12345678", tuples);
            byte[] payload = MpcEvmTransactionCodec.unsignedPayload(raw, CHAIN.longValueExact());
            byte[] signed = MpcEvmTransactionCodec.assemble(payload, CHAIN.longValueExact(), relayer.getPubKey(),
                    der(relayer, Hash.sha3(payload)));
            assertEquals(tuples.isEmpty() ? 2 : 4, signed[0]);
            var decoded = (SignedRawTransaction) TransactionDecoder.decode(Numeric.toHexString(signed));
            assertEquals(owner(relayer), decoded.getFrom());
            if (!tuples.isEmpty()) assertEquals(tuples, ((Transaction7702)decoded.getTransaction()).getAuthorizationList());
            assertThrows(IllegalArgumentException.class, () -> MpcEvmTransactionCodec.restore(signed, CHAIN.longValueExact()));
        }
    }

    @Test void batchRejectsDuplicateForeignOrMalformedAuthorizationsAndInvalidEnvelope() {
        var key = new ECKey();
        var accepted = authorization(key, BigInteger.ZERO);
        var tuple = MpcEvm7702Codec.authorization(accepted, key.getPubKey(), der(key, MpcEvm7702Codec.digest(accepted)));
        assertThrows(IllegalArgumentException.class, () -> batch(List.of(tuple, tuple)));
        var foreign = new AuthorizationTuple(CHAIN.add(BigInteger.ONE), tuple.getAddress(), tuple.getNonce(),
                tuple.getYParity(), tuple.getR(), tuple.getS());
        assertThrows(IllegalArgumentException.class, () -> batch(List.of(foreign)));
        var high = new AuthorizationTuple(CHAIN, tuple.getAddress(), tuple.getNonce(), tuple.getYParity(), tuple.getR(), ORDER.subtract(tuple.getS()));
        assertThrows(IllegalArgumentException.class, () -> batch(List.of(high)));
        assertThrows(IllegalArgumentException.class, () -> MpcEvm7702Codec.batch(31337, MAX_NONCE, BigInteger.ONE,
                BigInteger.TWO, BigInteger.valueOf(500_000), COLLECTOR, "0x1234", List.of()));
        assertThrows(IllegalArgumentException.class, () -> MpcEvm7702Codec.batch(31337, BigInteger.ZERO, BigInteger.TWO,
                BigInteger.ONE, BigInteger.valueOf(500_000), COLLECTOR, "0x1234", List.of()));
        assertThrows(IllegalArgumentException.class, () -> MpcEvm7702Codec.batch(31337, BigInteger.ZERO, BigInteger.ONE,
                BigInteger.TWO, BigInteger.valueOf(500_000), COLLECTOR, "0x123", List.of()));
    }

    private static org.web3j.crypto.RawTransaction batch(List<AuthorizationTuple> tuples) {
        return MpcEvm7702Codec.batch(31337, BigInteger.ZERO, BigInteger.ONE, BigInteger.TWO,
                BigInteger.valueOf(500_000), COLLECTOR, "0x1234", tuples);
    }
    private static MpcEvm7702Codec.Authorization authorization(ECKey key, BigInteger nonce) {
        return new MpcEvm7702Codec.Authorization(CHAIN, owner(key), DELEGATE, nonce);
    }
    private static Evm7702CollectionRequest collection(String authority, String recipient, BigInteger amount) {
        return new Evm7702CollectionRequest(Hash.sha3(new byte[]{1}), BigInteger.ZERO, authority, COLLECTOR,
                NATIVE, recipient, amount, BigInteger.ZERO, BigInteger.valueOf(2_000_000_000), BigInteger.valueOf(180_000));
    }
    private static Evm7702PayoutRequest payout(String authority, String recipient, BigInteger amount) {
        return new Evm7702PayoutRequest(Hash.sha3(new byte[]{2}), authority, COLLECTOR, List.of(
                new Evm7702PayoutItem(Hash.sha3(new byte[]{3}), BigInteger.ZERO, NATIVE, recipient, amount,
                        BigInteger.valueOf(120_000))), BigInteger.ZERO, BigInteger.valueOf(2_000_000_000));
    }
    private static String owner(ECKey key) { return "0x" + Keys.getAddress(Sign.publicFromPoint(key.decompress().getPubKey())); }
    private static byte[] der(ECKey key, byte[] digest) { return key.sign(Sha256Hash.wrap(digest)).encodeToDER(); }
    private static String recover(byte[] digest, byte[] wire) throws Exception {
        return "0x" + Keys.getAddress(Sign.signedMessageHashToKey(digest, new Sign.SignatureData(wire[64],
                Arrays.copyOfRange(wire, 0, 32), Arrays.copyOfRange(wire, 32, 64))));
    }
}
