package com.surprising.wallet.chain.monero;

import com.surprising.wallet.common.signing.MpcClient;
import com.surprising.wallet.model.mpc.MoneroSigningPayload;
import com.surprising.wallet.sdk.signing.MoneroPublicAddressCodec;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.junit.jupiter.api.Assertions.*;

/** Public payload/artifact boundaries only; opaque native signing validity is tested against actual nodes. */
class MoneroSigningPayloadTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final String SOURCE="89FAEsq3NekPZd19gJqD8YjhvHdYNAxhsPHbrHZdjge6emJ1rdgrJRV3gwxq4mey7JYBcNUUeTweVZErX9aYkAWZFPx4kri";
    private static final String TARGET="8AbiPXjXqBUMdGnfTgjRrv95Ty7bBt5LkA6Nj7a534JnJqPejuPD3sU5UYV1vN2phAGzj3dpGYJRS6hBsd7RqtEbH6yYNTZ";
    private static final String GENESIS="11".repeat(32);
    private MoneroSigningPayload payload(BigDecimal amount){return new MoneroSigningPayload("regtest",GENESIS,SOURCE,TARGET,amount,BigDecimal.ZERO,BigInteger.valueOf(100),10);}

    @Test void minimumAtomicUnitAndFeeUseCanonicalDecimalStrings() {
        var payload=payload(new BigDecimal("0.000000000001"));var policy=JSON.readTree(HexFormat.of().parseHex(payload.preimageHex(JSON)));
        assertEquals("1",policy.path("amountAtomic").asText());assertTrue(policy.path("amountAtomic").isTextual());
        assertEquals("100",policy.path("maximumFeeAtomic").asText());assertEquals("PAYMENT",policy.path("kind").asText());
        assertEquals(payload.preimageHex(JSON),JSON.readValue(JSON.writeValueAsBytes(payload),MoneroSigningPayload.class).preimageHex(JSON));
    }
    @Test void uint64AmountsRemainExactAndOverflowIsRejected() {
        var maximum=BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);
        assertEquals(maximum,payload(new BigDecimal(maximum,12)).amountAtomic());
        assertThrows(IllegalArgumentException.class,()->payload(new BigDecimal(maximum.add(BigInteger.ONE),12)));
    }
    @Test void fractionalAtomicUnitsAndNonpositivePaymentsAreRejected() {
        assertThrows(ArithmeticException.class,()->payload(new BigDecimal("0.0000000000001")));
        assertThrows(IllegalArgumentException.class,()->payload(BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class,()->payload(BigDecimal.ONE.negate()));
    }
    @Test void nativeSourceRecipientAndNetworkMustMatch() {
        assertThrows(IllegalArgumentException.class,()->new MoneroSigningPayload("regtest",GENESIS,SOURCE,SOURCE,BigDecimal.ONE,BigDecimal.ZERO,BigInteger.ONE,10));
        assertThrows(IllegalArgumentException.class,()->new MoneroSigningPayload("stagenet",GENESIS,SOURCE,TARGET,BigDecimal.ONE,BigDecimal.ZERO,BigInteger.ONE,10));
        assertThrows(IllegalArgumentException.class,()->new MoneroSigningPayload("regtest","GG".repeat(32),SOURCE,TARGET,BigDecimal.ONE,BigDecimal.ZERO,BigInteger.ONE,10));
    }
    @Test void feeCustomerChargeAndConfirmationBoundsAreExplicit() {
        for(var fee:List.of(BigInteger.ZERO,BigInteger.ONE.negate(),BigInteger.ONE.shiftLeft(64)))
            assertThrows(IllegalArgumentException.class,()->new MoneroSigningPayload("regtest",GENESIS,SOURCE,TARGET,BigDecimal.ONE,BigDecimal.ZERO,fee,10));
        assertThrows(IllegalArgumentException.class,()->new MoneroSigningPayload("regtest",GENESIS,SOURCE,TARGET,BigDecimal.ONE,BigDecimal.ONE.negate(),BigInteger.ONE,10));
        for(int confirmations:List.of(0,10001))assertThrows(IllegalArgumentException.class,()->new MoneroSigningPayload("regtest",GENESIS,SOURCE,TARGET,BigDecimal.ONE,BigDecimal.ZERO,BigInteger.ONE,confirmations));
    }
    @Test void publicSelectionRetainsExactOriginalSessionHoldersFeeAndArtifactDigest() throws Exception {
        var request=request();var original=JSON.writeValueAsBytes(artifact(request));
        var selected=MoneroSignedPayment.verify(request,publicKey(),original);
        assertEquals(request.sessionId(),selected.signSessionId());assertEquals(request.online(),selected.originalHolders());
        assertEquals(BigInteger.TEN,selected.feeAtomic());assertEquals("22".repeat(32),selected.transactionHash());
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original)),selected.artifactHash());
    }
    @Test void aDifferentSigningSessionOrIntentCannotSelectTheOriginalTransaction() throws Exception {
        var request=request();var original=JSON.writeValueAsBytes(artifact(request));
        var other=new MpcClient.Request(UUID.randomUUID(),request.tenantId(),request.keyId(),1,request.operation(),request.algorithm(),request.online(),request.messageHex());
        assertThrows(IllegalArgumentException.class,()->MoneroSignedPayment.verify(other,publicKey(),original));
        var altered=artifact(request).put("intentHash","00".repeat(32));
        assertThrows(IllegalArgumentException.class,()->MoneroSignedPayment.verify(request,publicKey(),JSON.writeValueAsBytes(altered)));
    }
    private MpcClient.Request request(){return new MpcClient.Request(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),1,MpcClient.Operation.SIGN,
            MpcClient.Algorithm.MONERO_NATIVE_2_OF_3,List.of("aa".repeat(32),"bb".repeat(32)),payload(new BigDecimal("0.000000000001")).preimageHex(JSON));}
    private static String publicKey(){return HexFormat.of().formatHex(MoneroPublicAddressCodec.decode(SOURCE).publicKeys());}
    private static ObjectNode artifact(MpcClient.Request request) throws Exception {
        var result=JSON.createObjectNode().put("protocol","MONERO_NATIVE_2_OF_3").put("tenantId",request.tenantId().toString())
                .put("keyId",request.keyId().toString()).put("sessionId",request.sessionId().toString())
                .put("intentHash",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical(request))))
                .put("transactionHash","22".repeat(32)).put("transactionSetHex","aa").put("feeAtomic","10");
        result.set("policy",JSON.readTree(HexFormat.of().parseHex(request.messageHex())));result.set("online",JSON.valueToTree(request.online()));
        var desc=result.putObject("description").put("fee",10).put("ring_size",16).put("unlock_time",0)
                .put("amount_in",101).put("amount_out",91).put("change_amount",90).put("change_address",SOURCE);
        desc.putArray("recipients").addObject().put("address",TARGET).put("amount",1);
        desc.putArray("sources").addObject().put("pubkey","33".repeat(64)).put("global_index",1).put("amount",101);return result;
    }
    private static byte[] canonical(MpcClient.Request request) {
        Map<String,Object> fields=JSON.readValue(JSON.writeValueAsBytes(request),JSON.getTypeFactory().constructMapType(TreeMap.class,String.class,Object.class));
        return JSON.writeValueAsBytes(fields);
    }
}
