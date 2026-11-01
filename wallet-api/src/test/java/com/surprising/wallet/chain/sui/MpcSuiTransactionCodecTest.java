package com.surprising.wallet.chain.sui;

import com.surprising.wallet.sdk.signing.sui.MpcSuiTransactionCodec;
import com.surprising.wallet.sdk.signing.sui.SuiCoin;

import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.*;
import static org.junit.jupiter.api.Assertions.*;

class MpcSuiTransactionCodecTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    private JsonNode fixtures()throws Exception{try(var in=getClass().getResourceAsStream("/mpc/sui-codec-reference.json")){return JSON.readTree(in).path("vectors");}}
    private static List<SuiCoin> coins(JsonNode value){
        var result=new ArrayList<SuiCoin>();for(var c:value)result.add(new SuiCoin(c.path("objectId").asText(),c.path("version").asText(),c.path("digest").asText(),new BigDecimal(c.path("balance").asText())));return result;
    }
    private static MpcSuiTransactionCodec.Unsigned unsigned(JsonNode v){return new MpcSuiTransactionCodec.Unsigned(v.path("network").asText(),v.path("chainIdentifier").asText(),v.path("sender").asText(),v.path("recipient").asText(),v.path("amountAtomic").asLong(),v.path("coinType").asText(),coins(v.path("inputs")),coins(v.path("gasPayment")),v.path("gasPrice").asLong(),v.path("gasBudget").asLong(),v.path("publicKeyHex").asText());}
    private static byte[] signature(JsonNode v){return Arrays.copyOfRange(Base64.getDecoder().decode(v.path("signatureBase64").asText()),1,65);}
    @Test void nativeMultiGasAndTypedTokenPtbMatchIndependentPublicSdk()throws Exception{
        for(var v:fixtures()){
            var u=unsigned(v);assertEquals(v.path("txBytesBase64").asText(),Base64.getEncoder().encodeToString(MpcSuiTransactionCodec.transactionBytes(u)));
            assertEquals(v.path("signingHashHex").asText(),HexFormat.of().formatHex(MpcSuiTransactionCodec.signingBytes(u)));
            assertEquals(v.path("digest").asText(),MpcSuiTransactionCodec.digest(u));
            var selected=MpcSuiTransactionCodec.assemble(u,signature(v));assertEquals(v.path("signatureBase64").asText(),selected.signatureBase64());
            assertDoesNotThrow(()->MpcSuiTransactionCodec.verifySelected(u,selected));
        }
    }
    @Test void recipientAmountGasPriceBudgetAndObjectVersionCannotReuseWitness()throws Exception{
        var v=fixtures().path(0);var u=unsigned(v);var g=u.gasPayment().getFirst();
        var changed=List.of(
                new MpcSuiTransactionCodec.Unsigned(u.network(),u.chainIdentifier(),u.sender(),"0x"+"cd".repeat(32),u.amountAtomic(),u.coinType(),u.inputs(),u.gasPayment(),u.gasPrice(),u.gasBudget(),u.publicKeyHex()),
                new MpcSuiTransactionCodec.Unsigned(u.network(),u.chainIdentifier(),u.sender(),u.recipient(),u.amountAtomic()+1,u.coinType(),u.inputs(),u.gasPayment(),u.gasPrice(),u.gasBudget(),u.publicKeyHex()),
                new MpcSuiTransactionCodec.Unsigned(u.network(),u.chainIdentifier(),u.sender(),u.recipient(),u.amountAtomic(),u.coinType(),u.inputs(),u.gasPayment(),u.gasPrice()+1,u.gasBudget(),u.publicKeyHex()),
                new MpcSuiTransactionCodec.Unsigned(u.network(),u.chainIdentifier(),u.sender(),u.recipient(),u.amountAtomic(),u.coinType(),u.inputs(),u.gasPayment(),u.gasPrice(),u.gasBudget()+1,u.publicKeyHex()),
                new MpcSuiTransactionCodec.Unsigned(u.network(),u.chainIdentifier(),u.sender(),u.recipient(),u.amountAtomic(),u.coinType(),u.inputs(),List.of(new SuiCoin(g.objectId(),"8",g.digest(),g.balance())),u.gasPrice(),u.gasBudget(),u.publicKeyHex()));
        for(var payment:changed)assertThrows(IllegalArgumentException.class,()->MpcSuiTransactionCodec.assemble(payment,signature(v)));
        var selected=MpcSuiTransactionCodec.assemble(u,signature(v));
        assertThrows(IllegalArgumentException.class,()->MpcSuiTransactionCodec.verifySelected(u,new MpcSuiTransactionCodec.Signed(selected.txBytesBase64(),selected.signatureBase64(),"1".repeat(32))));
    }
    @Test void tokenTypeIsSignedAndRpcBalanceCannotChangeTheExactPrincipal()throws Exception{
        var v=fixtures().path(2);var u=unsigned(v);var c=u.inputs().getFirst();
        var changedType=new MpcSuiTransactionCodec.Unsigned(u.network(),u.chainIdentifier(),u.sender(),u.recipient(),u.amountAtomic(),"0x22::usdt::USDT",u.inputs(),u.gasPayment(),u.gasPrice(),u.gasBudget(),u.publicKeyHex());
        assertThrows(IllegalArgumentException.class,()->MpcSuiTransactionCodec.assemble(changedType,signature(v)));
        var changedBalance=new MpcSuiTransactionCodec.Unsigned(u.network(),u.chainIdentifier(),u.sender(),u.recipient(),u.amountAtomic(),u.coinType(),List.of(new SuiCoin(c.objectId(),c.version(),c.digest(),c.balance().add(BigDecimal.ONE))),u.gasPayment(),u.gasPrice(),u.gasBudget(),u.publicKeyHex());
        assertArrayEquals(MpcSuiTransactionCodec.transactionBytes(u),MpcSuiTransactionCodec.transactionBytes(changedBalance));
        assertEquals(MpcSuiTransactionCodec.assemble(u,signature(v)),MpcSuiTransactionCodec.assemble(changedBalance,signature(v)));
    }
    @Test void overlappingObjectOwnershipAndWrongJointSenderFailBeforeSigning()throws Exception{
        var u=unsigned(fixtures().path(0));
        assertThrows(IllegalArgumentException.class,()->new MpcSuiTransactionCodec.Unsigned(u.network(),u.chainIdentifier(),u.sender(),u.recipient(),u.amountAtomic(),u.coinType(),List.of(),List.of(u.gasPayment().getFirst(),u.gasPayment().getFirst()),u.gasPrice(),u.gasBudget(),u.publicKeyHex()));
        assertThrows(IllegalArgumentException.class,()->new MpcSuiTransactionCodec.Unsigned(u.network(),u.chainIdentifier(),u.recipient(),u.sender(),u.amountAtomic(),u.coinType(),u.inputs(),u.gasPayment(),u.gasPrice(),u.gasBudget(),u.publicKeyHex()));
        assertThrows(IllegalArgumentException.class,()->new MpcSuiTransactionCodec.Unsigned(u.network(),"bad-checkpoint",u.sender(),u.recipient(),u.amountAtomic(),u.coinType(),u.inputs(),u.gasPayment(),u.gasPrice(),u.gasBudget(),u.publicKeyHex()));
    }
}
