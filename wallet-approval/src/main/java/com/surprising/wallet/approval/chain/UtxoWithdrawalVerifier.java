package com.surprising.wallet.approval.chain;

import com.surprising.wallet.approval.gateway.ApprovalUtxoRpcClient;
import com.surprising.wallet.approval.model.RegisteredMpcKey;
import com.surprising.wallet.common.approval.*;
import com.surprising.wallet.common.signing.*;
import com.surprising.wallet.sdk.signing.MpcUtxoTransactionCodec;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.*;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

/** Pure batch verification with independent node evidence and registered input/change keys. Not routed to grant issuance yet. */
public final class UtxoWithdrawalVerifier {
    private UtxoWithdrawalVerifier() { }
    public static Verified verify(List<ApprovalRequest> reviewed,Map<UUID,RegisteredMpcKey> registered,SigningCandidate candidate,
            ApprovalUtxoRpcClient.Observation evidence,ObjectMapper json) {
        return verify(reviewed,registered,candidate,evidence,json,null);
    }
    /** collectionRoot is supplied only after the independent same-input replacement verifier succeeds. */
    public static Verified verify(List<ApprovalRequest> reviewed,Map<UUID,RegisteredMpcKey> registered,SigningCandidate candidate,
            ApprovalUtxoRpcClient.Observation evidence,ObjectMapper json,UtxoSigningPayload collectionRoot) {
        var command=candidate.command();var execution=candidate.execution();
        boolean manual=command.businessType()==MpcSignCommand.BusinessType.WITHDRAWAL;
        require(command.chain().isBitcoinLike() && (manual || command.businessType()==MpcSignCommand.BusinessType.COLLECTION) && command.purpose()==MpcSignCommand.Purpose.TRANSACTION
                && command.payloadEncoding().equals("UTXO_MPC_JSON_V1") && execution.algorithm()==MpcClient.Algorithm.ECDSA_SECP256K1
                && execution.operation()==MpcClient.Operation.SIGN,"unsupported UTXO signature family");
        var payload=UtxoSigningPayload.decode(command,json);
        var task=payload.task();var request=task.request();var unsigned=payload.unsigned();
        require(request.operation().name().equals(command.businessType().name()) && request.tenantId().equals(command.tenantId()) && request.requestId().toString().equals(command.businessReference())
                && request.asset().chain().equals(command.chain().name()) && request.asset().decimals()==8 && unsigned.chain().equals(request.asset().chain())
                && unsigned.network()==request.asset().network() && request.inputs().size()==unsigned.inputs().size() && request.inputs().size()==payload.keyIds().size()
                && request.inputs().size()<=1000 && request.payments().size()<=100,"UTXO payload differs from its tenant/batch/input domain");
        request.validateForSigning(); // Apply count/precision bounds before any caller-derived amount arithmetic.
        require(!reviewed.isEmpty() && reviewed.size()==request.payments().size() && (manual?candidate.approvalRequestIds().size()==reviewed.size()
                && new HashSet<>(candidate.approvalRequestIds()).equals(reviewed.stream().map(r->r.intent().id()).collect(java.util.stream.Collectors.toSet()))
                :candidate.approvalRequestIds().isEmpty() && reviewed.stream().allMatch(r->r.intent().source().equals("SYSTEM"))),"UTXO batch approval or independent operating set differs");
        var asset=reviewed.getFirst().assetSnapshot();String chain=asset.chain(),network=asset.network();
        require(asset.chain().equals(command.chain().name()) && asset.symbol().equals(chain) && asset.nativeSymbol().equals(chain) && asset.tokenIdentifier()==null
                && unsigned.network()==com.surprising.wallet.sdk.bitcoinj.core.BitcoinLikeNetwork.parse(network)
                && asset.decimals()==8 && asset.nativeDecimals()==8 && asset.networkIdentity().equals("utxo:"+evidence.genesisHash())
                && evidence.chain().equals(chain) && evidence.network().equals(network) && evidence.outputs().size()==request.inputs().size(),"UTXO independent asset or node domain differs");
        var byReference=new HashMap<String,ApprovalRequest>();BigInteger reviewedFee=BigInteger.ZERO;
        for(var approval:reviewed) {
            require(approval.status().equals("APPROVED") && approval.intent().tenantId().equals(command.tenantId()) && asset.equals(approval.assetSnapshot())
                    && approval.intentHash().equals(asset.bind(approval.intent(),json)) && byReference.put(approval.intent().businessReference(),approval)==null,"UTXO reviewed intent/asset/owner differs");
            reviewedFee=reviewedFee.add(approval.intent().maximumNetworkFee().movePointRight(8).toBigIntegerExact());
        }
        var references=new HashSet<String>();
        for(var payment:request.payments()) {
            var approval=byReference.get(payment.reference());
            var approvedAmount=approval==null?null:approval.intent().amount();
            if(!manual && collectionRoot!=null && approvedAmount!=null)approvedAmount=approvedAmount.subtract(java.math.BigDecimal.valueOf(unsigned.feeAtomic()-collectionRoot.unsigned().feeAtomic(),8));
            require(approval!=null && references.add(payment.reference()) && payment.amount().compareTo(approvedAmount)==0
                    && MpcUtxoTransactionCodec.outputScriptHex(chain,unsigned.network(),payment.address())
                        .equals(MpcUtxoTransactionCodec.outputScriptHex(chain,unsigned.network(),approval.intent().recipient())),"UTXO payment differs from its approved recipient/amount");
        }
        var change=key(registered,payload.changeKeyId(),command.tenantId(),chain);
        require(script(chain,unsigned,request.changeAddress()).equals(script(chain,unsigned,MpcUtxoTransactionCodec.address(chain,unsigned.network(),change.publicKeyHex()))),"UTXO change is not controlled by the independent tenant key");
        var observed=new HashSet<ApprovalUtxoRpcClient.Outpoint>();
        for(int i=0;i<request.inputs().size();i++) {
            var input=request.inputs().get(i);var wire=unsigned.inputs().get(i);var fact=evidence.outputs().get(i);
            var key=key(registered,payload.keyIds().get(i),command.tenantId(),chain);
            String ownedScript=script(chain,unsigned,MpcUtxoTransactionCodec.address(chain,unsigned.network(),key.publicKeyHex()));
            require(input.key().redeemScript()==null && fact.outpoint().txId().equals(input.txId().toLowerCase(Locale.ROOT)) && fact.outpoint().index()==input.vout()
                    && observed.add(fact.outpoint()) && wire.txId().equals(fact.outpoint().txId()) && wire.outputIndex()==fact.outpoint().index()
                    && fact.amountAtomic()==request.asset().atomic(input.amount()) && fact.amountAtomic()==wire.valueAtomic()
                    && key.publicKeyHex().equals(wire.publicKeyHex()) && ownedScript.equals(fact.scriptPubKeyHex()) && ownedScript.equals(script(chain,unsigned,input.address())),
                    "UTXO input is not the independently observed output owned by its registered tenant key");
        }
        int index=-1;for(int i=0;i<request.inputs().size();i++)if(command.signingSlot().equals("input/"+i))index=i;
        require(index>=0 && payload.keyIds().get(index).equals(command.keyId()) && key(registered,command.keyId(),command.tenantId(),chain).epoch()==command.keyEpoch(),"UTXO input slot/key epoch differs");
        var rebuilt=MpcUtxoTransactionCodec.prepare(chain,unsigned.network(),unsigned.inputs(),request.payments().stream()
                .map(p->new MpcUtxoTransactionCodec.Payment(p.address(),request.asset().atomic(p.amount()))).toList(),request.changeAddress(),request.feeRate(),request.dustThreshold());
        require(unsigned.equals(rebuilt) && HexFormat.of().formatHex(MpcUtxoTransactionCodec.digest(unsigned,index)).equals(execution.messageHex()),"UTXO actual wire or ALL-input signature digest differs from the approved canonical batch");
        BigInteger fee=BigInteger.valueOf(unsigned.feeAtomic());
        require(fee.signum()>0 && fee.compareTo(reviewedFee)<=0 && fee.compareTo(asset.networkFeeLimit().movePointRight(8).toBigIntegerExact())<=0,"UTXO batch fee exceeds the reviewed or independent fee ceiling");
        var points=observed.stream().sorted(Comparator.comparing(ApprovalUtxoRpcClient.Outpoint::txId).thenComparingInt(ApprovalUtxoRpcClient.Outpoint::index)).toList();
        String manifest=points.stream().map(p->p.txId()+":"+p.index()).collect(java.util.stream.Collectors.joining("\n"));
        return new Verified("UTXO:"+asset.networkIdentity()+":"+SigningAuthorization.hash(manifest.getBytes(StandardCharsets.US_ASCII)),fee,points);
    }
    private static RegisteredMpcKey key(Map<UUID,RegisteredMpcKey> registered,UUID id,UUID tenant,String group) {
        var key=registered.get(id);require(key!=null && key.keyId().equals(id) && key.tenantId().equals(tenant) && key.keyGroup().equals(group)
                && key.state().equals("ACTIVE") && key.algorithm().equals("ECDSA_SECP256K1") && key.publicKeyHex().matches("0[23][0-9a-f]{64}"),"UTXO independent active input/change key required");return key;
    }
    private static String script(String chain,MpcUtxoTransactionCodec.Unsigned unsigned,String address) { return MpcUtxoTransactionCodec.outputScriptHex(chain,unsigned.network(),address); }
    private static void require(boolean value,String message) { if(!value)throw new IllegalArgumentException(message); }
    public record Verified(String spendDomain,BigInteger maximumFeeAtomic,List<ApprovalUtxoRpcClient.Outpoint> inputs) { }
}
