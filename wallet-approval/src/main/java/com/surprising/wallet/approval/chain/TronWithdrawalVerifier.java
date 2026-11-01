package com.surprising.wallet.approval.chain;

import static com.surprising.wallet.approval.chain.WithdrawalWireChecks.*;
import com.surprising.wallet.approval.gateway.ApprovalChainRpcClient;
import com.surprising.wallet.approval.model.RegisteredMpcKey;
import com.surprising.wallet.common.approval.*;
import com.surprising.wallet.common.signing.*;
import com.surprising.wallet.sdk.signing.tron.MpcTronTransactionCodec;
import java.math.BigInteger;
import java.time.Instant;
import java.util.*;
import tools.jackson.databind.*;

/** Exact protobuf transfer, independently checked TAPOS and native expenditure policy. No renewal of a timestamp/ref block. */
final class TronWithdrawalVerifier {
    static JsonNode observe(ApprovalRequest review,SigningCandidate candidate,ApprovalChainRpcClient rpc,ObjectMapper json) {
        var p=payload(candidate,TronSigningPayload.class,json);var raw=MpcTronTransactionCodec.restore(p.unsigned()).getRawData();var reads=rpc.open(review.assetSnapshot(),"DEFAULT");
        var facts=json.createObjectNode();facts.set("genesis",reads.rest("/wallet/getblockbynum",Map.of("num",0)));
        var head=reads.rest("/wallet/getnowblock",Map.of());facts.set("head",head);
        long height=integer(head.path("block_header").path("raw_data").path("number")).longValueExact();byte[] ref=raw.getRefBlockBytes().toByteArray();
        long original=(height&~0xffffL)|((ref[0]&255)<<8)|(ref[1]&255);if(original>height)original-=65536;
        require(original>=0,"TRON reference height predates genesis");facts.set("reference",reads.rest("/wallet/getblockbynum",Map.of("num",original)));
        facts.set("parameters",reads.rest("/wallet/getchainparameters",Map.of()));return facts;
    }
    static ApprovalWithdrawalVerifiers.Verified verify(ApprovalRequest review,RegisteredMpcKey key,SigningCandidate candidate,JsonNode facts,Instant now,ObjectMapper json) {
        owner(review,key,candidate,MpcClient.Algorithm.ECDSA_SECP256K1,json);var p=payload(candidate,TronSigningPayload.class,json);var asset=review.assetSnapshot();
        amount(review,p.symbol(),p.amount(),p.customerFee(),p.decimals());var u=p.unsigned();var tx=MpcTronTransactionCodec.restore(u);var raw=tx.getRawData();
        require(Arrays.equals(raw.getData().toByteArray(),MpcTronTransactionCodec.businessMemo(candidate.command().tenantId(),candidate.command().businessType().name(),candidate.command().businessReference())),
                "TRON memo differs from the original independently admitted business");
        require(asset.nativeSymbol().equals("TRX") && asset.nativeDecimals()==6 && u.owner().equals(org.tron.TronWalletApi.getAddress(HexFormat.of().parseHex(key.publicKeyHex())))
                && u.recipient().equals(review.intent().recipient()) && u.amountAtomic().equals(atomic(review)) && Objects.equals(u.contract(),asset.tokenIdentifier()),"TRON owner, recipient, asset or principal differs");
        require(facts!=null && asset.networkIdentity().equals("tron:"+facts.path("genesis").path("blockID").asText()),"TRON independent genesis differs");
        String reference=facts.path("reference").path("blockID").asText();require(reference.matches("[0-9a-f]{64}")
                && reference.substring(16,32).equals(HexFormat.of().formatHex(raw.getRefBlockHash().toByteArray()))
                && reference.substring(12,16).equals(HexFormat.of().formatHex(raw.getRefBlockBytes().toByteArray())),"TRON TAPOS differs from independent block identity");
        require(raw.getTimestamp()>=now.minusSeconds(300).toEpochMilli() && raw.getTimestamp()<=now.plusSeconds(30).toEpochMilli()
                && raw.getExpiration()>now.toEpochMilli() && raw.getExpiration()<=now.plusSeconds(3600).toEpochMilli(),"TRON original transaction lifetime is outside independent bounds");
        var parameters=new HashMap<String,BigInteger>();for(var item:facts.path("parameters").path("chainParameter"))parameters.put(item.path("key").asText(),item.has("value")?integer(item.path("value")):BigInteger.ZERO);
        require(parameters.containsKey("getTransactionFee") && parameters.containsKey("getCreateNewAccountFeeInSystemContract") && parameters.containsKey("getCreateAccountFee"),"TRON independent native fee parameters are incomplete");
        // Reserve bandwidth for the exact signed envelope plus result overhead, activation and capped VM energy.
        BigInteger bandwidth=parameters.get("getTransactionFee").multiply(BigInteger.valueOf(tx.getSerializedSize()+70+64));
        BigInteger activation=parameters.get("getCreateNewAccountFeeInSystemContract").add(parameters.get("getCreateAccountFee"));
        BigInteger expenditure=bandwidth.add(u.contract()==null?activation:BigInteger.valueOf(u.feeLimitSun()));
        message(candidate,MpcTronTransactionCodec.digest(u));
        return new ApprovalWithdrawalVerifiers.Verified("TRON:"+asset.networkIdentity()+":"+HexFormat.of().formatHex(MpcTronTransactionCodec.digest(u)),fee(review,expenditure),false);
    }
}
