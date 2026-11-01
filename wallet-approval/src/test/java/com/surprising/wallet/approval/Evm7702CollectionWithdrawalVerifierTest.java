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
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Two independent source signatures and one exact outer wire; fixtures contain only generated test keys. */
class Evm7702CollectionWithdrawalVerifierTest {
    private final UUID tenant=UUID.randomUUID(),batch=UUID.randomUUID(),relayerId=UUID.randomUUID();
    private final List<UUID> ids=List.of(UUID.randomUUID(),UUID.randomUUID());
    private final List<ECKey> sources=List.of(new ECKey(),new ECKey());private final ECKey relayer=new ECKey();
    private final ObjectMapper json=new ObjectMapper();private final Instant now=Instant.parse("2026-11-02T00:00:00Z");
    private final String delegate="0x"+"33".repeat(20),collector="0x"+"55".repeat(20),treasury="0x"+"66".repeat(20),token="0x"+"44".repeat(20);
    private final BigInteger gas=BigInteger.valueOf(400_000),price=BigInteger.valueOf(100),priority=BigInteger.TEN;
    private final ApprovalWithdrawalVerifiers verifier=new ApprovalWithdrawalVerifiers(json,mock(ApprovalChainRpcClient.class));
    private Map<UUID,RegisteredMpcKey> keys(){var keys=new HashMap<UUID,RegisteredMpcKey>();keys.put(relayerId,registered(relayerId,relayer));for(int i=0;i<2;i++)keys.put(ids.get(i),registered(ids.get(i),sources.get(i)));return keys;}

    @ParameterizedTest @ValueSource(booleans={false,true})
    void everySourceSlotAndCompleteOuterBindBothOperatingReceipts(boolean install) {
        var f=fixture(install);for(int i=0;i<2;i++) {
            assertEquals(install?5:3,verify(f,candidate(f,"operation-"+i,null),f.facts()).inputs().size());
            if(install)assertTrue(verify(f,candidate(f,"authorization-"+i,null),f.facts()).supportsExactRecovery());
        }
        assertEquals(gas.multiply(price),verify(f,candidate(f,"relayer",outer(f,false)),f.facts()).maximumFeeAtomic());
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void rejectsForeignInnerSignerSourceDeficitExpiredOperationAndUntrustedCollector(boolean install) {
        var f=fixture(install);assertThrows(IllegalArgumentException.class,()->verify(f,candidate(f,"relayer",outer(f,true)),f.facts()));
        var missing=f.facts().deepCopy();((ObjectNode)missing.path("sources").path(1)).put("balance","0x0");
        assertThrows(IllegalArgumentException.class,()->verify(f,candidate(f,"operation-0",null),missing));
        var denied=f.facts().deepCopy().put("relayerAllowed","0x0");assertThrows(IllegalArgumentException.class,()->verify(f,candidate(f,"operation-0",null),denied));
        var changed=f.facts().deepCopy().put("collectorCode","0x6001");assertThrows(IllegalArgumentException.class,()->verify(f,candidate(f,"operation-0",null),changed));
        var c=candidate(f,"operation-0",null);assertThrows(IllegalArgumentException.class,()->verifier.verifyBatch(f.receipts(),keys().get(ids.getFirst()),keys(),c,now.plusSeconds(601),evidence(f.facts())));
        assertThrows(IllegalArgumentException.class,()->verifier.verifyBatch(f.receipts().subList(0,1),keys().get(ids.getFirst()),keys(),c,now,evidence(f.facts())));
    }
    private Fixture fixture(boolean install) {
        var asset=new ApprovalAssetSnapshot("ETH","localnet","USDC","ETH","eip155:1",token,6,18,new BigDecimal("0.02"));
        var receipts=new ArrayList<ApprovalRequest>();var items=new ArrayList<Evm7702CollectionPayload.Item>();
        var facts=json.createObjectNode().put("delegate",delegate).put("delegateCode","0x60006000f3").put("delegateCodeHash",Hash.sha3("0x60006000f3"))
                .put("collector",collector).put("collectorCode","0x60016000f3").put("collectorCodeHash",Hash.sha3("0x60016000f3"))
                .put("relayer",address(relayer)).put("expectedCollector","0x"+"0".repeat(24)+collector.substring(2)).put("relayerAllowed","0x1")
                .put("chainId","0x1").put("relayerNonce","0x5").put("relayerBalance","0xde0b6b3a7640000").put("maximumGasLimit",gas.toString());
        facts.putObject("block").put("hash","0x"+"aa".repeat(32)).put("number","0x42").put("gasLimit","0xb71b00");
        facts.putObject("evmFees").put("chainId","0x1").put("feeModel","standard").put("maximumSignedTransactionBytes",65536).put("maximumGas",gas.toString()).put("extraFeeReserveAtomic","0").put("extraFeeQuoteAtomic","0");
        var observed=facts.putArray("sources");
        for(int i=0;i<2;i++) {
            var intent=new WithdrawalIntent(UUID.randomUUID(),tenant,"COLLECTION-"+i,"ETH","localnet","USDC",treasury,BigDecimal.ONE,new BigDecimal("0.01"),"SYSTEM",UUID.randomUUID(),"AUTOMATION:fixture","","ADDRESS");
            receipts.add(new ApprovalRequest(intent,asset.bind(intent,json),asset,UUID.randomUUID(),1,List.of("INDEPENDENT_OPERATING_RULE"),0,"APPROVED",false,now,now.plusSeconds(3600)));
            var request=new Evm7702CollectionRequest(Hash.sha3((tenant+":"+batch).getBytes(StandardCharsets.UTF_8)),BigInteger.valueOf(i),address(sources.get(i)),collector,token,treasury,BigInteger.valueOf(1_000_000),BigInteger.ZERO,BigInteger.valueOf(now.plusSeconds(600).getEpochSecond()),BigInteger.valueOf(100_000));
            var authorization=install?new MpcEvm7702Codec.Authorization(BigInteger.ONE,address(sources.get(i)),delegate,BigInteger.valueOf(7)):null;
            items.add(new Evm7702CollectionPayload.Item(owner(ids.get(i),sources.get(i)),authorization,request));
            observed.addObject().put("authority",address(sources.get(i))).put("code",install?"0x":"0xef0100"+delegate.substring(2)).put("nonce","0x7").put("operationNonce","0x0").put("balance","0x989680");
        }
        var prepared=new Evm7702CollectionPayload(BigInteger.ONE,BigInteger.valueOf(5),priority,price,BigInteger.valueOf(12_000_000),items);
        var plan=new Evm7702SigningPayload.Plan(1,BigInteger.valueOf(5),owner(relayerId,relayer),json.writeValueAsString(prepared),receipts.stream().map(r->r.intent().businessReference()).toList());
        return new Fixture(receipts,prepared,plan,facts);
    }
    private Evm7702SigningPayload.Outer outer(Fixture f,boolean wrongSigner) {
        var signatures=new ArrayList<byte[]>();var tuples=new ArrayList<AuthorizationTuple>();
        for(int i=0;i<2;i++) {
            var key=wrongSigner&&i==1?new ECKey():sources.get(i);var item=f.prepared().items().get(i);byte[] digest=new Evm7702OperationSigner().digest(BigInteger.ONE,item.request());
            var parts=MpcEvmTransactionCodec.signatureData(digest,key.getPubKey(),key.sign(Sha256Hash.wrap(digest)).encodeToDER());byte[] signature=new byte[65];
            System.arraycopy(parts.getR(),0,signature,0,32);System.arraycopy(parts.getS(),0,signature,32,32);signature[64]=parts.getV()[0];signatures.add(signature);
            if(item.authorization()!=null)tuples.add(MpcEvm7702Codec.authorization(item.authorization(),sources.get(i).getPubKey(),sources.get(i).sign(Sha256Hash.wrap(MpcEvm7702Codec.digest(item.authorization()))).encodeToDER()));
        }
        String data=new Evm7702ContractCodec().encodeCollectBatch(f.prepared().items().stream().map(Evm7702CollectionPayload.Item::request).toList(),signatures);
        var raw=MpcEvm7702Codec.batch(1,BigInteger.valueOf(5),priority,price,gas,collector,data,tuples);
        return new Evm7702SigningPayload.Outer(HexFormat.of().formatHex(MpcEvmTransactionCodec.unsignedPayload(raw,1)),gas.longValueExact(),new BigDecimal(gas.multiply(price)).movePointLeft(18));
    }
    private SigningCandidate candidate(Fixture f,String slot,Evm7702SigningPayload.Outer outer) {
        boolean isOuter=slot.equals("relayer");int index=isOuter?0:Integer.parseInt(slot.substring(slot.indexOf('-')+1));UUID id=isOuter?relayerId:ids.get(index);
        var purpose=isOuter?MpcSignCommand.Purpose.TRANSACTION:slot.startsWith("operation")?MpcSignCommand.Purpose.EIP7702_OPERATION:MpcSignCommand.Purpose.EIP7702_AUTHORIZATION;
        byte[] digest=isOuter?MpcEvmTransactionCodec.digest(MpcEvmTransactionCodec.restore(HexFormat.of().parseHex(outer.unsignedHex()),1),1)
                :purpose==MpcSignCommand.Purpose.EIP7702_OPERATION?new Evm7702OperationSigner().digest(BigInteger.ONE,f.prepared().items().get(index).request()):MpcEvm7702Codec.digest(f.prepared().items().get(index).authorization());
        String message=HexFormat.of().formatHex(digest);var command=new MpcSignCommand(tenant,id,1,ChainType.ETH,MpcSignCommand.BusinessType.COLLECTION,"eip7702:"+tenant+":"+batch,slot,purpose,Evm7702SigningPayload.ENCODING,
                HexFormat.of().formatHex(json.writeValueAsBytes(new Evm7702SigningPayload(f.plan(),outer))),message);
        return new SigningCandidate(UUID.randomUUID(),List.of(),command,new MpcClient.Request(UUID.randomUUID(),tenant,id,1,MpcClient.Operation.SIGN,MpcClient.Algorithm.ECDSA_SECP256K1,List.of("a".repeat(64),"c".repeat(64)),message),null,null);
    }
    private ApprovalWithdrawalVerifiers.Verified verify(Fixture f,SigningCandidate c,ObjectNode facts){return verifier.verifyBatch(f.receipts(),keys().get(c.command().keyId()),keys(),c,now,evidence(facts));}
    private JsonNode evidence(JsonNode facts){return json.createObjectNode().put("chain","ETH").put("networkIdentity","eip155:1").set("facts",facts);}
    private ChainAddressRecord owner(UUID id,ECKey key){return ChainAddressRecord.builder().tenantId(tenant).mpcKeyId(id).chain("ETH").assetSymbol("ETH").address(address(key)).ownerAddress(address(key)).walletRole("HOT").build();}
    private RegisteredMpcKey registered(UUID id,ECKey key){return new RegisteredMpcKey(tenant,id,"EVM","ECDSA_SECP256K1",1,HexFormat.of().formatHex(key.getPubKey()),null,"ACTIVE");}
    private static String address(ECKey key){return "0x"+Keys.getAddress(Sign.publicFromPoint(key.decompress().getPubKey()));}
    private record Fixture(List<ApprovalRequest> receipts,Evm7702CollectionPayload prepared,Evm7702SigningPayload.Plan plan,ObjectNode facts) { }
}
