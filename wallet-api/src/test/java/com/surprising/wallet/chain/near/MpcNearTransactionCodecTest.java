package com.surprising.wallet.chain.near;

import com.surprising.wallet.sdk.signing.near.MpcNearTransactionCodec;

import java.math.BigInteger;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.*;
import static org.junit.jupiter.api.Assertions.*;

class MpcNearTransactionCodecTest {
    private final ObjectMapper json=new ObjectMapper();
    private JsonNode vectors()throws Exception {try(var input=getClass().getResourceAsStream("/mpc/near-codec-reference.json")){return json.readTree(input).path("vectors");}}
    private MpcNearTransactionCodec.Unsigned unsigned(JsonNode vector){return json.treeToValue(vector.path("unsigned"),MpcNearTransactionCodec.Unsigned.class);}
    @Test void independentSdkVectorsMatchBorshHashAndEveryExternalWitness()throws Exception {
        assertEquals(5,vectors().size());
        for(var v:vectors()) {
            var u=unsigned(v);assertEquals(v.path("txBytesBase64").asText(),Base64.getEncoder().encodeToString(MpcNearTransactionCodec.transactionBytes(u)));
            assertEquals(v.path("signingHashHex").asText(),HexFormat.of().formatHex(MpcNearTransactionCodec.signingBytes(u)));
            var selected=MpcNearTransactionCodec.assemble(u,HexFormat.of().parseHex(v.path("signatureHex").asText()));
            assertEquals(v.path("txHash").asText(),selected.txHash());assertEquals(v.path("signedTransactionBase64").asText(),selected.signedTransactionBase64());MpcNearTransactionCodec.verifySelected(u,selected);
        }
    }
    @Test void changedPrincipalRecipientOrNonceCannotReuseTheSameWitness()throws Exception {
        var v=vectors().get(0);var u=unsigned(v);byte[] witness=HexFormat.of().parseHex(v.path("signatureHex").asText());
        for(var changed:List.of(copy(u,u.nonce()+1,u.recipient(),u.amountAtomic(),null,0,null),copy(u,u.nonce(),"other.near",u.amountAtomic(),null,0,null),copy(u,u.nonce(),u.recipient(),u.amountAtomic().add(BigInteger.ONE),null,0,null)))
            assertThrows(RuntimeException.class,()->MpcNearTransactionCodec.assemble(changed,witness));
    }
    @Test void contractGasAndExactTransferArgumentsRemainInTheSignedMessage()throws Exception {
        var v=vectors().get(3);var u=unsigned(v);byte[] witness=HexFormat.of().parseHex(v.path("signatureHex").asText());
        for(var changed:List.of(copy(u,u.nonce(),u.recipient(),u.amountAtomic(),"other-token.near",u.gas(),u.memo()),copy(u,u.nonce(),u.recipient(),u.amountAtomic(),u.tokenContract(),u.gas()+1,u.memo()),copy(u,u.nonce(),u.recipient(),u.amountAtomic(),u.tokenContract(),u.gas(),"changed memo")))
            assertThrows(RuntimeException.class,()->MpcNearTransactionCodec.assemble(changed,witness));
        byte[] raw=Base64.getDecoder().decode(v.path("signedTransactionBase64").asText());raw[raw.length-1]^=1;
        assertThrows(RuntimeException.class,()->MpcNearTransactionCodec.verifySelected(u,new MpcNearTransactionCodec.Signed(Base64.getEncoder().encodeToString(raw),v.path("txHash").asText())));
    }
    @Test void invalidAccountSyntaxUnsignedBoundsAndPartialJointKeysAreRejected()throws Exception {
        for(String invalid:List.of("a..near","a__b","a.-b","x",".bad","UPPER.near"))assertThrows(RuntimeException.class,()->MpcNearTransactionCodec.account(invalid));
        assertThrows(RuntimeException.class,()->MpcNearTransactionCodec.address(new byte[31]));
        var u=unsigned(vectors().get(0));assertThrows(RuntimeException.class,()->copy(u,0,u.recipient(),u.amountAtomic(),null,0,null));
        assertThrows(RuntimeException.class,()->copy(u,u.nonce(),u.recipient(),BigInteger.ONE.shiftLeft(128),null,0,null));
        assertThrows(RuntimeException.class,()->copy(u,u.nonce(),u.recipient(),u.amountAtomic(),"token.near",300_000_000_000_001L,null));
    }
    private static MpcNearTransactionCodec.Unsigned copy(MpcNearTransactionCodec.Unsigned u,long nonce,String recipient,BigInteger amount,String token,long gas,String memo){return new MpcNearTransactionCodec.Unsigned(u.network(),u.genesisHash(),u.sender(),nonce,recipient,amount,token,gas,memo,u.blockHeight(),u.validUntilHeight(),u.blockHash(),u.publicKeyHex());}
}
