package com.surprising.wallet.approval.chain;

import static com.surprising.wallet.approval.chain.WithdrawalWireChecks.*;
import com.surprising.wallet.approval.gateway.ApprovalChainRpcClient;
import com.surprising.wallet.approval.gateway.ApprovalUtxoRpcClient.Outpoint;
import com.surprising.wallet.approval.model.RegisteredMpcKey;
import com.surprising.wallet.common.approval.*;
import com.surprising.wallet.common.signing.*;
import com.surprising.wallet.sdk.signing.MpcEvmTransactionCodec;
import com.surprising.wallet.sdk.signing.evm7702.*;
import java.math.BigInteger;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.bitcoinj.crypto.ECKey;
import org.web3j.crypto.*;
import org.web3j.crypto.transaction.type.*;
import org.web3j.utils.Numeric;
import tools.jackson.databind.*;

/** All original approved items, pinned delegate bytecode/executor, inner domain, authorization and final relayer wire are one batch. */
final class Evm7702WithdrawalVerifier {
    static List<UUID> keyIds(SigningCandidate candidate,ObjectMapper json) {
        var payload=Evm7702SigningPayload.decode(candidate.command(),json);var prepared=preparation(payload,json);
        return List.of(Objects.requireNonNull(payload.plan().relayer().getMpcKeyId()),Objects.requireNonNull(prepared.authority().getMpcKeyId()));
    }
    static JsonNode observe(ApprovalRequest review,SigningCandidate candidate,ApprovalChainRpcClient rpc,ObjectMapper json) {
        var envelope=Evm7702SigningPayload.decode(candidate.command(),json);var p=preparation(envelope,json);var reads=rpc.open(review.assetSnapshot(),"DEFAULT");
        String tenant="."+review.intent().tenantId();
        String delegate=address(reads.parameter("payoutDelegate"+tenant)),relayer=address(reads.parameter("relayer"+tenant));String authority=address(p.request().authority());
        var facts=json.createObjectNode().put("delegate",delegate).put("delegateCodeHash",reads.parameter("payoutDelegateCodeHash"+tenant))
                .put("relayer",relayer).put("maximumGasLimit",reads.parameter("maximumBatchGas"));
        facts.set("chainId",reads.rpc("eth_chainId",List.of()));facts.set("delegateCode",reads.rpc("eth_getCode",List.of(delegate,"latest")));
        facts.set("authorityCode",reads.rpc("eth_getCode",List.of(authority,"latest")));
        facts.set("authorityNonce",reads.rpc("eth_getTransactionCount",List.of(authority,"pending")));
        facts.set("relayerNonce",reads.rpc("eth_getTransactionCount",List.of(relayer,"pending")));
        String slot=Numeric.toHexStringWithPrefixZeroPadded(Numeric.toBigInt(Hash.sha3String("surprising.wallet.eip7702.payout.nonce.v1")).subtract(BigInteger.ONE),64);
        facts.set("operationNonce",reads.rpc("eth_getStorageAt",List.of(authority,slot,"latest")));
        facts.set("expectedExecutor",reads.rpc("eth_call",List.of(Map.of("to",delegate,"data",Hash.sha3String("EXPECTED_EXECUTOR()").substring(0,10)),"latest")));
        facts.set("block",reads.rpc("eth_getBlockByNumber",List.of("latest",false)));return facts;
    }
    static ApprovalWithdrawalVerifiers.Verified verify(List<ApprovalRequest> reviewed,Map<UUID,RegisteredMpcKey> registered,SigningCandidate candidate,JsonNode facts,Instant now,ObjectMapper json) {
        var command=candidate.command();var envelope=Evm7702SigningPayload.decode(command,json);var plan=envelope.plan();var prepared=preparation(envelope,json);var request=prepared.request();var asset=reviewed.getFirst().assetSnapshot();
        require(command.chain().isEvm() && command.businessType()==MpcSignCommand.BusinessType.WITHDRAWAL && facts!=null
                && asset.networkIdentity().equals("eip155:"+plan.chainId()) && prepared.chainId().equals(BigInteger.valueOf(plan.chainId()))
                && quantity(facts.path("chainId")).equals(BigInteger.valueOf(plan.chainId())),"EIP-7702 independent reviewed network differs");
        var authority=registered.get(prepared.authority().getMpcKeyId());var relayer=registered.get(plan.relayer().getMpcKeyId());var key=registered.get(command.keyId());
        require(authority!=null && relayer!=null && key!=null && !authority.keyId().equals(relayer.keyId())
                && authority.state().equals("ACTIVE") && relayer.state().equals("ACTIVE") && key.epoch()==command.keyEpoch()
                && candidate.execution().operation()==MpcClient.Operation.SIGN && candidate.execution().algorithm()==MpcClient.Algorithm.ECDSA_SECP256K1
                && registered.values().stream().allMatch(k->k.tenantId().equals(command.tenantId()) && k.keyGroup().equals("EVM") && k.algorithm().equals("ECDSA_SECP256K1")),"EIP-7702 independent authority/relayer key domain differs");
        String source=publicAddress(authority),payer=publicAddress(relayer),delegate=address(facts.path("delegate").asText());
        require(address(prepared.authority().getAddress()).equals(source) && address(request.authority()).equals(source)
                && address(request.executor()).equals(payer) && address(plan.relayer().getAddress()).equals(payer)
                && address(facts.path("relayer").asText()).equals(payer) && command.tenantId().equals(plan.relayer().getTenantId())
                && command.tenantId().equals(prepared.authority().getTenantId()),"EIP-7702 batch public role differs from independently registered keys");
        String code=facts.path("delegateCode").asText();require(code.matches("0x(?:[0-9a-f]{2})+") && Hash.sha3(code).equalsIgnoreCase(facts.path("delegateCodeHash").asText())
                && facts.path("expectedExecutor").asText().matches("0x0{24}"+payer.substring(2)),"EIP-7702 delegate bytecode or immutable executor differs from installed policy");
        String current=facts.path("authorityCode").asText();require(current.equals("0x") || current.equals("0xef0100"+delegate.substring(2)),"EIP-7702 authority currently delegates to an untrusted implementation");
        require(prepared.authorization()!=null || !current.equals("0x"),"EIP-7702 payout source has no installed delegate or original authorization");
        require(request.deadline().compareTo(BigInteger.valueOf(now.getEpochSecond()))>0 && request.deadline().compareTo(BigInteger.valueOf(now.plusSeconds(3600).getEpochSecond()))<=0
                && request.operationNonce().equals(quantity(facts.path("operationNonce"))) && plan.relayerNonce().compareTo(quantity(facts.path("relayerNonce")))>=0,"EIP-7702 original operation/relayer nonce or deadline differs");
        String prefix="eip7702:"+command.tenantId()+":";require(command.businessReference().startsWith(prefix),"EIP-7702 original batch owner differs");
        UUID batch=UUID.fromString(command.businessReference().substring(prefix.length()));
        require(Arrays.equals(request.batchId(),Hash.sha3((command.tenantId()+":WITHDRAWAL:"+batch).getBytes(StandardCharsets.UTF_8))),"EIP-7702 batch ID differs from its immutable business reference");
        var byReference=new HashMap<String,ApprovalRequest>();for(var review:reviewed) {
            require(review.assetSnapshot().equals(asset) && review.intentHash().equals(asset.bind(review.intent(),json)) && byReference.put(review.intent().businessReference(),review)==null,"EIP-7702 reviewed batch contains another asset or duplicate order");
        }
        require(new HashSet<>(plan.references()).equals(byReference.keySet()) && request.items().size()==reviewed.size(),"EIP-7702 final batch does not contain every approved order exactly once");
        BigInteger allowance=BigInteger.ZERO;
        for(int index=0;index<request.items().size();index++) {
            var item=request.items().get(index);var review=byReference.get(plan.references().get(index));
            require(item.itemIndex().equals(BigInteger.valueOf(index)) && item.callGasLimit().compareTo(BigInteger.valueOf(30_000))>=0 && item.callGasLimit().compareTo(BigInteger.valueOf(500_000))<=0
                    && Arrays.equals(item.withdrawalId(),Hash.sha3((command.tenantId()+":WITHDRAWAL:"+review.intent().id()).getBytes(StandardCharsets.UTF_8)))
                    && address(item.recipient()).equals(address(review.intent().recipient())) && item.amount().equals(atomic(review))
                    && address(item.token()).equals(asset.tokenIdentifier()==null?"0x"+"0".repeat(40):address(asset.tokenIdentifier())),"EIP-7702 exact item, amount, token or recipient differs from approval");
            allowance=allowance.add(ceiling(review));
        }
        BigInteger gas=integer(facts.path("maximumGasLimit"));require(gas.signum()>0 && gas.compareTo(quantity(facts.path("block").path("gasLimit")))<=0
                && prepared.maxFeePerGas().signum()>0 && prepared.maxPriorityFeePerGas().signum()>=0 && prepared.maxPriorityFeePerGas().compareTo(prepared.maxFeePerGas())<=0,"EIP-7702 independently bounded gas or fixed prices differ");
        BigInteger exposure=EvmFeePolicy.verify(reviewed,candidate,facts.path("evmFees"),gas.multiply(prepared.maxFeePerGas()),json);require(exposure.compareTo(allowance)<=0
                && exposure.compareTo(asset.networkFeeLimit().movePointRight(asset.nativeDecimals()).toBigIntegerExact())<=0,"EIP-7702 whole-batch gas exposure exceeds reviewed or independent fee allowance");
        var authorization=prepared.authorization();if(authorization!=null)require(authorization.chainId().equals(BigInteger.valueOf(plan.chainId()))
                && address(authorization.authority()).equals(source) && address(authorization.delegate()).equals(delegate)
                && authorization.nonce().equals(quantity(facts.path("authorityNonce"))),"EIP-7702 delegation changes chain, delegate or original authority nonce");
        switch(command.signingSlot()) {
            case "authorization-0" -> {require(command.purpose()==MpcSignCommand.Purpose.EIP7702_AUTHORIZATION && authorization!=null && key.keyId().equals(authority.keyId()) && envelope.outer()==null,"EIP-7702 authorization slot differs");message(candidate,MpcEvm7702Codec.digest(authorization));}
            case "operation-0" -> {require(command.purpose()==MpcSignCommand.Purpose.EIP7702_OPERATION && key.keyId().equals(authority.keyId()) && envelope.outer()==null,"EIP-7702 operation slot differs");message(candidate,new Evm7702PayoutSigner().digest(BigInteger.valueOf(plan.chainId()),request));}
            case "relayer" -> {require(command.purpose()==MpcSignCommand.Purpose.TRANSACTION && key.keyId().equals(relayer.keyId()) && envelope.outer()!=null,"EIP-7702 relayer slot differs");verifyOuter(candidate,envelope,prepared,authority,gas,exposure);}
            default -> throw new IllegalArgumentException("unknown EIP-7702 withdrawal signature slot");
        }
        var points=new ArrayList<Outpoint>();points.add(lock("EVM:"+asset.networkIdentity()+":"+payer+":"+plan.relayerNonce()));
        points.add(lock("EIP7702_OPERATION:"+asset.networkIdentity()+":"+source+":"+request.operationNonce()));
        if(authorization!=null)points.add(lock("EVM:"+asset.networkIdentity()+":"+source+":"+authorization.nonce()));
        return new ApprovalWithdrawalVerifiers.Verified("EIP7702:"+asset.networkIdentity()+":"+source+":"+request.operationNonce(),exposure,true,points);
    }
    private static void verifyOuter(SigningCandidate candidate,Evm7702SigningPayload envelope,Evm7702PayoutPayload p,RegisteredMpcKey authority,BigInteger gas,BigInteger exposure) {
        long chainId=envelope.plan().chainId();var raw=MpcEvmTransactionCodec.restore(HexFormat.of().parseHex(envelope.outer().unsignedHex()),chainId);
        require(raw.getTransaction() instanceof Transaction1559 && raw.getGasLimit().signum()>0 && raw.getGasLimit().compareTo(gas)<=0,"EIP-7702 outer type or gas limit differs");
        var dynamic=(Transaction1559)raw.getTransaction();require(raw.getNonce().equals(envelope.plan().relayerNonce()) && raw.getValue().signum()==0
                && address(raw.getTo()).equals(address(p.request().authority())) && dynamic.getMaxFeePerGas().equals(p.maxFeePerGas()) && dynamic.getMaxPriorityFeePerGas().equals(p.maxPriorityFeePerGas())
                && raw.getGasLimit().multiply(dynamic.getMaxFeePerGas()).compareTo(exposure)<=0,"EIP-7702 outer nonce, destination, value or fixed fee bounds differ");
        byte[] data=Numeric.hexStringToByteArray(raw.getData());require(data.length>=96,"EIP-7702 outer authority signature missing");byte[] signature=Arrays.copyOfRange(data,data.length-96,data.length-31);
        require(new Evm7702PayoutCodec().encode(p.request(),signature).equalsIgnoreCase(Numeric.toHexString(data)),"EIP-7702 final calldata differs from every approved payout item");
        byte[] digest=new Evm7702PayoutSigner().digest(BigInteger.valueOf(chainId),p.request());
        try {
            var recovered=Sign.signedMessageHashToKey(digest,new Sign.SignatureData(signature[64],Arrays.copyOfRange(signature,0,32),Arrays.copyOfRange(signature,32,64)));
            require(("0x"+Keys.getAddress(recovered)).equals(publicAddress(authority)),"EIP-7702 outer inner signature belongs to another key");
        } catch(java.security.SignatureException invalid){throw new IllegalArgumentException("invalid EIP-7702 authority signature",invalid);}
        var tuples=raw.getTransaction() instanceof Transaction7702 type?type.getAuthorizationList():List.<AuthorizationTuple>of();
        require(tuples.size()==(p.authorization()==null?0:1),"EIP-7702 outer authorization list differs from original delegation plan");
        if(!tuples.isEmpty()) {
            var tuple=tuples.getFirst();var original=p.authorization();
            require(tuple.getChainId().equals(original.chainId()) && address(tuple.getAddress()).equals(address(original.delegate())) && tuple.getNonce().equals(original.nonce()),"EIP-7702 outer authorization changed its signed chain, delegate or nonce");
            try{require(address(Sign.recoverAuthorizationSigner(tuple)).equals(publicAddress(authority)),"EIP-7702 outer authorization signer differs");}
            catch(java.security.SignatureException invalid){throw new IllegalArgumentException("invalid EIP-7702 delegation signature",invalid);}
        }
        var expected=MpcEvm7702Codec.batch(chainId,envelope.plan().relayerNonce(),p.maxPriorityFeePerGas(),p.maxFeePerGas(),raw.getGasLimit(),p.request().authority(),Numeric.toHexString(data),tuples);
        require(Arrays.equals(MpcEvmTransactionCodec.unsignedPayload(expected,chainId),HexFormat.of().parseHex(envelope.outer().unsignedHex())),"EIP-7702 outer has an unreviewed access list or wire field");
        message(candidate,MpcEvmTransactionCodec.digest(expected,chainId));
    }
    private static Evm7702PayoutPayload preparation(Evm7702SigningPayload envelope,ObjectMapper json) {
        return json.readerFor(Evm7702PayoutPayload.class).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(envelope.plan().preparationJson());
    }
    static BigInteger quantity(JsonNode node){require(node.isString() && node.asText().matches("0x[0-9a-f]{1,64}"),"invalid independent EVM integer/storage word");return Numeric.toBigInt(node.asText());}
    static String address(String value){require(value!=null && value.matches("0x[0-9a-fA-F]{40}"),"invalid EIP-7702 public address");return value.toLowerCase(Locale.ROOT);}
    static String publicAddress(RegisteredMpcKey key){return "0x"+Keys.getAddress(Sign.publicFromPoint(ECKey.fromPublicOnly(HexFormat.of().parseHex(key.publicKeyHex())).decompress().getPubKey()));}
    static Outpoint lock(String domain){return new Outpoint(SigningAuthorization.hash(domain.getBytes(StandardCharsets.US_ASCII)),0);}
}
