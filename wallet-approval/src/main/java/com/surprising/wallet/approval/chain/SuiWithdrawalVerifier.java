package com.surprising.wallet.approval.chain;

import static com.surprising.wallet.approval.chain.WithdrawalWireChecks.*;
import com.surprising.wallet.approval.gateway.ApprovalChainRpcClient;
import com.surprising.wallet.approval.gateway.ApprovalUtxoRpcClient.Outpoint;
import com.surprising.wallet.approval.model.RegisteredMpcKey;
import com.surprising.wallet.common.approval.*;
import com.surprising.wallet.common.signing.*;
import com.surprising.wallet.sdk.signing.sui.*;
import com.google.protobuf.FieldMask;
import java.math.*;
import java.util.*;
import sui.rpc.v2.*;
import tools.jackson.databind.*;

/** Fixed PTB bytes plus live full-node owner/type/version/digest/balance checks for every principal and gas object. */
final class SuiWithdrawalVerifier {
    static JsonNode observe(ApprovalRequest review,SigningCandidate candidate,ApprovalChainRpcClient rpc,ObjectMapper json) {
        var p=payload(candidate,SuiSigningPayload.class,json);var u=p.unsigned();var facts=json.createObjectNode();
        try(var reads=rpc.grpc(review.assetSnapshot())) {
            var ledger=LedgerServiceGrpc.newBlockingStub(reads.channel()).withDeadline(reads.deadline());
            var info=ledger.getServiceInfo(LedgerServiceOuterClass.GetServiceInfoRequest.getDefaultInstance());
            require(info.hasChainId() && info.hasEpoch() && info.hasCheckpointHeight(),"Sui independent node lacks finalized network identity");
            facts.put("chainId",info.getChainId()).put("epoch",Long.toUnsignedString(info.getEpoch())).put("checkpoint",Long.toUnsignedString(info.getCheckpointHeight()));
            var epoch=ledger.getEpoch(LedgerServiceOuterClass.GetEpochRequest.newBuilder().setReadMask(FieldMask.newBuilder().addPaths("reference_gas_price")).build());
            require(epoch.hasEpoch() && epoch.getEpoch().hasReferenceGasPrice(),"Sui independent reference gas price missing");facts.put("gasPrice",Long.toUnsignedString(epoch.getEpoch().getReferenceGasPrice()));
            var objects=facts.putArray("objects");
            for(var coin:java.util.stream.Stream.concat(u.inputs().stream(),u.gasPayment().stream()).toList()) {
                // No version selector: reading an old historical version would not prove it is still spendable.
                var response=ledger.getObject(LedgerServiceOuterClass.GetObjectRequest.newBuilder().setObjectId(coin.objectId())
                        .setReadMask(FieldMask.newBuilder().addPaths("object_id").addPaths("version").addPaths("digest").addPaths("object_type").addPaths("owner").addPaths("balance")).build());
                require(response.hasObject(),"Sui input object is missing or spent");objects.add(Base64.getEncoder().encodeToString(response.getObject().toByteArray()));
            }
        }
        return facts;
    }
    static ApprovalWithdrawalVerifiers.Verified verify(ApprovalRequest review,RegisteredMpcKey key,SigningCandidate candidate,JsonNode facts,ObjectMapper json) {
        owner(review,key,candidate,MpcClient.Algorithm.ED25519,json);var p=payload(candidate,SuiSigningPayload.class,json);var asset=review.assetSnapshot();var u=p.unsigned();
        amount(review,p.symbol(),p.amount(),p.customerFee(),p.decimals());String nativeType=MpcSuiTransactionCodec.canonicalCoinType("0x2::sui::SUI");
        require(asset.nativeSymbol().equals("SUI") && asset.nativeDecimals()==9 && u.network().equals(asset.network())
                && u.publicKeyHex().equals(key.publicKeyHex()) && u.amountAtomic()==atomic(review).longValueExact()
                && u.recipient().equals(MpcSuiTransactionCodec.canonicalAddress(review.intent().recipient()))
                && u.coinType().equals(asset.tokenIdentifier()==null?nativeType:MpcSuiTransactionCodec.canonicalCoinType(asset.tokenIdentifier())),"Sui signed asset, principal, recipient, network or key differs");
        require(facts!=null && asset.networkIdentity().equals("sui:"+facts.path("chainId").asText()) && u.chainIdentifier().equals(facts.path("chainId").asText())
                && BigInteger.valueOf(u.gasPrice()).compareTo(integer(facts.path("gasPrice")))>=0,"Sui independent genesis or reference gas price differs");
        var expected=new LinkedHashMap<String,SuiCoin>();for(var coin:java.util.stream.Stream.concat(u.inputs().stream(),u.gasPayment().stream()).toList())expected.put(MpcSuiTransactionCodec.canonicalAddress(coin.objectId()),coin);
        var seen=new HashSet<String>();var gas=new HashSet<String>();u.gasPayment().forEach(c->gas.add(MpcSuiTransactionCodec.canonicalAddress(c.objectId())));
        require(facts.path("objects").isArray() && facts.path("objects").size()==expected.size(),"Sui independent object set differs");
        for(var bytes:facts.path("objects")) {
            ObjectOuterClass.Object object;
            try{object=ObjectOuterClass.Object.parseFrom(Base64.getDecoder().decode(bytes.asText()));}
            catch(com.google.protobuf.InvalidProtocolBufferException invalid){throw new IllegalArgumentException("invalid persisted Sui object fact",invalid);}
            String id=MpcSuiTransactionCodec.canonicalAddress(object.getObjectId());var coin=expected.get(id);
            String coinType=gas.contains(id)?nativeType:u.coinType();String expectedType="0x2::coin::Coin<"+coinType+">";
            require(coin!=null && seen.add(id) && object.hasOwner() && object.getOwner().getKind()==OwnerOuterClass.Owner.OwnerKind.ADDRESS
                    && MpcSuiTransactionCodec.canonicalAddress(object.getOwner().getAddress()).equals(u.sender())
                    && Long.toUnsignedString(object.getVersion()).equals(coin.version()) && object.getDigest().equals(coin.digest())
                    && object.hasBalance() && new BigInteger(Long.toUnsignedString(object.getBalance())).equals(coin.balance().toBigIntegerExact())
                    && canonicalObjectType(object.getObjectType()).equals(canonicalObjectType(expectedType)),"Sui live input ownership, type, version, digest or balance differs");
        }
        message(candidate,MpcSuiTransactionCodec.signingBytes(u));var points=expected.entrySet().stream()
                .map(entry->new Outpoint(SigningAuthorization.hash((entry.getKey()+":"+entry.getValue().version()).getBytes(java.nio.charset.StandardCharsets.US_ASCII)),0))
                .sorted(Comparator.comparing(Outpoint::txId)).toList();
        return new ApprovalWithdrawalVerifiers.Verified("SUI:"+asset.networkIdentity()+":"+SigningAuthorization.hash(json.writeValueAsBytes(points)),fee(review,BigInteger.valueOf(u.gasBudget())),true,points);
    }
    private static String canonicalObjectType(String type) {
        int open=type.indexOf('<');require(open>0 && type.endsWith(">"),"Sui input is not a typed Coin object");
        String outer=type.substring(0,open);String[] parts=outer.split("::",-1);require(parts.length==3 && parts[1].equals("coin") && parts[2].equals("Coin"),"Sui input is not a Coin");
        return MpcSuiTransactionCodec.canonicalAddress(parts[0])+"::coin::Coin<"+MpcSuiTransactionCodec.canonicalCoinType(type.substring(open+1,type.length()-1))+">";
    }
}
