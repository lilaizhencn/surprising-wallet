package com.surprising.wallet.approval;

import com.surprising.wallet.approval.chain.*;
import com.surprising.wallet.approval.gateway.ApprovalUtxoRpcClient;
import com.surprising.wallet.approval.model.RegisteredMpcKey;
import com.surprising.wallet.common.approval.*;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.signing.*;
import com.surprising.wallet.sdk.bitcoinj.core.BitcoinLikeNetwork;
import com.surprising.wallet.sdk.signing.MpcUtxoTransactionCodec;
import java.math.*;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.*;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.crypto.ECKey;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

class UtxoWithdrawalVerifierTest {
    private static final String GENERATOR="0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798",GENESIS="a".repeat(64),TIP="b".repeat(64);
    private static final Instant NOW=Instant.parse("2026-11-01T08:00:00Z");
    private final ObjectMapper json=new ObjectMapper();
    private String publicKey(int multiple) { return HexFormat.of().formatHex(ECKey.fromPublicOnly(HexFormat.of().parseHex(GENERATOR)).getPubKeyPoint().multiply(BigInteger.valueOf(multiple)).getEncoded(true)); }
    private ApprovalRequest review(WithdrawalIntent intent,ApprovalAssetSnapshot asset) { return new ApprovalRequest(intent,asset.bind(intent,json),asset,UUID.randomUUID(),1,List.of("ALL_WITHDRAWALS"),1,"APPROVED",false,NOW,NOW.plusSeconds(3600)); }
    @Test void everyUtxoFamilyVerifiesEachInputAgainstOneWholeReviewedBatch() {
        for(String chain:List.of("BTC","LTC","BCH","DOGE")) {
            var f=new UtxoApprovalFixture(chain);var first=f.verify(0);var second=f.verify(1);
            assertEquals(first,second);assertEquals(2,first.inputs().size());assertEquals(BigInteger.valueOf(f.unsigned.feeAtomic()),first.maximumFeeAtomic());
            assertTrue(new ApprovalWithdrawalVerifiers(json,org.mockito.Mockito.mock(com.surprising.wallet.approval.gateway.ApprovalChainRpcClient.class)).supports(f.candidate(0).command()));
        }
    }
    @Test void substitutedRecipientAmountOrUnownedChangeFailsEvenWithFreshCanonicalBytesAndDigest() {
        for(String attack:List.of("recipient","amount","change")) {
            var f=new UtxoApprovalFixture("BTC");var r=f.request;var payments=new ArrayList<>(r.payments());var first=payments.getFirst();
            if(!attack.equals("change"))payments.set(0,new UtxoSigningRequest.Payment(first.reference(),first.userId(),attack.equals("recipient")?f.address(6):first.address(),attack.equals("amount")?first.amount().add(new BigDecimal("0.01")):first.amount(),first.customerFee()));
            f.request=new UtxoSigningRequest(r.tenantId(),r.requestId(),r.asset(),r.operation(),r.collectionNo(),r.inputs(),payments,attack.equals("change")?f.address(6):r.changeAddress(),r.feeRate(),r.dustThreshold());f.rebuild();
            assertThrows(IllegalArgumentException.class,()->f.verify(0),attack);
        }
    }
    @Test void callerInputValuesKeysAndScriptsCannotReplaceIndependentlyObservedOutputs() {
        for(String attack:List.of("amount","script","outpoint","network","genesis")) {
            var f=new UtxoApprovalFixture("BTC");var facts=new ArrayList<>(f.evidence.outputs());var first=facts.getFirst();
            facts.set(0,new ApprovalUtxoRpcClient.Output(attack.equals("outpoint")?new ApprovalUtxoRpcClient.Outpoint("e".repeat(64),0):first.outpoint(),first.amountAtomic()+(attack.equals("amount")?1:0),attack.equals("script")?f.script(f.address(6)):first.scriptPubKeyHex(),10,false));
            f.evidence=new ApprovalUtxoRpcClient.Observation(f.chain,attack.equals("network")?"mainnet":"regtest",attack.equals("genesis")?"f".repeat(64):GENESIS,TIP,NOW,facts);
            assertThrows(IllegalArgumentException.class,()->f.verify(0),attack);
        }
    }
    @Test void foreignDisabledMissingInputOrChangeKeysCannotAuthorizeTheBatch() {
        for(String attack:List.of("foreign","disabled","missing-input","missing-change")) {
            var f=new UtxoApprovalFixture("BTC");var original=f.keys.get(f.first);
            if(attack.equals("missing-input"))f.keys.remove(f.first);
            else if(attack.equals("missing-change"))f.keys.remove(f.change);
            else f.keys.put(f.first,new RegisteredMpcKey(attack.equals("foreign")?UUID.randomUUID():f.tenant,f.first,"BTC","ECDSA_SECP256K1",1,original.publicKeyHex(),null,attack.equals("disabled")?"DISABLED":"ACTIVE"));
            assertThrows(IllegalArgumentException.class,()->f.verify(0),attack);
        }
    }
    @Test void alteredWireUnknownPayloadFieldsAndWrongInputDigestAreDenied() {
        var f=new UtxoApprovalFixture("BTC");var tx=Transaction.read(ByteBuffer.wrap(HexFormat.of().parseHex(f.unsigned.transactionHex())));tx.setLockTime(12345);
        f.unsigned=new MpcUtxoTransactionCodec.Unsigned("BTC",BitcoinLikeNetwork.REGTEST,HexFormat.of().formatHex(tx.bitcoinSerialize()),f.unsigned.inputs(),f.unsigned.feeAtomic(),f.unsigned.estimatedVBytes());
        assertThrows(IllegalArgumentException.class,()->f.verify(0));
        var original=new UtxoApprovalFixture("BTC");var candidate=original.candidate(0);var c=candidate.command();
        var body=json.readTree(HexFormat.of().parseHex(c.payloadHex())).asObject();body.put("arbitraryOutputs","injected");
        var changed=new MpcSignCommand(c.tenantId(),c.keyId(),c.keyEpoch(),c.chain(),c.businessType(),c.businessReference(),c.signingSlot(),c.purpose(),c.payloadEncoding(),HexFormat.of().formatHex(json.writeValueAsBytes(body)),c.preimageHex());
        assertThrows(RuntimeException.class,()->UtxoWithdrawalVerifier.verify(original.reviews,original.keys,new SigningCandidate(candidate.signingRequestId(),candidate.approvalRequestIds(),changed,candidate.execution(),null,null),original.evidence,json));
        var wrong=original.candidate(1);var w=wrong.command();
        var wrongSlot=new MpcSignCommand(w.tenantId(),w.keyId(),w.keyEpoch(),w.chain(),w.businessType(),w.businessReference(),"input/0",w.purpose(),w.payloadEncoding(),w.payloadHex(),w.preimageHex());
        assertThrows(IllegalArgumentException.class,()->UtxoWithdrawalVerifier.verify(original.reviews,original.keys,new SigningCandidate(wrong.signingRequestId(),wrong.approvalRequestIds(),wrongSlot,wrong.execution(),null,null),original.evidence,json));
    }
    @Test void everyPaymentNeedsItsOwnApprovedIntentAndFeeCapsApplyToTheWholeBatch() {
        var missing=new UtxoApprovalFixture("BTC");var candidate=missing.candidate(0);
        var one=new SigningCandidate(candidate.signingRequestId(),List.of(candidate.approvalRequestIds().getFirst()),candidate.command(),candidate.execution(),null,null);
        assertThrows(IllegalArgumentException.class,()->UtxoWithdrawalVerifier.verify(missing.reviews,missing.keys,one,missing.evidence,json));
        var lowFee=new UtxoApprovalFixture("BTC");
        for(int i=0;i<lowFee.reviews.size();i++) {var r=lowFee.reviews.get(i);var in=r.intent();
            var limited=new WithdrawalIntent(in.id(),in.tenantId(),in.businessReference(),in.chain(),in.network(),in.assetSymbol(),in.recipient(),in.amount(),new BigDecimal("0.00000001"),in.source(),in.initiatorId(),in.subject(),in.externalReference(),in.fundingSource());lowFee.reviews.set(i,review(limited,r.assetSnapshot()));}
        assertThrows(IllegalArgumentException.class,()->lowFee.verify(0));
    }
    @Test void independentTransactionFeeCeilingStillAppliesWhenCombinedReviewedContributionsWouldCoverMore() {
        var f=new UtxoApprovalFixture("BTC");var r=f.request;
        f.request=new UtxoSigningRequest(r.tenantId(),r.requestId(),r.asset(),r.operation(),r.collectionNo(),r.inputs(),r.payments(),r.changeAddress(),3,r.dustThreshold());f.rebuild();
        var cap=new BigDecimal("0.000006");
        assertTrue(f.unsigned.feeAtomic()>600 && f.unsigned.feeAtomic()<=1200);
        for(int i=0;i<f.reviews.size();i++) {var in=f.reviews.get(i).intent();
            var asset=new ApprovalAssetSnapshot("BTC","regtest","BTC","BTC","utxo:"+GENESIS,null,8,8,cap);
            var intent=new WithdrawalIntent(in.id(),in.tenantId(),in.businessReference(),in.chain(),in.network(),in.assetSymbol(),in.recipient(),in.amount(),cap,in.source(),in.initiatorId(),in.subject(),in.externalReference(),in.fundingSource());
            f.reviews.set(i,review(intent,asset));
        }
        assertThrows(IllegalArgumentException.class,()->f.verify(0));
    }
    @Test void nullPayloadAndUnboundedNativePrecisionAreRejectedBeforeAnyAmountComputation() {
        var f=new UtxoApprovalFixture("BTC");var original=f.candidate(0);var c=original.command();
        var bad=json.readTree(HexFormat.of().parseHex(c.payloadHex())).asObject();bad.path("task").path("request").path("asset").asObject().put("decimals",Integer.MAX_VALUE);
        for(String body:List.of("null",json.writeValueAsString(bad))) {
            var command=new MpcSignCommand(c.tenantId(),c.keyId(),c.keyEpoch(),c.chain(),c.businessType(),c.businessReference(),c.signingSlot(),c.purpose(),c.payloadEncoding(),HexFormat.of().formatHex(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)),c.preimageHex());
            var candidate=new SigningCandidate(original.signingRequestId(),original.approvalRequestIds(),command,original.execution(),null,null);
            assertThrows(RuntimeException.class,()->UtxoWithdrawalVerifier.verify(f.reviews,f.keys,candidate,f.evidence,json));
        }
    }
}
