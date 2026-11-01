package com.surprising.wallet.approval.chain;

import static com.surprising.wallet.approval.chain.WithdrawalWireChecks.*;
import com.surprising.wallet.approval.gateway.ApprovalChainRpcClient;
import com.surprising.wallet.approval.model.RegisteredMpcKey;
import com.surprising.wallet.common.approval.*;
import com.surprising.wallet.common.signing.*;
import com.surprising.wallet.sdk.signing.hypercore.MpcHyperCoreActionCodec;
import java.math.*;
import java.time.Instant;
import java.util.*;
import org.bitcoinj.crypto.ECKey;
import org.web3j.crypto.*;
import tools.jackson.databind.*;

/** Spot-pocket user-signed transfer only. Changing its time/nonce can repay, so fresh sessions are refused. */
final class HyperCoreWithdrawalVerifier {
    static JsonNode observe(ApprovalRequest review,SigningCandidate candidate,ApprovalChainRpcClient rpc,ObjectMapper json) {
        var p=payload(candidate,HyperCoreSigningPayload.class,json);var action=MpcHyperCoreActionCodec.restore(p.unsigned());var reads=rpc.open(review.assetSnapshot(),"DEFAULT");
        require(reads.parameter("hyperliquidChain").equals(action.path("hyperliquidChain").asText()),"HyperCore independent API network differs");
        var result=json.createObjectNode();result.set("recipientRole",reads.rest("/info",Map.of("type","userRole","user",review.intent().recipient())));
        return result;
    }
    static ApprovalWithdrawalVerifiers.Verified verify(ApprovalRequest review,RegisteredMpcKey key,SigningCandidate candidate,JsonNode facts,Instant now,ObjectMapper json) {
        owner(review,key,candidate,MpcClient.Algorithm.ECDSA_SECP256K1,json);var p=payload(candidate,HyperCoreSigningPayload.class,json);
        var asset=review.assetSnapshot();amount(review,p.symbol(),p.amount(),p.customerFee(),asset.decimals());var u=p.unsigned();var action=MpcHyperCoreActionCodec.restore(u);
        String address="0x"+Keys.getAddress(Sign.publicFromPoint(ECKey.fromPublicOnly(HexFormat.of().parseHex(key.publicKeyHex())).decompress().getPubKey()));
        require(u.owner().equals(address) && action.path("destination").asText().equals(review.intent().recipient())
                && new BigDecimal(action.path("amount").asText()).compareTo(review.intent().amount())==0
                && action.path("type").asText().equals("spotSend") && facts!=null && action.path("token").asText().equals(asset.tokenIdentifier()),"HyperCore signed pocket, owner, token or payment differs");
        String expected=asset.network().equals("mainnet")?"Mainnet":asset.network().equals("testnet")?"Testnet":"";
        require(!expected.isEmpty() && action.path("hyperliquidChain").asText().equals(expected) && asset.networkIdentity().equals("hyperliquid:"+expected),"HyperCore signed EIP-712 network differs");
        require(asset.tokenIdentifier()!=null && asset.tokenIdentifier().matches("[A-Za-z0-9._-]+:0x[0-9a-f]{32}"),"HyperCore reviewed asset must freeze its exact spot wire token, including canonical USDC");
        require(Set.of("user","subAccount").contains(facts.path("recipientRole").path("role").asText()),"HyperCore recipient is not independently activated; activation expenditure is not authorized");
        long nonce=action.path("time").asLong();require(nonce>=now.minusSeconds(300).toEpochMilli() && nonce<=now.plusSeconds(30).toEpochMilli(),"HyperCore original nonce is outside the independent signing window");
        message(candidate,MpcHyperCoreActionCodec.digest(u));
        return new ApprovalWithdrawalVerifiers.Verified("HYPERCORE:"+asset.networkIdentity()+":"+address+":"+nonce,fee(review,BigInteger.ZERO),false);
    }
}
