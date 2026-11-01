package com.surprising.wallet.sdk.signing.aptos;

import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

class MpcAptosTransactionCodecTest {
    private static final HexFormat HEX=HexFormat.of();
    private final ObjectMapper json=new ObjectMapper();
    private JsonNode fixtures() throws Exception {
        try(var resource=getClass().getResourceAsStream("/mpc/aptos-codec-reference.json")){assertNotNull(resource);return json.readTree(resource);}
    }
    private MpcAptosTransactionCodec.Unsigned unsigned(JsonNode v) {
        return new MpcAptosTransactionCodec.Unsigned(v.path("sender").asText(),v.path("sequence").asLong(),v.path("recipient").asText(),v.path("amountAtomic").asLong(),
                v.path("metadata").isNull()?null:v.path("metadata").asText(),v.path("maxGasAmount").asLong(),v.path("gasUnitPrice").asLong(),v.path("expiration").asLong(),v.path("chainId").asInt(),v.path("publicKeyHex").asText());
    }
    @Test void aptAndFaRawMessageExternalAuthenticatorAndHashMatchOfficialSdk() throws Exception {
        for(var v:fixtures().path("vectors")) {
            var unsigned=unsigned(v);assertEquals(v.path("rawHex").asText(),HEX.formatHex(MpcAptosTransactionCodec.raw(unsigned)));
            assertEquals(v.path("signingHex").asText(),HEX.formatHex(MpcAptosTransactionCodec.signingBytes(unsigned)));
            var signed=MpcAptosTransactionCodec.assemble(unsigned,HEX.parseHex(v.path("signatureHex").asText()));
            assertEquals(v.path("signedHex").asText(),signed.transactionHex());assertEquals(v.path("txId").asText(),signed.txId());
            var request=json.readTree(signed.json());assertEquals(unsigned.sender(),request.path("sender").asText());
            assertEquals("ed25519_signature",request.path("signature").path("type").asText());
            assertEquals("0x"+unsigned.publicKeyHex(),request.path("signature").path("public_key").asText());
            assertEquals(unsigned.metadata()==null?"0x1::aptos_account::transfer":"0x1::primary_fungible_store::transfer",request.path("payload").path("function").asText());
        }
    }
    @Test void changedAmountSequenceNetworkAndExpiredFieldsCannotUseOriginalSignature() throws Exception {
        var v=fixtures().path("vectors").path(0);var u=unsigned(v);byte[] signature=HEX.parseHex(v.path("signatureHex").asText());
        for(var changed:java.util.List.of(
                new MpcAptosTransactionCodec.Unsigned(u.sender(),u.sequence()+1,u.recipient(),u.amountAtomic(),null,u.maxGasAmount(),u.gasUnitPrice(),u.expiration(),u.chainId(),u.publicKeyHex()),
                new MpcAptosTransactionCodec.Unsigned(u.sender(),u.sequence(),u.recipient(),u.amountAtomic()+1,null,u.maxGasAmount(),u.gasUnitPrice(),u.expiration(),u.chainId(),u.publicKeyHex()),
                new MpcAptosTransactionCodec.Unsigned(u.sender(),u.sequence(),u.recipient(),u.amountAtomic(),null,u.maxGasAmount(),u.gasUnitPrice(),u.expiration()+1,u.chainId(),u.publicKeyHex()),
                new MpcAptosTransactionCodec.Unsigned(u.sender(),u.sequence(),u.recipient(),u.amountAtomic(),null,u.maxGasAmount(),u.gasUnitPrice(),u.expiration(),u.chainId()+1,u.publicKeyHex())))
            assertThrows(IllegalArgumentException.class,()->MpcAptosTransactionCodec.assemble(changed,signature));
        signature[0]^=1;assertThrows(IllegalArgumentException.class,()->MpcAptosTransactionCodec.assemble(u,signature));
        assertThrows(IllegalArgumentException.class,()->MpcAptosTransactionCodec.assemble(u,new byte[63]));
    }
    @Test void senderMustMatchJointAuthenticationKeyAndMalformedFieldsAreRejected() throws Exception {
        var v=fixtures().path("vectors").path(0);var u=unsigned(v);
        assertThrows(IllegalArgumentException.class,()->new MpcAptosTransactionCodec.Unsigned("0x1",3,u.recipient(),1,null,2000,100,2000000000,4,u.publicKeyHex()));
        assertThrows(IllegalArgumentException.class,()->new MpcAptosTransactionCodec.Unsigned(u.sender(),-1,u.recipient(),1,null,2000,100,2000000000,4,u.publicKeyHex()));
        assertThrows(IllegalArgumentException.class,()->new MpcAptosTransactionCodec.Unsigned(u.sender(),3,"0x",1,null,2000,100,2000000000,4,u.publicKeyHex()));
        assertThrows(IllegalArgumentException.class,()->new MpcAptosTransactionCodec.Unsigned(u.sender(),3,u.recipient(),0,null,2000,100,2000000000,4,u.publicKeyHex()));
        assertThrows(IllegalArgumentException.class,()->new MpcAptosTransactionCodec.Unsigned(u.sender(),3,u.recipient(),1,null,2000,100,2000000000,256,u.publicKeyHex()));
        assertThrows(IllegalArgumentException.class,()->new MpcAptosTransactionCodec.Unsigned(u.sender(),3,u.sender(),1,null,2000,100,2000000000,4,u.publicKeyHex()));
    }
    @Test void primaryStoreAddressMatchesFrameworkUserDerivedObjectDomain() throws Exception {
        var f=fixtures();assertEquals(f.path("primaryStore").asText(),MpcAptosTransactionCodec.primaryStore(f.path("vectors").path(0).path("sender").asText(),"0xcafe"));
        assertNotEquals(MpcAptosTransactionCodec.primaryStore("0x1","0xcafe"),MpcAptosTransactionCodec.primaryStore("0x2","0xcafe"));
    }
}
