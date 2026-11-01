package com.surprising.wallet.approval.chain;

import static com.surprising.wallet.approval.chain.WithdrawalWireChecks.*;
import com.surprising.wallet.approval.gateway.ApprovalChainRpcClient;
import com.surprising.wallet.approval.model.RegisteredMpcKey;
import com.surprising.wallet.common.approval.*;
import com.surprising.wallet.common.signing.*;
import com.surprising.wallet.sdk.signing.near.MpcNearTransactionCodec;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.*;
import tools.jackson.databind.*;

/** One Gas-funded NEP-145 registration for the exact independently admitted parent token payment. */
final class NearRegistrationVerifier {
    static JsonNode observe(ApprovalRequest operation,SigningCandidate candidate,ApprovalChainRpcClient rpc,ObjectMapper json) {
        var p=payload(candidate,NearRegistrationPayload.class,json);var u=p.unsigned();var reads=rpc.open(operation.assetSnapshot(),"DEFAULT");var result=json.createObjectNode();
        result.set("genesis",reads.rpc("block",Map.of("block_id",0)));result.set("reference",reads.rpc("block",Map.of("block_id",u.blockHash())));result.set("final",reads.rpc("block",Map.of("finality","final")));
        result.set("accessKey",reads.rpc("query",Map.of("request_type","view_access_key","finality","final","account_id",u.sender(),"public_key",MpcNearTransactionCodec.publicKey(u))));
        result.set("gasPrice",reads.rpc("gas_price",List.of(u.blockHash())));result.put("maximumGasPrice",reads.parameter("maximumGasPriceYocto"));
        result.set("storageBalance",view(reads,u.contract(),"storage_balance_of",Map.of("account_id",u.beneficiary()),json));
        result.set("storageBounds",view(reads,u.contract(),"storage_balance_bounds",Map.of(),json));
        result.set("parentBalance",view(reads,u.contract(),"ft_balance_of",Map.of("account_id",p.parentSender()),json));return result;
    }
    static ApprovalWithdrawalVerifiers.Verified verify(ApprovalRequest operation,RegisteredMpcKey key,SigningCandidate candidate,JsonNode facts,ObjectMapper json) {
        owner(operation,key,candidate,MpcClient.Algorithm.ED25519,json);var p=payload(candidate,NearRegistrationPayload.class,json);var u=p.unsigned();var asset=operation.assetSnapshot();
        amount(operation,p.payment().symbol(),p.payment().amount(),p.payment().customerFee(),p.tokenDecimals());
        var reference=AutomaticParentReference.decode(candidate.command().tenantId(),"NEAR",candidate.command().businessReference());
        require(candidate.command().businessType()==MpcSignCommand.BusinessType.FUNDING && operation.intent().source().equals("SYSTEM")
                && p.parent().tenantId().equals(candidate.command().tenantId()) && reference.matches(p.parent().kind().name(),p.parent().reference())
                && asset.nativeSymbol().equals("NEAR") && asset.nativeDecimals()==24 && asset.tokenIdentifier()!=null && asset.tokenIdentifier().equals(u.contract())
                && p.payment().recipient().equals(u.beneficiary()) && u.beneficiary().equals(operation.intent().recipient())
                && u.network().equals(asset.network()) && u.publicKeyHex().equals(key.publicKeyHex()) && facts!=null,"NEP-145 original parent, Gas key, network, contract or beneficiary differs");
        require(asset.networkIdentity().equals("near:"+u.genesisHash()) && facts.path("genesis").path("header").path("hash").asText().equals(u.genesisHash())
                && facts.path("reference").path("header").path("hash").asText().equals(u.blockHash()) && integer(facts.path("reference").path("header").path("height")).longValueExact()==u.blockHeight()
                && integer(facts.path("final").path("header").path("height")).longValueExact()<u.validUntilHeight() && u.validUntilHeight()<=Math.addExact(u.blockHeight(),86400)
                && facts.path("accessKey").path("permission").asText().equals("FullAccess") && integer(facts.path("accessKey").path("nonce")).compareTo(BigInteger.valueOf(u.nonce()))<0,"NEP-145 original nonce/reference differs from independent chain facts");
        require(facts.path("storageBalance").isNull() && facts.path("storageBounds").isObject()
                && integer(facts.path("storageBounds").path("min")).equals(u.depositAtomic()) && u.depositAtomic().signum()>0
                && (facts.path("storageBounds").path("max").isNull() || integer(facts.path("storageBounds").path("max")).compareTo(u.depositAtomic())>=0)
                && integer(facts.path("parentBalance")).compareTo(atomic(operation))>=0,"NEP-145 deposit is not the actual missing registration/minimum or parent principal is absent");
        BigInteger maximumPrice=integer(facts.path("maximumGasPrice"));require(maximumPrice.signum()>0 && integer(facts.path("gasPrice").path("gas_price")).compareTo(maximumPrice)<=0
                && p.maximumFeeYocto()!=null && maximumPrice.multiply(BigInteger.valueOf(Math.addExact(u.gas(),10_000_000_000_000L))).compareTo(p.maximumFeeYocto())<=0,"NEP-145 conservative Gas exposure exceeds original fee budget");
        BigInteger expenditure=u.depositAtomic().add(p.maximumFeeYocto());message(candidate,MpcNearTransactionCodec.signingBytes(u));
        return new ApprovalWithdrawalVerifiers.Verified("NEAR:"+asset.networkIdentity()+":"+u.sender()+":"+u.nonce(),fee(operation,expenditure),true,List.of(),true);
    }
    private static JsonNode view(ApprovalChainRpcClient.Reads reads,String contract,String method,Map<String,?> args,ObjectMapper json) {
        var response=reads.rpc("query",Map.of("request_type","call_function","finality","final","account_id",contract,"method_name",method,"args_base64",Base64.getEncoder().encodeToString(json.writeValueAsBytes(args))));
        var bytes=response.path("result");require(bytes.isArray() && bytes.size()>0 && bytes.size()<=8192,"independent NEAR view result is absent or oversized");var out=new ByteArrayOutputStream();
        for(var value:bytes){require(value.isIntegralNumber() && value.canConvertToInt() && value.asInt()>=0 && value.asInt()<=255,"invalid independent NEAR view byte");out.write(value.asInt());}
        return json.readTree(out.toByteArray());
    }
}
