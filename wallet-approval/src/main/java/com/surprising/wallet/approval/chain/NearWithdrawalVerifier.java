package com.surprising.wallet.approval.chain;

import static com.surprising.wallet.approval.chain.WithdrawalWireChecks.*;
import com.surprising.wallet.approval.gateway.ApprovalChainRpcClient;
import com.surprising.wallet.approval.model.RegisteredMpcKey;
import com.surprising.wallet.common.approval.*;
import com.surprising.wallet.common.signing.*;
import com.surprising.wallet.sdk.signing.near.MpcNearTransactionCodec;
import java.math.BigInteger;
import java.util.*;
import tools.jackson.databind.*;

/** Exact Transfer or fixed NEP-141 ft_transfer, original access-key nonce and original canonical reference block. */
final class NearWithdrawalVerifier {
    static JsonNode observe(ApprovalRequest review,SigningCandidate candidate,ApprovalChainRpcClient rpc,ObjectMapper json) {
        var p=payload(candidate,NearSigningPayload.class,json);var u=p.unsigned();var reads=rpc.open(review.assetSnapshot(),"DEFAULT");var result=json.createObjectNode();
        result.set("genesis",reads.rpc("block",Map.of("block_id",0)));result.set("reference",reads.rpc("block",Map.of("block_id",u.blockHash())));
        result.set("final",reads.rpc("block",Map.of("finality","final")));
        result.set("accessKey",reads.rpc("query",Map.of("request_type","view_access_key","finality","final","account_id",u.sender(),"public_key",MpcNearTransactionCodec.publicKey(u))));
        result.set("gasPrice",reads.rpc("gas_price",List.of(u.blockHash())));result.put("maximumGasPrice",reads.parameter("maximumGasPriceYocto"));return result;
    }
    static ApprovalWithdrawalVerifiers.Verified verify(ApprovalRequest review,RegisteredMpcKey key,SigningCandidate candidate,JsonNode facts,ObjectMapper json) {
        owner(review,key,candidate,MpcClient.Algorithm.ED25519,json);var p=payload(candidate,NearSigningPayload.class,json);var asset=review.assetSnapshot();var u=p.unsigned();
        amount(review,p.symbol(),p.amount(),p.customerFee(),p.decimals());require(asset.nativeSymbol().equals("NEAR") && asset.nativeDecimals()==24
                && u.network().equals(asset.network()) && u.publicKeyHex().equals(key.publicKeyHex()) && u.amountAtomic().equals(atomic(review))
                && u.recipient().equals(MpcNearTransactionCodec.account(review.intent().recipient())) && Objects.equals(u.tokenContract(),asset.tokenIdentifier()),"NEAR key, network, recipient, token or exact principal differs");
        require(u.tokenContract()==null?u.memo()==null:Objects.equals(u.memo(),candidate.command().businessReference()),"NEAR memo differs from the original reviewed business");
        require(facts!=null && asset.networkIdentity().equals("near:"+u.genesisHash()) && u.genesisHash().equals(facts.path("genesis").path("header").path("hash").asText())
                && u.blockHash().equals(facts.path("reference").path("header").path("hash").asText())
                && BigInteger.valueOf(u.blockHeight()).equals(integer(facts.path("reference").path("header").path("height")))
                && u.validUntilHeight()>integer(facts.path("final").path("header").path("height")).longValueExact()
                && u.validUntilHeight()<=Math.addExact(u.blockHeight(),86400)
                && integer(facts.path("accessKey").path("nonce")).compareTo(BigInteger.valueOf(u.nonce()))<0
                && facts.path("accessKey").path("permission").asText().equals("FullAccess"),"NEAR original nonce/reference validity differs from independent chain facts");
        BigInteger maximumGasPrice=integer(facts.path("maximumGasPrice"));require(maximumGasPrice.signum()>0 && integer(facts.path("gasPrice").path("gas_price")).compareTo(maximumGasPrice)<=0,"NEAR live gas price exceeds independent price policy");
        // A conservative whole-transaction gas allowance, plus the exact one-yocto NEP-141 deposit.
        BigInteger estimate=maximumGasPrice.multiply(BigInteger.valueOf(300_000_000_000_000L)).add(u.tokenContract()==null?BigInteger.ZERO:BigInteger.ONE);
        require(estimate.compareTo(ceiling(review))<=0,"NEAR maximum gas exposure exceeds the reviewed fee allowance");
        message(candidate,MpcNearTransactionCodec.signingBytes(u));
        return new ApprovalWithdrawalVerifiers.Verified("NEAR:"+asset.networkIdentity()+":"+u.sender()+":"+u.nonce(),fee(review,ceiling(review)),true);
    }
}
