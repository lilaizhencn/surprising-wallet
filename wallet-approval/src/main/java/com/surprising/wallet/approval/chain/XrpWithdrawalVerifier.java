package com.surprising.wallet.approval.chain;

import static com.surprising.wallet.approval.chain.WithdrawalWireChecks.*;
import com.surprising.wallet.approval.gateway.ApprovalChainRpcClient;
import com.surprising.wallet.approval.model.RegisteredMpcKey;
import com.surprising.wallet.common.approval.*;
import com.surprising.wallet.common.signing.*;
import com.surprising.wallet.sdk.signing.xrp.MpcXrpTransactionCodec;
import java.math.BigDecimal;
import java.util.*;
import org.xrpl.xrpl4j.model.transactions.*;
import tools.jackson.databind.*;

/** Direct Payment only; canonical binary, no paths, partial payment, tickets, tags or embedded authorization. */
final class XrpWithdrawalVerifier {
    static JsonNode observe(ApprovalRequest review,SigningCandidate candidate,ApprovalChainRpcClient rpc,ObjectMapper json) {
        var payload=payload(candidate,XrpSigningPayload.class,json);var tx=MpcXrpTransactionCodec.restore(payload.unsigned());
        var reads=rpc.open(review.assetSnapshot(),"DEFAULT");var facts=json.createObjectNode();
        var info=reads.rpc("server_info",List.of()).path("info");
        require(info.path("network_id").asText().equals(reads.parameter("networkId")),"XRPL node network ID differs from independent profile");
        facts.set("server",info);facts.set("account",reads.rpc("account_info",List.of(Map.of("account",tx.account().value(),"ledger_index","validated","strict",true))));
        return facts;
    }
    static ApprovalWithdrawalVerifiers.Verified verify(ApprovalRequest review,RegisteredMpcKey key,SigningCandidate candidate,JsonNode facts,ObjectMapper json) {
        owner(review,key,candidate,MpcClient.Algorithm.ECDSA_SECP256K1,json);
        var p=payload(candidate,XrpSigningPayload.class,json);var asset=review.assetSnapshot();amount(review,p.symbol(),p.amount(),p.customerFee(),asset.decimals());
        require(asset.nativeSymbol().equals("XRP") && asset.nativeDecimals()==6 && facts!=null,"XRPL independent native network facts are required");
        var tx=MpcXrpTransactionCodec.restore(p.unsigned());require(tx instanceof Payment,"only a direct Payment can consume a withdrawal approval");var payment=(Payment)tx;
        require(HexFormat.of().formatHex(tx.signingPublicKey().value().toByteArray()).equals(key.publicKeyHex())
                && payment.destination().value().equals(review.intent().recipient()),"XRPL sender or destination differs from the reviewed payment");
        // Roundtrip through xrpl4j is strict, then allow only the Payment fields emitted by Wallet.
        var wire=json.readTree(org.xrpl.xrpl4j.codec.binary.XrplBinaryCodec.getInstance().decode(p.unsigned().transactionHex()));
        var allowed=Set.of("TransactionType","Account","Destination","Amount","Fee","Sequence","LastLedgerSequence","SigningPubKey","Flags","NetworkID");
        wire.propertyNames().forEach(name->require(allowed.contains(name),"XRPL payment contains an unreviewed field"));
        require(!wire.has("Flags") || wire.path("Flags").bigIntegerValue().equals(java.math.BigInteger.valueOf(0x80000000L)) || wire.path("Flags").bigIntegerValue().signum()==0,"XRPL payment has unsupported flags");
        if(asset.tokenIdentifier()==null)require(payment.amount() instanceof XrpCurrencyAmount nativeAmount && nativeAmount.value().bigIntegerValue().equals(atomic(review)),"XRPL actual drops differ from reviewed amount");
        else {
            String[] token=asset.tokenIdentifier().split(":",-1);
            require(token.length==2 && payment.amount() instanceof IssuedCurrencyAmount issued && issued.issuer().value().equals(token[0])
                    && issued.currency().equalsIgnoreCase(token[1]) && new BigDecimal(issued.value()).compareTo(review.intent().amount())==0,"XRPL issued currency, issuer or exact amount differs");
        }
        var account=facts.path("account").path("account_data");var server=facts.path("server");
        require(account.path("Account").asText().equals(tx.account().value()) && integer(account.path("Sequence")).compareTo(java.math.BigInteger.valueOf(tx.sequence().longValue()))<=0
                && server.path("validated_ledger").path("seq").isIntegralNumber()
                && tx.lastLedgerSequence().orElseThrow().longValue()>server.path("validated_ledger").path("seq").asLong()
                && tx.lastLedgerSequence().orElseThrow().longValue()<=server.path("validated_ledger").path("seq").asLong()+100,
                "XRPL original sequence/expiry is outside the independently observed ledger window");
        String networkId=server.path("network_id").asText();require(asset.networkIdentity().equals("xrpl:"+networkId),"XRPL reviewed network identity differs");
        if(Long.parseLong(networkId)>1024)require(wire.has("NetworkID") && wire.path("NetworkID").asText().equals(networkId),"XRPL sidechain payment requires its signed network ID");
        else require(!wire.has("NetworkID"),"XRPL main/test payment must omit network ID");
        message(candidate,MpcXrpTransactionCodec.digest(p.unsigned()));
        return new ApprovalWithdrawalVerifiers.Verified("XRP:"+asset.networkIdentity()+":"+tx.account().value()+":"+tx.sequence().longValue(),fee(review,tx.fee().value().bigIntegerValue()),true);
    }
}
