package com.surprising.wallet.approval.chain;

import static com.surprising.wallet.approval.chain.WithdrawalWireChecks.*;
import com.surprising.wallet.approval.gateway.ApprovalChainRpcClient;
import com.surprising.wallet.approval.model.RegisteredMpcKey;
import com.surprising.wallet.common.approval.*;
import com.surprising.wallet.common.signing.*;
import com.surprising.wallet.sdk.signing.solana.MpcSolanaTransactionCodec;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.p2p.solanaj.core.PublicKey;
import tools.jackson.databind.*;

/** Canonical legacy native/SPL transfer and optional ATA creation; every signer binds one identical message/version. */
final class SolanaWithdrawalVerifier {
    static List<String> publicKeys(SigningCandidate candidate,ObjectMapper json) {
        return MpcSolanaTransactionCodec.restore(payload(candidate,SolanaSigningPayload.class,json).unsigned()).signers().stream()
                .map(address->HexFormat.of().formatHex(new PublicKey(address).toByteArray())).toList();
    }
    static JsonNode observe(ApprovalRequest review,SigningCandidate candidate,ApprovalChainRpcClient rpc,ObjectMapper json) {
        var p=payload(candidate,SolanaSigningPayload.class,json);var message=MpcSolanaTransactionCodec.restore(p.unsigned());var reads=rpc.open(review.assetSnapshot(),"DEFAULT");
        var facts=json.createObjectNode();facts.set("genesis",reads.rpc("getGenesisHash",List.of()));
        facts.set("validBlockhash",reads.rpc("isBlockhashValid",List.of(message.blockhash(),Map.of("commitment","confirmed"))));
        facts.set("fee",reads.rpc("getFeeForMessage",List.of(Base64.getEncoder().encodeToString(HexFormat.of().parseHex(p.unsigned().messageHex())),Map.of("commitment","confirmed"))));
        facts.set("blockHeight",reads.rpc("getBlockHeight",List.of(Map.of("commitment","confirmed"))));
        if(p.mint()!=null){facts.set("rent",reads.rpc("getMinimumBalanceForRentExemption",List.of(165,Map.of("commitment","confirmed"))));facts.put("feePayer",reads.parameter("feePayer."+review.intent().tenantId()));}
        return facts;
    }
    static ApprovalWithdrawalVerifiers.Verified verify(ApprovalRequest review,RegisteredMpcKey key,Map<UUID,RegisteredMpcKey> registered,SigningCandidate candidate,JsonNode facts,ObjectMapper json) {
        owner(review,key,candidate,MpcClient.Algorithm.ED25519,json);var p=payload(candidate,SolanaSigningPayload.class,json);var asset=review.assetSnapshot();
        amount(review,p.symbol(),p.amount(),p.customerFee(),p.decimals());require(asset.nativeSymbol().equals("SOL") && asset.nativeDecimals()==9,"Solana native precision differs");
        var message=MpcSolanaTransactionCodec.restore(p.unsigned());String signer=new PublicKey(HexFormat.of().parseHex(key.publicKeyHex())).toBase58();
        require(p.sender().equals(message.signers().stream().filter(p.sender()::equals).findFirst().orElse(null)) && p.feePayer().equals(message.feePayer())
                && p.recipient().equals(review.intent().recipient()) && Objects.equals(p.mint(),asset.tokenIdentifier())
                && signer.equals(candidate.command().signingSlot().equals("transaction")?p.sender():p.feePayer()),"Solana key slot, sender, fee payer, mint or recipient differs");
        var signers=new HashSet<>(List.of(p.sender(),p.feePayer()));require(signers.equals(new HashSet<>(message.signers())),"Solana message has an unreviewed signer");
        var known=new HashSet<String>();for(var k:registered.values()) {
            require(k.state().equals("ACTIVE") && k.algorithm().equals("ED25519") && k.keyGroup().equals("SOLANA") && k.tenantId().equals(review.intent().tenantId()),"Solana signer is not an active independently registered tenant key");
            known.add(new PublicKey(HexFormat.of().parseHex(k.publicKeyHex())).toBase58());
        }
        require(known.containsAll(signers),"Solana source or Gas payer is not independently registered");
        if(p.mint()==null)require(p.sender().equals(p.feePayer()),"native SOL payer must equal its payment source");
        else require(facts!=null && p.feePayer().equals(facts.path("feePayer").asText()),"SPL fee payer differs from the independently installed Gas role");
        String memo=HexFormat.of().formatHex(org.bitcoinj.base.Sha256Hash.hash(("surprising-wallet:solana:v1\n"+review.intent().tenantId()+"\n"+candidate.command().businessType()+"\n"+review.intent().businessReference()).getBytes(StandardCharsets.UTF_8)));
        var expected=MpcSolanaTransactionCodec.payment(p.sender(),p.feePayer(),review.intent().recipient(),asset.tokenIdentifier(),atomic(review).longValueExact(),asset.decimals(),message.blockhash(),memo);
        require(expected.equals(p.unsigned()),"Solana message differs from the exact canonical payment and approved memo");message(candidate,MpcSolanaTransactionCodec.signingBytes(expected));
        require(facts!=null && asset.networkIdentity().equals("solana:"+facts.path("genesis").asText()) && facts.path("validBlockhash").path("value").isBoolean()
                && facts.path("validBlockhash").path("value").asBoolean() && p.lastValidBlockHeight()>integer(facts.path("blockHeight")).longValueExact(),"Solana original blockhash/network is not independently valid");
        BigInteger expenditure=integer(facts.path("fee").path("value"));if(p.mint()!=null)expenditure=expenditure.add(integer(facts.path("rent")));
        return new ApprovalWithdrawalVerifiers.Verified("SOLANA:"+asset.networkIdentity()+":"+SigningAuthorization.hash(HexFormat.of().parseHex(expected.messageHex())),fee(review,expenditure),false);
    }
}
