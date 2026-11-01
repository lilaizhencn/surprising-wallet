package com.surprising.wallet.chain.tron;

import com.google.protobuf.ByteString;
import com.surprising.wallet.sdk.signing.ThresholdSignatureVerifier;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.HexFormat;
import org.bitcoinj.crypto.ECKey;
import org.tron.TronWalletApi;
import org.tron.trident.core.ApiWrapper;
import org.tron.trident.proto.Chain;
import org.tron.trident.proto.Contract;
import org.web3j.crypto.ECDSASignature;
import org.web3j.crypto.Sign;
import org.web3j.utils.Numeric;

/** Canonical public-only TRX/TRC20 envelope and verified recoverable MPC signature assembly. */
public final class MpcTronTransactionCodec {
    private static final HexFormat HEX=HexFormat.of();
    private MpcTronTransactionCodec() { }

    public record Unsigned(String transactionHex, String owner, String recipient, BigInteger amountAtomic,
                           String contract, long feeLimitSun) {
        public Unsigned {
            if(transactionHex==null || transactionHex.length()>2*1024*1024 || transactionHex.isEmpty() || transactionHex.length()%2!=0 || !transactionHex.matches("[0-9a-f]+")
                    || !TronAddressCodec.isValidBase58(owner) || !TronAddressCodec.isValidBase58(recipient)
                    || amountAtomic==null || amountAtomic.signum()<=0 || amountAtomic.bitLength()>256 || feeLimitSun<0
                    || (contract!=null && !TronAddressCodec.isValidBase58(contract))) {
                throw new IllegalArgumentException("invalid immutable TRON signing envelope");
            }
        }
    }
    public record Signed(String transactionHex,String txId) { }

    public static Unsigned prepare(Chain.Transaction transaction,String owner,String recipient,BigInteger amount,
                                   String contract,long feeLimitSun) {
        var unsigned=new Unsigned(HEX.formatHex(transaction.toByteArray()),owner,recipient,amount,contract,feeLimitSun);
        restore(unsigned);
        return unsigned;
    }

    public static byte[] digest(Unsigned unsigned) { return ApiWrapper.calculateTransactionHash(restore(unsigned)); }

    public static Chain.Transaction restore(Unsigned unsigned) {
        try {
            var tx=Chain.Transaction.parseFrom(HEX.parseHex(unsigned.transactionHex()));
            var raw=tx.getRawData();
            require(HEX.formatHex(tx.toByteArray()).equals(unsigned.transactionHex()) && tx.getSignatureCount()==0
                            && tx.getRetCount()==0 && raw.getContractCount()==1 && raw.getRefBlockBytes().size()==2
                            && raw.getRefBlockHash().size()==8 && raw.getTimestamp()>0 && raw.getExpiration()>raw.getTimestamp()
                            && raw.getExpiration()-raw.getTimestamp()<=86_400_000L && raw.getFeeLimit()==unsigned.feeLimitSun(),
                    "TRON unsigned header differs: signatures="+tx.getSignatureCount()+", returns="+tx.getRetCount()
                            +", refs="+raw.getRefBlockBytes().size()+"/"+raw.getRefBlockHash().size()
                            +", timestamp="+raw.getTimestamp()+", expiry="+raw.getExpiration()+", fee="+raw.getFeeLimit()+"/"+unsigned.feeLimitSun());
            var action=raw.getContract(0);
            require(action.getPermissionId()==0,"TRON transfer must use its joint-key owner permission");
            if(unsigned.contract()==null) {
                require(action.getType()==Chain.Transaction.Contract.ContractType.TransferContract && unsigned.feeLimitSun()==0,
                        "TRX transfer contract type or fee limit differs");
                var transfer=action.getParameter().unpack(Contract.TransferContract.class);
                require(transfer.getOwnerAddress().equals(ApiWrapper.parseAddress(unsigned.owner()))
                                && transfer.getToAddress().equals(ApiWrapper.parseAddress(unsigned.recipient()))
                                && unsigned.amountAtomic().equals(BigInteger.valueOf(transfer.getAmount())),
                        "TRX owner, recipient or amount differs from accepted intent");
            } else {
                require(action.getType()==Chain.Transaction.Contract.ContractType.TriggerSmartContract && unsigned.feeLimitSun()>0,
                        "TRC20 contract type or fee limit differs");
                var transfer=action.getParameter().unpack(Contract.TriggerSmartContract.class);
                String expected="a9059cbb"+"0".repeat(24)+TronAddressCodec.base58ToHex(unsigned.recipient()).substring(2)
                        +Numeric.toHexStringNoPrefixZeroPadded(unsigned.amountAtomic(),64);
                require(transfer.getOwnerAddress().equals(ApiWrapper.parseAddress(unsigned.owner()))
                                && transfer.getContractAddress().equals(ApiWrapper.parseAddress(unsigned.contract()))
                                && transfer.getCallValue()==0 && transfer.getCallTokenValue()==0 && transfer.getTokenId()==0
                                && expected.equals(HEX.formatHex(transfer.getData().toByteArray())),
                        "TRC20 owner, contract, recipient, amount or native value differs from accepted intent");
            }
            return tx;
        } catch(com.google.protobuf.InvalidProtocolBufferException invalid) {
            throw new IllegalArgumentException("invalid unsigned TRON protobuf",invalid);
        }
    }

    public static Signed assemble(Unsigned unsigned,byte[] publicKey,byte[] derSignature) {
        var tx=restore(unsigned);
        byte[] digest=ApiWrapper.calculateTransactionHash(tx);
        require(unsigned.owner().equals(TronWalletApi.getAddress(publicKey))
                        && ThresholdSignatureVerifier.ecdsa(publicKey,digest,derSignature),"MPC signature or joint key differs from TRON intent");
        ECKey.ECDSASignature decoded;
        try { decoded=ECKey.ECDSASignature.decodeFromDER(derSignature).toCanonicalised(); }
        catch(org.bitcoinj.crypto.SignatureDecodeException invalid) { throw new IllegalArgumentException("invalid TRON MPC DER signature",invalid); }
        var signature=new ECDSASignature(decoded.r,decoded.s);
        var point=Sign.publicFromPoint(ECKey.fromPublicOnly(publicKey).decompress().getPubKey());
        byte[] recoverable=new byte[65];
        System.arraycopy(Numeric.toBytesPadded(decoded.r,32),0,recoverable,0,32);
        System.arraycopy(Numeric.toBytesPadded(decoded.s,32),0,recoverable,32,32);
        for(int recovery=0;recovery<4;recovery++) {
            if(!point.equals(Sign.recoverFromSignature(recovery,signature,digest))) continue;
            recoverable[64]=(byte)recovery;
            var signed=tx.toBuilder().addSignature(ByteString.copyFrom(recoverable)).build();
            require(Arrays.equals(digest,ApiWrapper.calculateTransactionHash(signed)),"TRON signature changed the fixed transaction hash");
            return new Signed(HEX.formatHex(signed.toByteArray()),HEX.formatHex(digest));
        }
        throw new IllegalArgumentException("TRON MPC public-key recovery failed");
    }
    private static void require(boolean condition,String message) { if(!condition) throw new IllegalArgumentException(message); }
}
