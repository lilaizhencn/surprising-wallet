package com.surprising.wallet.sdk.signing;

import com.surprising.wallet.sdk.bitcoinj.bitcoincash.BitcoinCashAddressCodec;
import com.surprising.wallet.sdk.bitcoinj.bitcoincash.BitcoinCashNetworkParameters;
import com.surprising.wallet.sdk.bitcoinj.core.BitcoinLikeNetwork;
import java.nio.ByteBuffer;
import java.util.HexFormat;
import java.util.List;
import org.bitcoinj.base.LegacyAddress;
import org.bitcoinj.base.SegwitAddress;
import org.bitcoinj.base.Sha256Hash;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.crypto.ECKey;
import org.bitcoinj.crypto.TransactionSignature;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptBuilder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class MpcUtxoTransactionCodecTest {
    private static final HexFormat HEX=HexFormat.of();
    private static final BitcoinLikeNetwork NETWORK=BitcoinLikeNetwork.REGTEST;

    @ParameterizedTest @ValueSource(strings={"BTC","LTC","DOGE","BCH"})
    void externallySignedJointKeyInputsUseTheActualChainWireFormatAndFeeBound(String chain) throws Exception {
        var first=new ECKey();var second=new ECKey();var recipient=new ECKey();
        var inputs=List.of(input("12",0,first),input("34",1,second));
        var payments=List.of(new MpcUtxoTransactionCodec.Payment(address(chain,recipient),1_000_000L),
                new MpcUtxoTransactionCodec.Payment(address(chain,second),2_000_000L));
        long rate=chain.equals("DOGE") ? 1000:2;
        long dust=chain.equals("DOGE") ? 100_000:546;
        var unsigned=MpcUtxoTransactionCodec.prepare(chain,NETWORK,inputs,payments,address(chain,first),rate,dust);
        var signatures=List.of(sign(first,unsigned,0),sign(second,unsigned,1));
        var signed=MpcUtxoTransactionCodec.assemble(unsigned,signatures);
        var transaction=Transaction.read(ByteBuffer.wrap(HEX.parseHex(signed.transactionHex())));
        assertEquals(signed.txId(),transaction.getTxId().toString());
        assertEquals(2,transaction.getInputs().size());assertEquals(3,transaction.getOutputs().size());
        assertEquals(1_000_000L,transaction.getOutput(0).getValue().value);
        assertEquals(2_000_000L,transaction.getOutput(1).getValue().value);
        assertEquals(10_000_000L-signed.feeAtomic()-3_000_000L,transaction.getOutput(2).getValue().value);
        assertEquals(signed.weight(),transaction.getWeight());assertEquals(signed.vBytes(),transaction.getVsize());
        assertTrue(signed.vBytes()<=unsigned.estimatedVBytes());assertTrue(signed.feeAtomic()>=rate*signed.vBytes());
        for(int i=0;i<2;i++) {
            var key=i==0 ? first:second;
            var actual=transaction.getInput(i);
            TransactionSignature signature;
            if(chain.equals("BTC") || chain.equals("LTC")) {
                assertTrue(actual.hasWitness());assertEquals(2,actual.getWitness().getPushCount());
                assertEquals(0,actual.getScriptSig().program().length);
                assertArrayEquals(key.getPubKey(),actual.getWitness().getPush(1));
                signature=TransactionSignature.decodeFromBitcoin(actual.getWitness().getPush(0),true,true);
                assertEquals(0xfffffffdL,actual.getSequenceNumber());
                actual.getScriptSig().correctlySpends(transaction,i,actual.getWitness(),org.bitcoinj.base.Coin.valueOf(5_000_000L),
                        ScriptBuilder.createP2WPKHOutputScript(key),Script.ALL_VERIFY_FLAGS);
            } else {
                assertFalse(actual.hasWitness());assertEquals(2,actual.getScriptSig().chunks().size());
                assertArrayEquals(key.getPubKey(),actual.getScriptSig().chunks().get(1).data);
                // Bitcoinj's Bitcoin-only hash-type policy excludes BCH FORKID (0x41).
                // DER/low-S and the exact BCH hash type are checked separately here.
                signature=TransactionSignature.decodeFromBitcoin(actual.getScriptSig().chunks().getFirst().data,!chain.equals("BCH"),true);
                if(chain.equals("DOGE")) actual.getScriptSig().correctlySpends(transaction,i,ScriptBuilder.createP2PKHOutputScript(key),Script.ALL_VERIFY_FLAGS);
            }
            assertEquals(chain.equals("BCH") ? 0x41:1,signature.sighashFlags);
            assertTrue(signature.isCanonical());
            assertTrue(key.verify(Sha256Hash.wrap(MpcUtxoTransactionCodec.digest(unsigned,i)),signature));
        }
        assertEquals(signed,MpcUtxoTransactionCodec.assemble(unsigned,signatures));
        assertThrows(IllegalArgumentException.class,()->MpcUtxoTransactionCodec.assemble(unsigned,List.of(signatures.get(1),signatures.get(0))));
        assertThrows(IllegalArgumentException.class,()->MpcUtxoTransactionCodec.assemble(unsigned,List.of(signatures.get(0))));
        var alteredTransaction=Transaction.read(ByteBuffer.wrap(HEX.parseHex(unsigned.transactionHex())));
        alteredTransaction.setLockTime(1);
        var altered=new MpcUtxoTransactionCodec.Unsigned(chain,NETWORK,HEX.formatHex(alteredTransaction.bitcoinSerialize()),
                unsigned.inputs(),unsigned.feeAtomic(),unsigned.estimatedVBytes());
        assertThrows(IllegalArgumentException.class,()->MpcUtxoTransactionCodec.assemble(altered,signatures));
        var alreadySigned=new MpcUtxoTransactionCodec.Unsigned(chain,NETWORK,signed.transactionHex(),inputs,signed.feeAtomic(),unsigned.estimatedVBytes());
        assertThrows(IllegalArgumentException.class,()->MpcUtxoTransactionCodec.digest(alreadySigned,0));
    }

    @ParameterizedTest @ValueSource(strings={"BTC","LTC","DOGE","BCH"})
    void validationPreventsDuplicateInputsMissingChangeAndFeeUnderfunding(String chain) {
        var key=new ECKey();var input=input("56",0,key);String address=address(chain,key);
        long dust=chain.equals("DOGE") ? 100_000:546;
        long rate=chain.equals("DOGE") ? 1000:2;
        var payment=new MpcUtxoTransactionCodec.Payment(address,1_000_000L);
        assertThrows(IllegalArgumentException.class,()->MpcUtxoTransactionCodec.prepare(chain,NETWORK,List.of(input,input),List.of(payment),address,rate,dust));
        assertThrows(IllegalArgumentException.class,()->MpcUtxoTransactionCodec.prepare(chain,NETWORK,List.of(input),List.of(payment),null,rate,dust));
        assertThrows(IllegalArgumentException.class,()->MpcUtxoTransactionCodec.prepare(chain,NETWORK,List.of(input),
                List.of(new MpcUtxoTransactionCodec.Payment(address,4_999_999L)),address,rate,dust));
        assertThrows(IllegalArgumentException.class,()->MpcUtxoTransactionCodec.prepare(chain,NETWORK,List.of(input),
                List.of(new MpcUtxoTransactionCodec.Payment(address,dust-1)),address,rate,dust));
        var valid=MpcUtxoTransactionCodec.prepare(chain,NETWORK,List.of(input),List.of(payment),address,rate,dust);
        var wrongValue=new MpcUtxoTransactionCodec.Unsigned(chain,NETWORK,valid.transactionHex(),
                List.of(new MpcUtxoTransactionCodec.Input(input.txId(),0,input.valueAtomic()+1,input.publicKeyHex())),valid.feeAtomic(),valid.estimatedVBytes());
        assertThrows(IllegalArgumentException.class,()->MpcUtxoTransactionCodec.digest(wrongValue,0));
        if(chain.equals("BCH")) {
            var legacy=LegacyAddress.fromKey(NETWORK.parameters(chain),key).toString();
            assertDoesNotThrow(()->MpcUtxoTransactionCodec.prepare(chain,NETWORK,List.of(input),
                    List.of(new MpcUtxoTransactionCodec.Payment(legacy,1_000_000L)),address,rate,dust));
        }
    }

    private static MpcUtxoTransactionCodec.Input input(String prefix,int index,ECKey key) {
        return new MpcUtxoTransactionCodec.Input(prefix.repeat(32),index,5_000_000L,HEX.formatHex(key.getPubKey()));
    }
    private static byte[] sign(ECKey key,MpcUtxoTransactionCodec.Unsigned unsigned,int input) {
        return key.sign(Sha256Hash.wrap(MpcUtxoTransactionCodec.digest(unsigned,input))).encodeToDER();
    }
    private static String address(String chain,ECKey key) {
        var params=NETWORK.parameters(chain);
        return switch(chain) {
            case "BTC","LTC" -> SegwitAddress.fromKey(params,key).toString();
            case "BCH" -> BitcoinCashAddressCodec.fromLegacy(LegacyAddress.fromKey(params,key),((BitcoinCashNetworkParameters)params).cashPrefix());
            default -> LegacyAddress.fromKey(params,key).toString();
        };
    }
}
