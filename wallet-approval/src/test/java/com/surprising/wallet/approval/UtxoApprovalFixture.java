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
import java.time.Instant;
import java.util.*;
import org.bitcoinj.crypto.ECKey;
import tools.jackson.databind.ObjectMapper;

class UtxoApprovalFixture {
    private static final String GENERATOR="0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798",GENESIS="a".repeat(64),TIP="b".repeat(64);
    private static final Instant NOW=Instant.parse("2026-11-01T08:00:00Z");
    private final ObjectMapper json=new ObjectMapper();
    private String publicKey(int multiple) { return HexFormat.of().formatHex(ECKey.fromPublicOnly(HexFormat.of().parseHex(GENERATOR)).getPubKeyPoint().multiply(BigInteger.valueOf(multiple)).getEncoded(true)); }
    final UUID tenant,batch=UUID.randomUUID(),first=UUID.randomUUID(),second=UUID.randomUUID(),change=UUID.randomUUID();
    int transactionId=42;
    final String chain;final Map<UUID,RegisteredMpcKey> keys=new HashMap<>();
    final List<ApprovalRequest> reviews=new ArrayList<>();
    UtxoSigningRequest request;MpcUtxoTransactionCodec.Unsigned unsigned;ApprovalUtxoRpcClient.Observation evidence;
    UtxoApprovalFixture(String chain) { this(chain,UUID.randomUUID()); }
    UtxoApprovalFixture(String chain,UUID tenant) {
        this.chain=chain;this.tenant=tenant;
        int i=1;for(var id:List.of(first,second,change))keys.put(id,new RegisteredMpcKey(tenant,id,chain,"ECDSA_SECP256K1",1,publicKey(i++),null,"ACTIVE"));
        var asset=new ApprovalAssetSnapshot(chain,"regtest",chain,chain,"utxo:"+GENESIS,null,8,8,new BigDecimal("0.01"));
        for(int n=0;n<2;n++) {
            var intent=new WithdrawalIntent(UUID.randomUUID(),tenant,"CW-"+batch+"-"+n,chain,"regtest",chain,address(n+4),new BigDecimal(n==0?"0.4":"0.6"),new BigDecimal("0.01"),"API",UUID.randomUUID(),"customer-"+n,"merchant-"+n,"TENANT_POOL");
            reviews.add(review(intent,asset));
        }
        var inputs=List.of(new UtxoSigningRequest.Input("c".repeat(64),0,address(1),new BigDecimal("0.8"),new UtxoSigningRequest.Key(1,1,0,null)),
                new UtxoSigningRequest.Input("d".repeat(64),1,address(2),new BigDecimal("0.7"),new UtxoSigningRequest.Key(2,1,0,null)));
        request=new UtxoSigningRequest(tenant,batch,new UtxoSigningRequest.Asset(chain,BitcoinLikeNetwork.REGTEST,1,8,0),UtxoSigningRequest.Operation.WITHDRAWAL,null,inputs,
                reviews.stream().map(r->new UtxoSigningRequest.Payment(r.intent().businessReference(),1,r.intent().recipient(),r.intent().amount(),new BigDecimal("0.1"))).toList(),address(3),2,1000);
        rebuild();
        var facts=new ArrayList<ApprovalUtxoRpcClient.Output>();
        for(var input:inputs)facts.add(new ApprovalUtxoRpcClient.Output(new ApprovalUtxoRpcClient.Outpoint(input.txId(),input.vout()),request.asset().atomic(input.amount()),script(input.address()),10,false));
        evidence=new ApprovalUtxoRpcClient.Observation(chain,"regtest",GENESIS,TIP,NOW,facts);
    }
    String address(int n) { return MpcUtxoTransactionCodec.address(chain,BitcoinLikeNetwork.REGTEST,publicKey(n)); }
    String script(String address) { return MpcUtxoTransactionCodec.outputScriptHex(chain,BitcoinLikeNetwork.REGTEST,address); }
    void rebuild() {
        var inputs=new ArrayList<MpcUtxoTransactionCodec.Input>();
        for(int i=0;i<request.inputs().size();i++) { var input=request.inputs().get(i);inputs.add(new MpcUtxoTransactionCodec.Input(input.txId(),input.vout(),request.asset().atomic(input.amount()),publicKey(i+1))); }
        unsigned=MpcUtxoTransactionCodec.prepare(chain,BitcoinLikeNetwork.REGTEST,inputs,request.payments().stream().map(p->new MpcUtxoTransactionCodec.Payment(p.address(),request.asset().atomic(p.amount()))).toList(),request.changeAddress(),request.feeRate(),request.dustThreshold());
    }
    SigningCandidate candidate(int index) {
        UUID key=index==0?first:second;var payload=new UtxoSigningPayload(new UtxoSigningTask(transactionId,request),unsigned,List.of(first,second),change);
        String message=HexFormat.of().formatHex(MpcUtxoTransactionCodec.digest(unsigned,index));
        var command=new MpcSignCommand(tenant,key,1,ChainType.valueOf(chain),MpcSignCommand.BusinessType.WITHDRAWAL,request.requestId().toString(),"input/"+index,MpcSignCommand.Purpose.TRANSACTION,"UTXO_MPC_JSON_V1",HexFormat.of().formatHex(json.writeValueAsBytes(payload)),message);
        var execution=new MpcClient.Request(UUID.randomUUID(),tenant,key,1,MpcClient.Operation.SIGN,MpcClient.Algorithm.ECDSA_SECP256K1,List.of("a".repeat(64),"c".repeat(64)),message);
        return new SigningCandidate(UUID.randomUUID(),reviews.stream().map(r->r.intent().id()).toList(),command,execution,null,null);
    }
    UtxoWithdrawalVerifier.Verified verify(int index) { return UtxoWithdrawalVerifier.verify(reviews,keys,candidate(index),evidence,json); }
    private ApprovalRequest review(WithdrawalIntent intent,ApprovalAssetSnapshot asset) { return new ApprovalRequest(intent,asset.bind(intent,json),asset,UUID.randomUUID(),1,List.of("ALL_WITHDRAWALS"),1,"APPROVED",false,NOW,NOW.plusSeconds(3600)); }
}
