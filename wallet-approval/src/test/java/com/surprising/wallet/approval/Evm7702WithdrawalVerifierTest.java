package com.surprising.wallet.approval;

import com.surprising.wallet.approval.chain.ApprovalWithdrawalVerifiers;
import com.surprising.wallet.approval.gateway.ApprovalChainRpcClient;
import com.surprising.wallet.approval.model.RegisteredMpcKey;
import com.surprising.wallet.common.approval.*;
import com.surprising.wallet.common.chain.*;
import com.surprising.wallet.common.signing.*;
import com.surprising.wallet.sdk.signing.MpcEvmTransactionCodec;
import com.surprising.wallet.sdk.signing.evm7702.*;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.bitcoinj.base.Sha256Hash;
import org.bitcoinj.crypto.ECKey;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.web3j.crypto.*;
import org.web3j.utils.Numeric;
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Public canonical plan + actual fixture signatures; no execution node or private production material. */
class Evm7702WithdrawalVerifierTest {
    private final UUID tenant=UUID.randomUUID(),batch=UUID.randomUUID(),authorityId=UUID.randomUUID(),relayerId=UUID.randomUUID();
    private final ECKey authority=new ECKey(),relayer=new ECKey();private final ObjectMapper json=new ObjectMapper();
    private final Instant now=Instant.parse("2026-11-02T00:00:00Z");private final String delegate="0x"+"33".repeat(20),token="0x"+"44".repeat(20);
    private final ApprovalWithdrawalVerifiers verifier=new ApprovalWithdrawalVerifiers(json,mock(ApprovalChainRpcClient.class));
    private final Map<UUID,RegisteredMpcKey> keys=Map.of(authorityId,registered(authorityId,authority),relayerId,registered(relayerId,relayer));
    private final BigInteger gas=BigInteger.valueOf(200_000),price=BigInteger.valueOf(100),priority=BigInteger.TEN;

    @ParameterizedTest @ValueSource(booleans={false,true})
    void allSlotsBindOneWholeApprovedPlanIncludingRealInnerSignatureAndOriginalDelegation(boolean install) {
        var f=fixture(install);var operation=candidate(f,"operation-0",null);var outer=candidate(f,"relayer",outer(f,f.request(),authority,f.authorization()));
        assertTrue(verify(f,operation,f.facts()).supportsExactRecovery());assertEquals(gas.multiply(price),verify(f,outer,f.facts()).maximumFeeAtomic());
        assertEquals(ApprovalTransactionHash.business(f.reviews(),operation.command()),ApprovalTransactionHash.business(f.reviews(),outer.command()));
        if(install) {
            var authorization=candidate(f,"authorization-0",null);assertEquals(3,verify(f,authorization,f.facts()).inputs().size());
            assertEquals(ApprovalTransactionHash.version(operation.command()),ApprovalTransactionHash.version(authorization.command()));
        } else assertEquals(2,verify(f,operation,f.facts()).inputs().size());
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void finalOuterCannotSubstituteItemSignatureOrDelegationEvenWithAValidRecomputedDigest(boolean install) {
        var f=fixture(install);var item=f.request().items().getFirst();var items=new ArrayList<>(f.request().items());
        items.set(0,new Evm7702PayoutItem(item.withdrawalId(),item.itemIndex(),item.token(),item.recipient(),item.amount().add(BigInteger.ONE),item.callGasLimit()));
        var changed=new Evm7702PayoutRequest(f.request().batchId(),f.request().authority(),f.request().executor(),items,f.request().operationNonce(),f.request().deadline());
        assertThrows(IllegalArgumentException.class,()->verify(f,candidate(f,"relayer",outer(f,changed,authority,f.authorization())),f.facts()));
        assertThrows(IllegalArgumentException.class,()->verify(f,candidate(f,"relayer",outer(f,f.request(),new ECKey(),f.authorization())),f.facts()));
        if(install) {
            var unexpected=new MpcEvm7702Codec.Authorization(BigInteger.ONE,address(authority),"0x"+"55".repeat(20),BigInteger.valueOf(7));
            assertThrows(IllegalArgumentException.class,()->verify(f,candidate(f,"relayer",outer(f,f.request(),authority,unexpected)),f.facts()));
        }
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void everyInnerSlotRequiresPinnedCodeExecutorCompleteReviewAndUnexpiredOperation(boolean install) {
        var f=fixture(install);var candidate=candidate(f,"operation-0",null);var facts=f.facts().deepCopy();facts.put("delegateCodeHash","0x"+"00".repeat(32));
        assertThrows(IllegalArgumentException.class,()->verify(f,candidate,facts));
        var wrongExecutor=f.facts().deepCopy();wrongExecutor.put("expectedExecutor","0x"+"00".repeat(32));assertThrows(IllegalArgumentException.class,()->verify(f,candidate,wrongExecutor));
        assertThrows(IllegalArgumentException.class,()->verifier.verifyBatch(f.reviews().subList(0,1),keys.get(authorityId),keys,candidate,now,evidence(f.reviews().getFirst(),f.facts())));
        assertThrows(IllegalArgumentException.class,()->verifier.verifyBatch(f.reviews(),keys.get(authorityId),keys,candidate,now.plusSeconds(601),evidence(f.reviews().getFirst(),f.facts())));
    }
    private Fixture fixture(boolean install) {
        var asset=new ApprovalAssetSnapshot("ETH","localnet","USDC","ETH","eip155:1",token,6,18,new BigDecimal("0.02"));var reviews=new ArrayList<ApprovalRequest>();var items=new ArrayList<Evm7702PayoutItem>();
        for(int index=0;index<2;index++) {
            var intent=new WithdrawalIntent(UUID.randomUUID(),tenant,"CW-"+index,"ETH","localnet","USDC","0x"+(index==0?"11":"22").repeat(20),BigDecimal.ONE,new BigDecimal("0.01"),"API",UUID.randomUUID(),"customer","merchant-"+index,"ADDRESS");
            reviews.add(new ApprovalRequest(intent,asset.bind(intent,json),asset,UUID.randomUUID(),1,List.of("ALL_WITHDRAWALS"),1,"APPROVED",false,now,now.plusSeconds(3600)));
            items.add(new Evm7702PayoutItem(Hash.sha3((tenant+":WITHDRAWAL:"+intent.id()).getBytes(StandardCharsets.UTF_8)),BigInteger.valueOf(index),token,intent.recipient(),BigInteger.valueOf(1_000_000),BigInteger.valueOf(100_000)));
        }
        var request=new Evm7702PayoutRequest(Hash.sha3((tenant+":WITHDRAWAL:"+batch).getBytes(StandardCharsets.UTF_8)),address(authority),address(relayer),items,BigInteger.ZERO,BigInteger.valueOf(now.plusSeconds(600).getEpochSecond()));
        var authorization=install?new MpcEvm7702Codec.Authorization(BigInteger.ONE,address(authority),delegate,BigInteger.valueOf(7)):null;
        var prepared=new Evm7702PayoutPayload(BigInteger.ONE,BigInteger.valueOf(7),priority,price,BigInteger.valueOf(12_000_000),owner(authorityId,authority),authorization,request);
        var plan=new Evm7702SigningPayload.Plan(1,BigInteger.valueOf(5),owner(relayerId,relayer),json.writeValueAsString(prepared),reviews.stream().map(r->r.intent().businessReference()).toList());
        var facts=json.createObjectNode().put("delegate",delegate).put("delegateCode","0x60006000f3").put("delegateCodeHash",Hash.sha3("0x60006000f3"))
                .put("relayer",address(relayer)).put("authorityCode",install?"0x":"0xef0100"+delegate.substring(2)).put("expectedExecutor","0x"+"0".repeat(24)+address(relayer).substring(2))
                .put("chainId","0x1").put("authorityNonce","0x7").put("operationNonce","0x0").put("relayerNonce","0x5").put("maximumGasLimit",gas.toString());
        facts.putObject("block").put("hash","0x"+"aa".repeat(32)).put("number","0x42").put("gasLimit","0xb71b00");
        facts.putObject("evmFees").put("chainId","0x1").put("feeModel","standard").put("maximumSignedTransactionBytes",65536).put("maximumGas",gas.toString()).put("extraFeeReserveAtomic","0").put("extraFeeQuoteAtomic","0");
        return new Fixture(reviews,request,authorization,plan,facts);
    }
    private Evm7702SigningPayload.Outer outer(Fixture f,Evm7702PayoutRequest request,ECKey innerSigner,MpcEvm7702Codec.Authorization authorization) {
        byte[] digest=new Evm7702PayoutSigner().digest(BigInteger.ONE,request),der=innerSigner.sign(Sha256Hash.wrap(digest)).encodeToDER();
        var parts=MpcEvmTransactionCodec.signatureData(digest,innerSigner.getPubKey(),der);byte[] signature=new byte[65];
        System.arraycopy(parts.getR(),0,signature,0,32);System.arraycopy(parts.getS(),0,signature,32,32);signature[64]=parts.getV()[0];
        var tuples=authorization==null?List.<AuthorizationTuple>of():List.of(MpcEvm7702Codec.authorization(authorization,authority.getPubKey(),authority.sign(Sha256Hash.wrap(MpcEvm7702Codec.digest(authorization))).encodeToDER()));
        String calldata=new Evm7702PayoutCodec().encode(request,signature);var raw=MpcEvm7702Codec.batch(1,BigInteger.valueOf(5),priority,price,gas,address(authority),calldata,tuples);
        return new Evm7702SigningPayload.Outer(HexFormat.of().formatHex(MpcEvmTransactionCodec.unsignedPayload(raw,1)),gas.longValueExact(),new BigDecimal(gas.multiply(price)).movePointLeft(18));
    }
    private SigningCandidate candidate(Fixture f,String slot,Evm7702SigningPayload.Outer outer) {
        UUID id=slot.equals("relayer")?relayerId:authorityId;MpcSignCommand.Purpose purpose=slot.equals("relayer")?MpcSignCommand.Purpose.TRANSACTION:slot.equals("operation-0")?MpcSignCommand.Purpose.EIP7702_OPERATION:MpcSignCommand.Purpose.EIP7702_AUTHORIZATION;
        byte[] message=slot.equals("relayer")?MpcEvmTransactionCodec.digest(MpcEvmTransactionCodec.restore(HexFormat.of().parseHex(outer.unsignedHex()),1),1)
                :slot.equals("operation-0")?new Evm7702PayoutSigner().digest(BigInteger.ONE,f.request()):MpcEvm7702Codec.digest(f.authorization());
        String preimage=HexFormat.of().formatHex(message);var envelope=new Evm7702SigningPayload(f.plan(),outer);
        var command=new MpcSignCommand(tenant,id,1,ChainType.ETH,MpcSignCommand.BusinessType.WITHDRAWAL,"eip7702:"+tenant+":"+batch,slot,purpose,Evm7702SigningPayload.ENCODING,HexFormat.of().formatHex(json.writeValueAsBytes(envelope)),preimage);
        var execution=new MpcClient.Request(UUID.randomUUID(),tenant,id,1,MpcClient.Operation.SIGN,MpcClient.Algorithm.ECDSA_SECP256K1,List.of("a".repeat(64),"c".repeat(64)),preimage);
        return new SigningCandidate(UUID.randomUUID(),f.reviews().stream().map(r->r.intent().id()).toList(),command,execution,null,null);
    }
    private ApprovalWithdrawalVerifiers.Verified verify(Fixture f,SigningCandidate candidate,ObjectNode facts){return verifier.verifyBatch(f.reviews(),keys.get(candidate.command().keyId()),keys,candidate,now,evidence(f.reviews().getFirst(),facts));}
    private JsonNode evidence(ApprovalRequest review,JsonNode facts){return json.createObjectNode().put("chain","ETH").put("networkIdentity","eip155:1").set("facts",facts);}
    private ChainAddressRecord owner(UUID id,ECKey key){return ChainAddressRecord.builder().tenantId(tenant).mpcKeyId(id).chain("ETH").assetSymbol("ETH").address(address(key)).ownerAddress(address(key)).walletRole("HOT").build();}
    private RegisteredMpcKey registered(UUID id,ECKey key){return new RegisteredMpcKey(tenant,id,"EVM","ECDSA_SECP256K1",1,HexFormat.of().formatHex(key.getPubKey()),null,"ACTIVE");}
    private static String address(ECKey key){return "0x"+Keys.getAddress(Sign.publicFromPoint(key.decompress().getPubKey()));}
    private record Fixture(List<ApprovalRequest> reviews,Evm7702PayoutRequest request,MpcEvm7702Codec.Authorization authorization,Evm7702SigningPayload.Plan plan,ObjectNode facts) { }
}
