package com.surprising.wallet.approval.chain;

import static com.surprising.wallet.approval.chain.WithdrawalWireChecks.*;
import com.surprising.wallet.approval.gateway.ApprovalChainRpcClient;
import com.surprising.wallet.approval.model.RegisteredMpcKey;
import com.surprising.wallet.common.approval.*;
import com.surprising.wallet.common.signing.*;
import com.surprising.wallet.sdk.signing.ton.MpcTonTransactionCodec;
import java.math.BigInteger;
import java.time.Instant;
import java.util.*;
import org.ton.ton4j.address.Address;
import org.ton.ton4j.cell.*;
import tools.jackson.databind.*;

/** Only the public V4R2 body produced by the shared codec; master-derived Jetton ownership is independently read. */
final class TonWithdrawalVerifier {
    static JsonNode observe(ApprovalRequest review,SigningCandidate candidate,ApprovalChainRpcClient rpc,ObjectMapper json) {
        var p=payload(candidate,TonSigningPayload.class,json);var u=p.unsigned();var reads=rpc.open(review.assetSnapshot(),"DEFAULT");var result=json.createObjectNode();
        result.set("masterchain",reads.rpc("getMasterchainInfo",Map.of()));result.set("wallet",reads.rpc("getWalletInformation",Map.of("address",u.sender())));
        if(u.jettonMaster()!=null) {
            result.put("sourceJettonWallet",jettonWallet(reads,u.sender(),u.jettonMaster(),json));result.put("recipientJettonWallet",jettonWallet(reads,u.recipient(),u.jettonMaster(),json));
            result.set("jettonData",reads.rpc("runGetMethod",Map.of("address",u.sourceJettonWallet(),"method","get_wallet_data","stack",List.of())));
        }
        var body=CellBuilder.beginCell().storeBytes(new byte[64]).storeCell(MpcTonTransactionCodec.body(u)).endCell();
        var parameters=new HashMap<String,Object>();parameters.put("address",u.sender());parameters.put("body",Base64.getEncoder().encodeToString(body.toBoc(false)));parameters.put("ignore_chksig",true);
        var wallet=MpcTonTransactionCodec.wallet(u.network(),HexFormat.of().parseHex(u.publicKeyHex()));
        if(u.sequence()==0){parameters.put("init_code",Base64.getEncoder().encodeToString(wallet.getStateInit().getCode().toBoc(false)));parameters.put("init_data",Base64.getEncoder().encodeToString(wallet.getStateInit().getData().toBoc(false)));}
        result.set("fee",reads.rpc("estimateFee",parameters));return result;
    }
    static ApprovalWithdrawalVerifiers.Verified verify(ApprovalRequest review,RegisteredMpcKey key,SigningCandidate candidate,JsonNode facts,Instant now,ObjectMapper json) {
        owner(review,key,candidate,MpcClient.Algorithm.ED25519,json);var p=payload(candidate,TonSigningPayload.class,json);var asset=review.assetSnapshot();var u=p.unsigned();
        amount(review,p.symbol(),p.amount(),p.customerFee(),p.decimals());require(asset.nativeSymbol().equals("TON") && asset.nativeDecimals()==9
                && u.network().equals(asset.network()) && u.publicKeyHex().equals(key.publicKeyHex())
                && u.recipient().equals(MpcTonTransactionCodec.canonicalAddress(review.intent().recipient())) && u.amountAtomic().equals(atomic(review))
                && Objects.equals(u.jettonMaster(),asset.tokenIdentifier()==null?null:MpcTonTransactionCodec.canonicalAddress(asset.tokenIdentifier())),"TON signed network, key, recipient, master or principal differs");
        require(u.memo().equals(MpcTonTransactionCodec.businessMemo(candidate.command().tenantId(),candidate.command().businessType().name(),candidate.command().businessReference()))
                && u.queryId()==MpcTonTransactionCodec.businessQueryId(candidate.command().tenantId(),candidate.command().businessType().name(),candidate.command().businessReference(),u.jettonMaster()!=null),
                "TON memo or Jetton query identity differs from the original approved business");
        require(facts!=null && u.validUntil()>now.getEpochSecond() && u.validUntil()<=now.plusSeconds(3600).getEpochSecond(),"TON original signing lifetime is outside independent bounds");
        String genesis=facts.path("masterchain").path("init").path("root_hash").asText();byte[] root=genesis.matches("[0-9a-f]{64}")?HexFormat.of().parseHex(genesis):Base64.getDecoder().decode(genesis);
        require(root.length==32 && asset.networkIdentity().equals("ton:"+HexFormat.of().formatHex(root)),"TON independent zero-state identity differs");
        var wallet=facts.path("wallet");require(wallet.path("seqno").isIntegralNumber() && wallet.path("seqno").asLong()==u.sequence()
                || u.sequence()==0 && wallet.path("account_state").asText().equals("uninitialized"),"TON original sequence differs from independent wallet state");
        if(u.jettonMaster()!=null) {
            require(u.sourceJettonWallet().equals(facts.path("sourceJettonWallet").asText()) && u.recipientJettonWallet().equals(facts.path("recipientJettonWallet").asText()),"TON source/destination Jetton wallet differs from configured master's getter");
            var data=facts.path("jettonData");require(data.path("exit_code").isIntegralNumber() && data.path("exit_code").asInt()==0 && data.path("stack").isArray() && data.path("stack").size()==4
                    && stackAddress(data.path("stack").path(1)).equals(u.sender()) && stackAddress(data.path("stack").path(2)).equals(u.jettonMaster()),"TON Jetton owner/master relationship differs");
        }
        var sourceFees=facts.path("fee").path("source_fees");BigInteger estimate=BigInteger.ZERO;
        for(String field:List.of("in_fwd_fee","storage_fee","gas_fee","fwd_fee"))estimate=estimate.add(integer(sourceFees.path(field)));
        // Reserve the entire reviewed exposure, including the fixed attached Jetton value, rather than one signature's estimate.
        BigInteger exposure=estimate.multiply(BigInteger.TWO).add(u.jettonMaster()==null?BigInteger.ZERO:MpcTonTransactionCodec.JETTON_ATTACHED_NANO);
        require(exposure.compareTo(ceiling(review))<=0,"TON conservative execution exposure exceeds the independently reviewed allowance");
        message(candidate,MpcTonTransactionCodec.signingBytes(u));
        return new ApprovalWithdrawalVerifiers.Verified("TON:"+asset.networkIdentity()+":"+u.sender()+":"+u.sequence(),fee(review,ceiling(review)),true);
    }
    private static String jettonWallet(ApprovalChainRpcClient.Reads reads,String owner,String master,ObjectMapper json) {
        var slice=CellBuilder.beginCell().storeAddress(Address.of(owner)).endCell();
        var result=reads.rpc("runGetMethod",Map.of("address",master,"method","get_wallet_address","stack",List.of(List.of("tvm.Slice",Base64.getEncoder().encodeToString(slice.toBoc(false))))));
        require(result.path("exit_code").isIntegralNumber() && result.path("exit_code").asInt()==0 && result.path("stack").isArray() && result.path("stack").size()==1,"TON master getter failed");
        return stackAddress(result.path("stack").path(0));
    }
    private static String stackAddress(JsonNode item) {
        require(Set.of("cell","slice").contains(item.path(0).asText()) && item.path(1).path("bytes").isString(),"TON getter did not return an address cell");
        return MpcTonTransactionCodec.canonicalAddress(CellSlice.beginParse(Cell.fromBocBase64(item.path(1).path("bytes").asText())).loadAddress().toRaw());
    }
}
