package com.surprising.wallet.approval;

import com.google.protobuf.*;
import com.surprising.wallet.approval.chain.ApprovalWithdrawalVerifiers;
import com.surprising.wallet.approval.gateway.ApprovalChainRpcClient;
import com.surprising.wallet.approval.model.RegisteredMpcKey;
import com.surprising.wallet.common.approval.*;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.signing.*;
import com.surprising.wallet.sdk.signing.tron.*;
import java.math.*;
import java.time.Instant;
import java.util.*;
import org.bitcoinj.crypto.ECKey;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.tron.TronWalletApi;
import org.tron.trident.core.ApiWrapper;
import org.tron.trident.proto.Chain;
import org.tron.trident.proto.Contract;
import org.web3j.utils.Numeric;
import tools.jackson.databind.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class TronWithdrawalVerifierTest {
    private final ObjectMapper json=new ObjectMapper();private final Instant now=Instant.parse("2026-11-02T00:00:00Z");
    private final UUID tenant=UUID.randomUUID(),keyId=UUID.randomUUID();private final ECKey joint=new ECKey();
    private final String owner=TronWalletApi.getAddress(joint.getPubKey()),recipient=TronWalletApi.getAddress(new ECKey().getPubKey()),token=TronWalletApi.getAddress(new ECKey().getPubKey());
    private final RegisteredMpcKey key=new RegisteredMpcKey(tenant,keyId,"TRON","ECDSA_SECP256K1",1,HexFormat.of().formatHex(joint.getPubKey()),null,"ACTIVE");
    private final ApprovalWithdrawalVerifiers verifier=new ApprovalWithdrawalVerifiers(json,mock(ApprovalChainRpcClient.class));
    @ParameterizedTest @ValueSource(booleans={false,true})
    void actualTransferMemoAndAllWireFieldsMustMatchIndependentReview(boolean issued) {
        var review=review(issued);var valid=candidate(review,issued,"CW-fixed",recipient,1_000_000);
        var approved=verifier.verifyBatch(List.of(review),key,Map.of(keyId,key),valid,now,evidence());
        assertTrue(approved.maximumFeeAtomic().signum()>0);assertFalse(approved.supportsExactRecovery());
        assertThrows(IllegalArgumentException.class,()->verifier.verifyBatch(List.of(review),key,Map.of(keyId,key),candidate(review,issued,"CW-foreign",recipient,1_000_000),now,evidence()));
        assertThrows(IllegalArgumentException.class,()->verifier.verifyBatch(List.of(review),key,Map.of(keyId,key),candidate(review,issued,"CW-fixed",owner,1_000_000),now,evidence()));
        assertThrows(IllegalArgumentException.class,()->verifier.verifyBatch(List.of(review),key,Map.of(keyId,key),candidate(review,issued,"CW-fixed",recipient,1_000_001),now,evidence()));
        var expired=now.plusSeconds(61);assertThrows(IllegalArgumentException.class,()->verifier.verifyBatch(List.of(review),key,Map.of(keyId,key),valid,expired,evidence()));
    }
    private ApprovalRequest review(boolean issued) {
        var asset=new ApprovalAssetSnapshot("TRON","nile",issued?"USDT":"TRX","TRX","tron:"+"a".repeat(64),issued?token:null,6,6,BigDecimal.ONE);
        var intent=new WithdrawalIntent(UUID.randomUUID(),tenant,"CW-fixed","TRON","nile",asset.symbol(),recipient,BigDecimal.ONE,BigDecimal.ONE,"API",UUID.randomUUID(),"customer","merchant","ADDRESS");
        return new ApprovalRequest(intent,asset.bind(intent,json),asset,UUID.randomUUID(),1,List.of("ALL_WITHDRAWALS"),1,"APPROVED",false,now,now.plusSeconds(3600));
    }
    private SigningCandidate candidate(ApprovalRequest review,boolean issued,String memoReference,String destination,long atomic) {
        var action=Chain.Transaction.Contract.newBuilder();long fee=issued?100_000:0;
        if(issued) {
            String data="a9059cbb"+"0".repeat(24)+TronAddressCodec.base58ToHex(destination).substring(2)+Numeric.toHexStringNoPrefixZeroPadded(BigInteger.valueOf(atomic),64);
            action.setType(Chain.Transaction.Contract.ContractType.TriggerSmartContract).setParameter(Any.pack(Contract.TriggerSmartContract.newBuilder()
                    .setOwnerAddress(ApiWrapper.parseAddress(owner)).setContractAddress(ApiWrapper.parseAddress(token)).setData(ByteString.copyFrom(HexFormat.of().parseHex(data))).build()));
        } else action.setType(Chain.Transaction.Contract.ContractType.TransferContract).setParameter(Any.pack(Contract.TransferContract.newBuilder()
                .setOwnerAddress(ApiWrapper.parseAddress(owner)).setToAddress(ApiWrapper.parseAddress(destination)).setAmount(atomic).build()));
        var transaction=Chain.Transaction.newBuilder().setRawData(Chain.Transaction.raw.newBuilder().setTimestamp(now.toEpochMilli()).setExpiration(now.plusSeconds(60).toEpochMilli())
                .setRefBlockBytes(ByteString.copyFrom(new byte[2])).setRefBlockHash(ByteString.copyFrom(new byte[8]))
                .setData(ByteString.copyFrom(MpcTronTransactionCodec.businessMemo(tenant,"WITHDRAWAL",memoReference))).setFeeLimit(fee).addContract(action)).build();
        var unsigned=MpcTronTransactionCodec.prepare(transaction,owner,destination,BigInteger.valueOf(atomic),issued?token:null,fee);
        var payload=new TronSigningPayload(review.assetSnapshot().symbol(),BigDecimal.ONE,BigDecimal.ZERO,6,unsigned);String digest=HexFormat.of().formatHex(MpcTronTransactionCodec.digest(unsigned));
        var command=new MpcSignCommand(tenant,keyId,1,ChainType.TRON,MpcSignCommand.BusinessType.WITHDRAWAL,"CW-fixed","transaction",MpcSignCommand.Purpose.TRANSACTION,"TRON_TX_JSON",HexFormat.of().formatHex(json.writeValueAsBytes(payload)),digest);
        var execution=new MpcClient.Request(UUID.randomUUID(),tenant,keyId,1,MpcClient.Operation.SIGN,MpcClient.Algorithm.ECDSA_SECP256K1,List.of("a".repeat(64),"c".repeat(64)),digest);
        return new SigningCandidate(UUID.randomUUID(),List.of(review.intent().id()),command,execution,null,null);
    }
    private JsonNode evidence() {
        var facts=json.createObjectNode();facts.putObject("genesis").put("blockID","a".repeat(64));facts.putObject("reference").put("blockID","0".repeat(64));
        var parameters=facts.putObject("parameters").putArray("chainParameter");
        for(String name:List.of("getTransactionFee","getCreateNewAccountFeeInSystemContract","getCreateAccountFee"))parameters.addObject().put("key",name).put("value",100);
        return json.createObjectNode().put("chain","TRON").put("networkIdentity","tron:"+"a".repeat(64)).set("facts",facts);
    }
}
