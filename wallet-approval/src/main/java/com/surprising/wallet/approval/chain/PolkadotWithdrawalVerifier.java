package com.surprising.wallet.approval.chain;

import static com.surprising.wallet.approval.chain.WithdrawalWireChecks.*;
import com.surprising.wallet.approval.gateway.ApprovalChainRpcClient;
import com.surprising.wallet.approval.model.RegisteredMpcKey;
import com.surprising.wallet.common.approval.*;
import com.surprising.wallet.common.signing.*;
import com.surprising.wallet.sdk.signing.polkadot.MpcPolkadotTransactionCodec;
import java.math.BigInteger;
import java.util.*;
import tools.jackson.databind.*;

/** Native/Asset Hub SCALE V4 payments; call indices and extension order come from the independent runtime, never Wallet metadata. */
final class PolkadotWithdrawalVerifier {
    static JsonNode observe(ApprovalRequest review,SigningCandidate candidate,ApprovalChainRpcClient rpc,ObjectMapper json) {
        var p=payload(candidate,PolkadotSigningPayload.class,json);var u=p.unsigned();var reads=rpc.open(review.assetSnapshot(),u.role().name());
        var body=new LinkedHashMap<String,Object>();body.put("chain","DOT");body.put("rpcUrl",reads.parameter("rpcUrl"));
        body.put("address",u.sender());body.put("to",u.recipient());body.put("amountAtomic",u.amountAtomic().toString());body.put("assetId",u.assetId());
        body.put("blockHash",u.blockHash());body.put("blockHeight",u.blockHeight());body.put("nonce",u.nonce());body.put("eraPeriod",u.eraPeriod());body.put("keepAlive",u.keepAlive());
        var response=reads.rest("/v1/polkadot/signing-facts",body);require(response.path("ok").isBoolean() && response.path("ok").asBoolean() && response.path("result").isObject(),"independent DOT runtime could not resolve the fixed signing domain");
        return response.path("result");
    }
    static ApprovalWithdrawalVerifiers.Verified verify(ApprovalRequest review,RegisteredMpcKey key,SigningCandidate candidate,JsonNode facts,ObjectMapper json) {
        owner(review,key,candidate,MpcClient.Algorithm.ED25519,json);var p=payload(candidate,PolkadotSigningPayload.class,json);var asset=review.assetSnapshot();var u=p.unsigned();
        amount(review,p.symbol(),p.amount(),p.customerFee(),p.decimals());
        require(asset.nativeSymbol().equals("DOT") && u.network().equals(asset.network()) && p.nativeDecimals()==asset.nativeDecimals()
                && u.publicKeyHex().equals(key.publicKeyHex()) && Arrays.equals(MpcPolkadotTransactionCodec.account(u.recipient()),MpcPolkadotTransactionCodec.account(review.intent().recipient()))
                && u.amountAtomic().equals(atomic(review)) && Objects.equals(u.assetId(),asset.tokenIdentifier()) && u.role()==MpcPolkadotTransactionCodec.Role.ASSET_HUB,
                "DOT reviewed Asset Hub payment, precision, recipient or public key differs");
        require(facts!=null && asset.networkIdentity().equals("polkadot:"+u.genesisHash().substring(2)) && facts.path("genesisHash").asText().equals(u.genesisHash())
                && facts.path("blockHash").asText().equals(u.blockHash()) && integer(facts.path("blockHeight")).longValueExact()==u.blockHeight()
                && integer(facts.path("finalizedHeight")).longValueExact()<u.validUntilHeight()
                && integer(facts.path("nonce")).longValueExact()<=u.nonce()
                && integer(facts.path("specVersion")).longValueExact()==u.specVersion() && integer(facts.path("transactionVersion")).longValueExact()==u.transactionVersion()
                && integer(facts.path("decimals")).intValueExact()==asset.decimals() && integer(facts.path("nativeDecimals")).intValueExact()==asset.nativeDecimals()
                && integer(facts.path("extrinsicVersion")).intValueExact()==4 && integer(facts.path("extensionVersion")).signum()==0,
                "DOT independent canonical reference, runtime version, nonce or precision differs");
        var extensions=new ArrayList<String>();require(facts.path("extensions").isArray(),"independent DOT runtime extensions are missing");facts.path("extensions").forEach(e->{require(e.isString(),"invalid DOT extension name");extensions.add(e.asText());});
        require(extensions.equals(u.extensions()) && facts.path("callIndices").equals(json.valueToTree(u.callIndices()))
                && p.existentialDeposit()!=null && p.existentialDeposit().equals(integer(facts.path("existentialDeposit"))),"DOT SCALE call indices, extension layout/order or existential deposit differs");
        require(p.feeBudget()!=null && p.feeBudget().signum()>0 && p.feeBudget().compareTo(ceiling(review))<=0
                && integer(facts.path("feePlanck")).multiply(BigInteger.TWO).compareTo(p.feeBudget())<=0,"DOT independent execution quote exceeds the fixed reviewed fee budget");
        message(candidate,MpcPolkadotTransactionCodec.signingBytes(u));
        return new ApprovalWithdrawalVerifiers.Verified("DOT:"+asset.networkIdentity()+":"+u.publicKeyHex()+":"+u.nonce(),fee(review,ceiling(review)),true);
    }
}
