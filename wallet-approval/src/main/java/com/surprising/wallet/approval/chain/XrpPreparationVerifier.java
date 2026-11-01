package com.surprising.wallet.approval.chain;

import static com.surprising.wallet.approval.chain.WithdrawalWireChecks.*;
import com.surprising.wallet.approval.gateway.ApprovalChainRpcClient;
import com.surprising.wallet.approval.model.RegisteredMpcKey;
import com.surprising.wallet.common.approval.*;
import com.surprising.wallet.common.signing.*;
import com.surprising.wallet.sdk.signing.xrp.MpcXrpTransactionCodec;
import java.math.*;
import java.util.*;
import org.xrpl.xrpl4j.model.transactions.*;
import tools.jackson.databind.*;

/** A bounded account setup permission, never an IOU payment or a human withdrawal approval. */
final class XrpPreparationVerifier {
    static JsonNode observe(ApprovalRequest operation,SigningCandidate candidate,ApprovalChainRpcClient rpc,ObjectMapper json) {
        var p=payload(candidate,XrpSigningPayload.class,json);var tx=MpcXrpTransactionCodec.restore(p.unsigned());var asset=operation.assetSnapshot();
        require(candidate.command().businessType()==MpcSignCommand.BusinessType.ADDRESS_PREPARATION && asset.tokenIdentifier()!=null,"explicit XRP issued-asset setup required");
        String[] token=asset.tokenIdentifier().split(":",-1);require(token.length==2,"independent XRPL issuer/currency required");
        String target=tx instanceof Payment payment?payment.destination().value():tx.account().value();var reads=rpc.open(asset,"DEFAULT");
        var server=reads.rpc("server_info",List.of()).path("info");String hash=server.path("validated_ledger").path("hash").asText();
        require(server.path("network_id").asText().equals(reads.parameter("networkId")),"XRPL preparation node network differs");
        var facts=json.createObjectNode();facts.set("server",server);facts.put("gasAddress",reads.parameter("gasAddress."+candidate.command().tenantId()));
        facts.put("trustLimit",reads.parameter("trustLimit."+candidate.command().tenantId()));
        facts.set("source",reads.xrpAccount(tx.account().value(),hash));var account=reads.xrpAccount(target,hash);facts.set("target",account);
        facts.set("issuer",reads.xrpAccount(token[0],hash));
        if(!account.has("error"))facts.set("lines",reads.rpc("account_lines",List.of(Map.of("account",target,"peer",token[0],"ledger_hash",hash,"limit",400))));
        return facts;
    }
    static ApprovalWithdrawalVerifiers.Verified verify(ApprovalRequest operation,RegisteredMpcKey key,Map<UUID,RegisteredMpcKey> keys,SigningCandidate candidate,JsonNode facts,ObjectMapper json) {
        owner(operation,key,candidate,MpcClient.Algorithm.ECDSA_SECP256K1,json);var asset=operation.assetSnapshot();var p=payload(candidate,XrpSigningPayload.class,json);
        require(candidate.command().businessType()==MpcSignCommand.BusinessType.ADDRESS_PREPARATION && operation.intent().source().equals("SYSTEM")
                && operation.intent().amount().signum()==0 && p.customerFee().signum()==0 && p.symbol().equals(asset.symbol()) && asset.tokenIdentifier()!=null
                && asset.nativeSymbol().equals("XRP") && asset.nativeDecimals()==6 && facts!=null,"XRP preparation must have zero reviewed principal and a fixed issued asset");
        var tx=MpcXrpTransactionCodec.restore(p.unsigned());String target=tx instanceof Payment payment?payment.destination().value():tx.account().value();
        var targetKey=keys.values().stream().filter(k->RegisteredMpcAddresses.matches(k,asset,target)).toList();
        require(targetKey.size()==1 && !target.equals(facts.path("gasAddress").asText()) && target.equals(operation.intent().recipient())
                && candidate.command().businessReference().equals("prepare:"+targetKey.getFirst().keyId()+":"+asset.symbol()+":"+(tx instanceof TrustSet?"trust":"fund")),"XRPL setup target/reference differs from its independently registered tenant key");
        require(HexFormat.of().formatHex(tx.signingPublicKey().value().toByteArray()).equals(key.publicKeyHex()),"XRPL setup public key differs");
        var wire=json.readTree(org.xrpl.xrpl4j.codec.binary.XrplBinaryCodec.getInstance().decode(p.unsigned().transactionHex()));
        var allowed=tx instanceof Payment?Set.of("TransactionType","Account","Destination","Amount","Fee","Sequence","LastLedgerSequence","SigningPubKey","Flags","NetworkID")
                :Set.of("TransactionType","Account","LimitAmount","Fee","Sequence","LastLedgerSequence","SigningPubKey","Flags","NetworkID");
        wire.propertyNames().forEach(name->require(allowed.contains(name),"XRPL setup contains an unreviewed field"));
        var server=facts.path("server");var ledger=server.path("validated_ledger");String network=server.path("network_id").asText();
        require(asset.networkIdentity().equals("xrpl:"+network) && ledger.path("seq").isIntegralNumber()
                && tx.lastLedgerSequence().orElseThrow().longValue()>ledger.path("seq").asLong() && tx.lastLedgerSequence().orElseThrow().longValue()<=ledger.path("seq").asLong()+100,"XRPL setup network/expiry differs from observed validated ledger");
        if(Long.parseLong(network)>1024)require(wire.path("NetworkID").asText().equals(network),"XRPL setup signed network ID differs");else require(!wire.has("NetworkID"),"XRPL main/test setup must omit NetworkID");
        var source=facts.path("source").path("account_data");require(source.path("Account").asText().equals(tx.account().value()) && integer(source.path("Sequence")).compareTo(BigInteger.valueOf(tx.sequence().longValue()))<=0,"XRPL setup original source/sequence differs");
        String[] token=asset.tokenIdentifier().split(":",-1);require(token.length==2 && !target.equals(token[0]),"XRPL trust issuer cannot be its target");
        var issuer=facts.path("issuer").path("account_data");require(issuer.path("Account").asText().equals(token[0]) && !integer(issuer.path("Flags")).testBit(22),"XRPL issuer is missing or globally frozen");
        var targetAccount=facts.path("target");boolean missing=targetAccount.path("error").asText().equals("actNotFound");var data=targetAccount.path("account_data");
        if(!missing)require(data.path("Account").asText().equals(target),"XRPL setup observed another target");
        JsonNode existing=null;
        if(!missing) {
            var lines=facts.path("lines");require(lines.path("account").asText().equals(target) && lines.path("lines").isArray() && !lines.hasNonNull("marker"),"complete XRPL target trust-line page required");
            for(var line:lines.path("lines"))if(line.path("account").asText().equals(token[0]) && line.path("currency").asText().equalsIgnoreCase(token[1])) {require(existing==null,"duplicate XRPL trust line");existing=line;}
        }
        require(existing==null || new BigDecimal(existing.path("limit").asText()).signum()==0 && new BigDecimal(existing.path("balance").asText()).signum()>=0
                && !existing.path("freeze").asBoolean() && !existing.path("freeze_peer").asBoolean(),"XRPL setup cannot modify a funded, enabled or frozen trust line");
        require(!integer(issuer.path("Flags")).testBit(18) || existing!=null && existing.path("peer_authorized").asBoolean(),"XRPL issuer authorization is required before automatic setup");
        BigInteger fee=tx.fee().value().bigIntegerValue();BigInteger base=drops(ledger.path("reserve_base_xrp")),increment=drops(ledger.path("reserve_inc_xrp"));
        require(base.signum()>0 && increment.signum()>=0,"XRPL reserve parameters are invalid");
        BigInteger targetBalance=missing?BigInteger.ZERO:integer(data.path("Balance"));BigInteger owners=missing?BigInteger.ZERO:integer(data.path("OwnerCount"));
        BigInteger reserve=base.add(increment.multiply(owners.add(BigInteger.ONE)));BigInteger expense=fee;
        if(tx instanceof Payment payment) {
            require(tx.account().value().equals(facts.path("gasAddress").asText()) && payment.amount() instanceof XrpCurrencyAmount,"XRPL activation must use the independently installed tenant Gas source and native XRP");
            BigInteger flags=wire.has("Flags")?integer(wire.path("Flags")):BigInteger.ZERO;require(flags.signum()==0 || flags.equals(BigInteger.valueOf(0x80000000L)),"XRPL activation flags differ");
            BigInteger amount=((XrpCurrencyAmount)payment.amount()).value().bigIntegerValue();BigInteger deficit=reserve.add(fee.multiply(BigInteger.valueOf(3))).subtract(targetBalance);
            require(amount.signum()>0 && amount.equals(p.amount().movePointRight(6).toBigIntegerExact()) && deficit.signum()>0 && amount.compareTo(deficit)<=0
                    && (!missing || amount.compareTo(base)>=0),"XRPL activation exceeds the actual target reserve deficit");
            expense=expense.add(amount);BigInteger sourceReserve=base.add(increment.multiply(integer(source.path("OwnerCount"))));
            require(integer(source.path("Balance")).subtract(sourceReserve).compareTo(expense)>=0,"XRPL Gas source lacks spendable principal and fee");
        } else {
            var trust=(TrustSet)tx;BigInteger flags=integer(wire.path("Flags"));
            require(flags.equals(BigInteger.valueOf(0x20000L)) || flags.equals(BigInteger.valueOf(0x80020000L)),"XRPL TrustSet must set NoRipple only");
            BigDecimal limit=new BigDecimal(facts.path("trustLimit").asText());require(limit.signum()>0 && p.amount().signum()==0 && trust.limitAmount().issuer().value().equals(token[0])
                    && trust.limitAmount().currency().equalsIgnoreCase(token[1]) && new BigDecimal(trust.limitAmount().value()).compareTo(limit)==0,"XRPL TrustSet issuer, currency or installed trust limit differs");
            require(!missing && targetBalance.compareTo(reserve.add(fee))>=0,"XRPL TrustSet lacks independently observed reserve and fee");
        }
        message(candidate,MpcXrpTransactionCodec.digest(p.unsigned()));
        return new ApprovalWithdrawalVerifiers.Verified("XRP:"+asset.networkIdentity()+":"+tx.account().value()+":"+tx.sequence().longValue(),fee(operation,expense),true,List.of(),true);
    }
    private static BigInteger drops(JsonNode value) {require(value!=null && (value.isNumber() || value.isString()),"XRPL reserve amount missing");return new BigDecimal(value.asText()).movePointRight(6).toBigIntegerExact();}
}
