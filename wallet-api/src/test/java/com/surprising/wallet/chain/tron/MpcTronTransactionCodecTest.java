package com.surprising.wallet.chain.tron;

import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.HexFormat;
import org.tron.trident.crypto.tuwenitypes.Bytes;
import org.tron.trident.crypto.tuwenitypes.Bytes32;
import org.bitcoinj.base.Sha256Hash;
import org.bitcoinj.crypto.ECKey;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.tron.TronWalletApi;
import org.tron.trident.core.ApiWrapper;
import org.tron.trident.crypto.SECP256K1;
import org.tron.trident.proto.Chain;
import org.tron.trident.proto.Contract;
import org.web3j.utils.Numeric;

import static org.junit.jupiter.api.Assertions.*;

class MpcTronTransactionCodecTest {
    private static final HexFormat HEX=HexFormat.of();
    private final ECKey key=new ECKey();
    private final String owner=TronWalletApi.getAddress(key.getPubKey());
    private final String recipient=TronWalletApi.getAddress(new ECKey().getPubKey());
    private final String contract=TronWalletApi.getAddress(new ECKey().getPubKey());
    private final BigInteger amount=BigInteger.valueOf(1_234_567);

    @ParameterizedTest @ValueSource(booleans={false,true})
    void externalDerBecomesSdkVerifiedRecoverableSignatureWithoutChangingRawOrHash(boolean token) throws Exception {
        var unsigned=unsigned(token);
        byte[] digest=MpcTronTransactionCodec.digest(unsigned);
        assertArrayEquals(Sha256Hash.hash(MpcTronTransactionCodec.restore(unsigned).getRawData().toByteArray()),digest);
        byte[] der=key.sign(Sha256Hash.wrap(digest)).encodeToDER();
        var signed=MpcTronTransactionCodec.assemble(unsigned,key.getPubKey(),der);
        var transaction=Chain.Transaction.parseFrom(HEX.parseHex(signed.transactionHex()));
        assertEquals(1,transaction.getSignatureCount());
        assertEquals(HEX.formatHex(digest),signed.txId());
        assertArrayEquals(MpcTronTransactionCodec.restore(unsigned).getRawData().toByteArray(),transaction.getRawData().toByteArray());
        var signature=SECP256K1.Signature.decode(Bytes.wrap(transaction.getSignature(0).toByteArray()));
        var publicKey=SECP256K1.PublicKey.create(Bytes.wrap(Arrays.copyOfRange(key.decompress().getPubKey(),1,65)));
        assertTrue(SECP256K1.verify(Bytes32.wrap(digest),signature,publicKey));
        assertEquals(publicKey,SECP256K1.PublicKey.recoverFromSignature(Bytes32.wrap(digest),signature).orElseThrow());
        assertTrue(signature.getS().compareTo(SECP256K1.HALF_CURVE_ORDER)<=0);
        assertThrows(IllegalArgumentException.class,()->MpcTronTransactionCodec.prepare(transaction,owner,recipient,amount,
                token ? contract:null,token ? 30_000_000:0));
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void alteredPaymentOwnerFeeOrSignatureCannotBecomeTheAcceptedTransaction(boolean token) {
        var unsigned=unsigned(token);
        var raw=MpcTronTransactionCodec.restore(unsigned);
        long fee=unsigned.feeLimitSun();String assetContract=unsigned.contract();
        assertThrows(IllegalArgumentException.class,()->MpcTronTransactionCodec.prepare(raw,owner,recipient,amount.add(BigInteger.ONE),assetContract,fee));
        assertThrows(IllegalArgumentException.class,()->MpcTronTransactionCodec.prepare(raw,recipient,recipient,amount,assetContract,fee));
        assertThrows(IllegalArgumentException.class,()->MpcTronTransactionCodec.prepare(raw,owner,owner,amount,assetContract,fee));
        assertThrows(IllegalArgumentException.class,()->MpcTronTransactionCodec.prepare(raw,owner,recipient,amount,assetContract,fee+1));
        var missingReference=raw.toBuilder().setRawData(raw.getRawData().toBuilder().clearRefBlockHash()).build();
        assertThrows(IllegalArgumentException.class,()->MpcTronTransactionCodec.prepare(missingReference,owner,recipient,amount,assetContract,fee));
        var expired=raw.toBuilder().setRawData(raw.getRawData().toBuilder().setExpiration(1)).build();
        assertThrows(IllegalArgumentException.class,()->MpcTronTransactionCodec.prepare(expired,owner,recipient,amount,assetContract,fee));
        var wrong=new ECKey().sign(Sha256Hash.wrap(MpcTronTransactionCodec.digest(unsigned))).encodeToDER();
        assertThrows(IllegalArgumentException.class,()->MpcTronTransactionCodec.assemble(unsigned,key.getPubKey(),wrong));
        var own=key.sign(Sha256Hash.wrap(MpcTronTransactionCodec.digest(unsigned))).encodeToDER();
        assertThrows(IllegalArgumentException.class,()->MpcTronTransactionCodec.assemble(unsigned,new ECKey().getPubKey(),own));
    }

    private MpcTronTransactionCodec.Unsigned unsigned(boolean token) {
        var action=Chain.Transaction.Contract.newBuilder();
        if(token) {
            String data="a9059cbb"+"0".repeat(24)+TronAddressCodec.base58ToHex(recipient).substring(2)+Numeric.toHexStringNoPrefixZeroPadded(amount,64);
            action.setType(Chain.Transaction.Contract.ContractType.TriggerSmartContract).setParameter(Any.pack(
                    Contract.TriggerSmartContract.newBuilder().setOwnerAddress(ApiWrapper.parseAddress(owner))
                            .setContractAddress(ApiWrapper.parseAddress(contract)).setData(ByteString.copyFrom(HEX.parseHex(data))).build()));
        } else action.setType(Chain.Transaction.Contract.ContractType.TransferContract).setParameter(Any.pack(
                Contract.TransferContract.newBuilder().setOwnerAddress(ApiWrapper.parseAddress(owner))
                        .setToAddress(ApiWrapper.parseAddress(recipient)).setAmount(amount.longValueExact()).build()));
        var tx=Chain.Transaction.newBuilder().setRawData(Chain.Transaction.raw.newBuilder().setTimestamp(1_000)
                .setExpiration(61_000).setRefBlockBytes(ByteString.copyFrom(new byte[2])).setRefBlockHash(ByteString.copyFrom(new byte[8]))
                .setFeeLimit(token ? 30_000_000:0).addContract(action)).build();
        return MpcTronTransactionCodec.prepare(tx,owner,recipient,amount,token ? contract:null,token ? 30_000_000:0);
    }
}
