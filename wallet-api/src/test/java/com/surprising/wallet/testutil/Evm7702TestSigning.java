package com.surprising.wallet.testutil;

import com.surprising.wallet.sdk.signing.evm7702.MpcEvm7702Codec;

import com.surprising.wallet.chain.evm.*;
import java.math.BigInteger;
import java.util.List;
import org.bitcoinj.crypto.ECKey;
import org.web3j.crypto.*;
import org.web3j.utils.Numeric;

/** Local keys belong only to isolated codec/RPC fixtures; production paths use remote MPC. */
public final class Evm7702TestSigning {
    public static byte[] signDigest(byte[] digest, Credentials fixture) {
        var signature = Sign.signMessage(digest,fixture.getEcKeyPair(),false);
        byte[] encoded = new byte[65];
        System.arraycopy(signature.getR(),0,encoded,0,32);
        System.arraycopy(signature.getS(),0,encoded,32,32);
        encoded[64]=signature.getV()[0];
        return encoded;
    }
    public static AuthorizationTuple authorize(BigInteger chainId,String delegate,BigInteger nonce,Credentials fixture) {
        if (fixture==null) throw new IllegalArgumentException("test authority is required");
        var accepted = new MpcEvm7702Codec.Authorization(chainId,fixture.getAddress(),delegate,nonce);
        var signature = Sign.signMessage(MpcEvm7702Codec.digest(accepted),fixture.getEcKeyPair(),false);
        byte[] der = new ECKey.ECDSASignature(new BigInteger(1,signature.getR()),new BigInteger(1,signature.getS())).encodeToDER();
        byte[] publicKey = ECKey.fromPrivate(fixture.getEcKeyPair().getPrivateKey()).getPubKey();
        return MpcEvm7702Codec.authorization(accepted,publicKey,der);
    }
    public static Evm7702SignedBatchTransaction signBatch(long chainId,BigInteger nonce,BigInteger priority,
            BigInteger maxFee,BigInteger gas,String destination,String data,List<AuthorizationTuple> authorizations,Credentials fixture) {
        if (fixture==null) throw new IllegalArgumentException("test relayer is required");
        var raw = MpcEvm7702Codec.batch(chainId,nonce,priority,maxFee,gas,destination,data,authorizations);
        byte[] wire = TransactionEncoder.signMessage(raw,fixture);
        return new Evm7702SignedBatchTransaction(Numeric.toHexString(wire),Numeric.toHexString(Hash.sha3(wire)),nonce,wire[0]&0xff);
    }
    public static Evm7702SignedBatchTransaction sign(long chainId,BigInteger nonce,BigInteger priority,
            BigInteger maxFee,BigInteger gas,String destination,String data,List<AuthorizationTuple> authorizations,Credentials fixture) {
        if (authorizations==null || authorizations.isEmpty()) throw new IllegalArgumentException("type4 test requires authorization");
        return signBatch(chainId,nonce,priority,maxFee,gas,destination,data,authorizations,fixture);
    }
}
