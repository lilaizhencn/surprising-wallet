package com.surprising.wallet.approval.chain;

import static com.surprising.wallet.approval.chain.WithdrawalWireChecks.*;
import static com.surprising.wallet.approval.chain.Evm7702WithdrawalVerifier.address;
import static com.surprising.wallet.approval.chain.Evm7702WithdrawalVerifier.quantity;
import static com.surprising.wallet.approval.chain.Evm7702WithdrawalVerifier.publicAddress;
import com.surprising.wallet.approval.gateway.ApprovalChainRpcClient;
import com.surprising.wallet.approval.gateway.ApprovalUtxoRpcClient.Outpoint;
import com.surprising.wallet.approval.model.RegisteredMpcKey;
import com.surprising.wallet.common.approval.*;
import com.surprising.wallet.common.signing.*;
import com.surprising.wallet.sdk.signing.MpcEvmTransactionCodec;
import com.surprising.wallet.sdk.signing.evm7702.*;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.web3j.crypto.*;
import org.web3j.crypto.transaction.type.*;
import org.web3j.utils.Numeric;
import tools.jackson.databind.*;

/** Fixed multi-source sweep to the independent operating treasury, pinned collector/delegate and one complete outer transaction. */
final class Evm7702CollectionVerifier {
    static List<UUID> keyIds(SigningCandidate candidate,ObjectMapper json) {
        var envelope=Evm7702SigningPayload.decode(candidate.command(),json);var result=new ArrayList<UUID>();result.add(envelope.plan().relayer().getMpcKeyId());
        preparation(envelope,json).items().forEach(item->result.add(item.authority().getMpcKeyId()));return List.copyOf(result);
    }
    static JsonNode observe(ApprovalRequest review,SigningCandidate candidate,ApprovalChainRpcClient rpc,ObjectMapper json) {
        var envelope=Evm7702SigningPayload.decode(candidate.command(),json);var p=preparation(envelope,json);var reads=rpc.open(review.assetSnapshot(),"DEFAULT");String tenant="."+review.intent().tenantId();
        String delegate=address(reads.parameter("collectionDelegate"+tenant)),collector=address(reads.parameter("collector"+tenant)),relayer=address(reads.parameter("relayer"+tenant));
        var facts=json.createObjectNode().put("delegate",delegate).put("delegateCodeHash",reads.parameter("collectionDelegateCodeHash"+tenant))
                .put("collector",collector).put("collectorCodeHash",reads.parameter("collectorCodeHash"+tenant)).put("relayer",relayer).put("maximumGasLimit",reads.parameter("maximumBatchGas"));
        facts.set("chainId",reads.rpc("eth_chainId",List.of()));facts.set("delegateCode",reads.rpc("eth_getCode",List.of(delegate,"latest")));
        facts.set("collectorCode",reads.rpc("eth_getCode",List.of(collector,"latest")));facts.set("relayerNonce",reads.rpc("eth_getTransactionCount",List.of(relayer,"pending")));
        facts.set("relayerBalance",reads.rpc("eth_getBalance",List.of(relayer,"latest")));
        facts.set("expectedCollector",reads.rpc("eth_call",List.of(Map.of("to",delegate,"data",Hash.sha3String("EXPECTED_COLLECTOR()").substring(0,10)),"latest")));
        String relayerSelector=Hash.sha3String("relayers(address)").substring(0,10)+"0".repeat(24)+relayer.substring(2);
        facts.set("relayerAllowed",reads.rpc("eth_call",List.of(Map.of("to",collector,"data",relayerSelector),"latest")));
        facts.set("block",reads.rpc("eth_getBlockByNumber",List.of("latest",false)));
        String slot=Numeric.toHexStringWithPrefixZeroPadded(Numeric.toBigInt(Hash.sha3String("surprising.wallet.eip7702.collection.nonce.v1")).subtract(BigInteger.ONE),64);var sources=facts.putArray("sources");
        for(var item:p.items()) {
            String source=address(item.request().authority());var observation=sources.addObject().put("authority",source);
            observation.set("code",reads.rpc("eth_getCode",List.of(source,"latest")));observation.set("nonce",reads.rpc("eth_getTransactionCount",List.of(source,"pending")));
            observation.set("operationNonce",reads.rpc("eth_getStorageAt",List.of(source,slot,"latest")));
            var asset=review.assetSnapshot();observation.set("balance",asset.tokenIdentifier()==null?reads.rpc("eth_getBalance",List.of(source,"latest"))
                    :reads.rpc("eth_call",List.of(Map.of("to",asset.tokenIdentifier(),"data","0x70a08231"+"0".repeat(24)+source.substring(2)),"latest")));
        }
        return facts;
    }
    static ApprovalWithdrawalVerifiers.Verified verify(List<ApprovalRequest> admitted,Map<UUID,RegisteredMpcKey> registered,SigningCandidate candidate,JsonNode facts,Instant now,ObjectMapper json) {
        var command=candidate.command();var envelope=Evm7702SigningPayload.decode(command,json);var plan=envelope.plan();var p=preparation(envelope,json);var asset=admitted.getFirst().assetSnapshot();
        require(command.businessType()==MpcSignCommand.BusinessType.COLLECTION && facts!=null && admitted.stream().allMatch(a->a.intent().source().equals("SYSTEM"))
                && p.items().size()==admitted.size() && p.items().size()==plan.references().size() && p.items().size()<=100
                && p.chainId().equals(BigInteger.valueOf(plan.chainId())) && asset.networkIdentity().equals("eip155:"+plan.chainId())
                && quantity(facts.path("chainId")).equals(BigInteger.valueOf(plan.chainId())),"EIP-7702 independent collection plan/domain differs");
        var relayer=registered.get(plan.relayer().getMpcKeyId());var key=registered.get(command.keyId());
        require(relayer!=null && key!=null && key.epoch()==command.keyEpoch() && candidate.execution().operation()==MpcClient.Operation.SIGN
                && candidate.execution().algorithm()==MpcClient.Algorithm.ECDSA_SECP256K1 && registered.values().stream().allMatch(k->k.tenantId().equals(command.tenantId())
                    && k.keyGroup().equals("EVM") && k.algorithm().equals("ECDSA_SECP256K1") && k.state().equals("ACTIVE")),"EIP-7702 collection key/epoch or original native execution differs");
        String payer=publicAddress(relayer),delegate=address(facts.path("delegate").asText()),collector=address(facts.path("collector").asText());
        require(command.tenantId().equals(plan.relayer().getTenantId()) && payer.equals(address(plan.relayer().getAddress())) && payer.equals(address(facts.path("relayer").asText()))
                && plan.relayerNonce().compareTo(quantity(facts.path("relayerNonce")))>=0,"EIP-7702 collection relayer domain differs");
        require(codeHash(facts.path("delegateCode"),facts.path("delegateCodeHash")) && codeHash(facts.path("collectorCode"),facts.path("collectorCodeHash"))
                && facts.path("expectedCollector").asText().equals("0x"+"0".repeat(24)+collector.substring(2)) && quantity(facts.path("relayerAllowed")).equals(BigInteger.ONE),"EIP-7702 collection delegate/collector code or relayer permission differs");
        String prefix="eip7702:"+command.tenantId()+":";require(command.businessReference().startsWith(prefix),"EIP-7702 collection business owner differs");UUID batch=UUID.fromString(command.businessReference().substring(prefix.length()));
        byte[] expectedBatch=Hash.sha3((command.tenantId()+":"+batch).getBytes(StandardCharsets.UTF_8));
        var byReference=new HashMap<String,ApprovalRequest>();for(var receipt:admitted)require(receipt.assetSnapshot().equals(asset) && receipt.intentHash().equals(asset.bind(receipt.intent(),json))
                && byReference.put(receipt.intent().businessReference(),receipt)==null,"automatic collection contains another asset/owner or duplicate reference");
        require(new HashSet<>(plan.references()).equals(byReference.keySet()) && facts.path("sources").isArray() && facts.path("sources").size()==p.items().size(),"EIP-7702 collection source/operating receipt set differs");
        BigInteger allowance=BigInteger.ZERO;var owners=new HashSet<UUID>();var points=new ArrayList<Outpoint>();
        for(int i=0;i<p.items().size();i++) {
            var item=p.items().get(i);var request=item.request();var receipt=byReference.get(plan.references().get(i));var source=registered.get(item.authority().getMpcKeyId());var observed=facts.path("sources").path(i);
            require(source!=null && owners.add(source.keyId()) && !source.keyId().equals(relayer.keyId()) && command.tenantId().equals(item.authority().getTenantId())
                    && address(request.authority()).equals(publicAddress(source)) && address(item.authority().getAddress()).equals(publicAddress(source))
                    && observed.path("authority").asText().equals(publicAddress(source)) && address(request.collector()).equals(collector)
                    && request.itemIndex().equals(BigInteger.valueOf(i)) && Arrays.equals(request.batchId(),expectedBatch)
                    && address(request.recipient()).equals(address(receipt.intent().recipient())) && request.amount().equals(atomic(receipt))
                    && quantity(observed.path("balance")).compareTo(request.amount())>=0
                    && address(request.token()).equals(asset.tokenIdentifier()==null?"0x"+"0".repeat(40):address(asset.tokenIdentifier()))
                    && request.operationNonce().equals(quantity(observed.path("operationNonce")))
                    && request.deadline().compareTo(BigInteger.valueOf(now.getEpochSecond()))>0 && request.deadline().compareTo(BigInteger.valueOf(now.plusSeconds(3600).getEpochSecond()))<=0
                    && request.callGasLimit().compareTo(BigInteger.valueOf(30_000))>=0 && request.callGasLimit().compareTo(BigInteger.valueOf(500_000))<=0,
                    "EIP-7702 collection exact source, amount, recipient, token, reference or nonce differs");
            String current=observed.path("code").asText();require(current.equals("0x") || current.equals("0xef0100"+delegate.substring(2)),"collection source has an untrusted delegate");
            var authorization=item.authorization();require(authorization!=null || !current.equals("0x"),"collection source lacks its original delegation");
            if(authorization!=null) {
                require(authorization.chainId().equals(p.chainId()) && address(authorization.authority()).equals(publicAddress(source)) && address(authorization.delegate()).equals(delegate)
                        && authorization.nonce().equals(quantity(observed.path("nonce"))),"collection delegation changes original chain, authority, delegate or nonce");
                points.add(Evm7702WithdrawalVerifier.lock("EVM:"+asset.networkIdentity()+":"+publicAddress(source)+":"+authorization.nonce()));
            }
            points.add(Evm7702WithdrawalVerifier.lock("EIP7702_COLLECTION:"+asset.networkIdentity()+":"+publicAddress(source)+":"+request.operationNonce()));allowance=allowance.add(ceiling(receipt));
        }
        points.add(Evm7702WithdrawalVerifier.lock("EVM:"+asset.networkIdentity()+":"+payer+":"+plan.relayerNonce()));
        BigInteger gas=integer(facts.path("maximumGasLimit"));require(gas.signum()>0 && gas.compareTo(quantity(facts.path("block").path("gasLimit")))<=0
                && p.maxFeePerGas().signum()>0 && p.maxPriorityFeePerGas().signum()>=0 && p.maxPriorityFeePerGas().compareTo(p.maxFeePerGas())<=0,"collection whole-batch gas policy differs");
        BigInteger exposure=EvmFeePolicy.verify(admitted,candidate,facts.path("evmFees"),gas.multiply(p.maxFeePerGas()),json);require(exposure.compareTo(allowance)<=0 && exposure.compareTo(asset.networkFeeLimit().movePointRight(asset.nativeDecimals()).toBigIntegerExact())<=0,"collection whole-batch gas exposure exceeds admitted or independent ceiling");
        require(quantity(facts.path("relayerBalance")).compareTo(exposure)>=0,"independent collection relayer cannot cover the original batch exposure");
        if(command.signingSlot().equals("relayer")) {
            require(command.purpose()==MpcSignCommand.Purpose.TRANSACTION && key.keyId().equals(relayer.keyId()) && envelope.outer()!=null,"collection relayer slot differs");verifyOuter(candidate,envelope,p,registered,collector,gas,exposure);
        } else {
            String[] slot=command.signingSlot().split("-",-1);require(slot.length==2 && slot[1].matches("0|[1-9][0-9]?"),"invalid collection inner slot");int index=Integer.parseInt(slot[1]);require(index<p.items().size() && envelope.outer()==null,"collection inner slot outside original batch");var item=p.items().get(index);
            require(key.keyId().equals(item.authority().getMpcKeyId()),"collection inner signer belongs to another item");
            if(slot[0].equals("authorization")){require(command.purpose()==MpcSignCommand.Purpose.EIP7702_AUTHORIZATION && item.authorization()!=null,"collection authorization slot differs");message(candidate,MpcEvm7702Codec.digest(item.authorization()));}
            else {require(slot[0].equals("operation") && command.purpose()==MpcSignCommand.Purpose.EIP7702_OPERATION,"collection operation slot differs");message(candidate,new Evm7702OperationSigner().digest(p.chainId(),item.request()));}
        }
        return new ApprovalWithdrawalVerifiers.Verified("EIP7702_COLLECTION:"+asset.networkIdentity()+":"+SigningAuthorization.hash(json.writeValueAsBytes(points)),exposure,true,points);
    }
    private static void verifyOuter(SigningCandidate candidate,Evm7702SigningPayload envelope,Evm7702CollectionPayload p,Map<UUID,RegisteredMpcKey> registered,String collector,BigInteger gas,BigInteger exposure) {
        long chainId=envelope.plan().chainId();var raw=MpcEvmTransactionCodec.restore(HexFormat.of().parseHex(envelope.outer().unsignedHex()),chainId);
        require(raw.getTransaction() instanceof Transaction1559 && raw.getNonce().equals(envelope.plan().relayerNonce()) && raw.getValue().signum()==0 && address(raw.getTo()).equals(collector)
                && raw.getGasLimit().signum()>0 && raw.getGasLimit().compareTo(gas)<=0,"collection outer type/nonce/value/destination or gas differs");var dynamic=(Transaction1559)raw.getTransaction();
        require(dynamic.getMaxFeePerGas().equals(p.maxFeePerGas()) && dynamic.getMaxPriorityFeePerGas().equals(p.maxPriorityFeePerGas()) && raw.getGasLimit().multiply(dynamic.getMaxFeePerGas()).compareTo(exposure)<=0,"collection outer fixed fee bounds differ");
        byte[] data=Numeric.hexStringToByteArray(raw.getData());var signatures=signatures(data,p.items().size());var requests=p.items().stream().map(Evm7702CollectionPayload.Item::request).toList();
        require(new Evm7702ContractCodec().encodeCollectBatch(requests,signatures).equalsIgnoreCase(Numeric.toHexString(data)),"final collection calldata differs from complete admitted requests");
        for(int i=0;i<p.items().size();i++) {
            byte[] signature=signatures.get(i);byte[] digest=new Evm7702OperationSigner().digest(p.chainId(),p.items().get(i).request());
            try {var recovered=Sign.signedMessageHashToKey(digest,new Sign.SignatureData(signature[64],Arrays.copyOfRange(signature,0,32),Arrays.copyOfRange(signature,32,64)));
                require(("0x"+Keys.getAddress(recovered)).equals(publicAddress(registered.get(p.items().get(i).authority().getMpcKeyId()))),"collection inner signature belongs to another source");}
            catch(java.security.SignatureException invalid){throw new IllegalArgumentException("invalid collection authority signature",invalid);}
        }
        var tuples=raw.getTransaction() instanceof Transaction7702 type?type.getAuthorizationList():List.<AuthorizationTuple>of();var authorizations=p.items().stream().filter(i->i.authorization()!=null).toList();
        require(tuples.size()==authorizations.size(),"collection outer delegation list differs");
        for(int i=0;i<tuples.size();i++) {
            var tuple=tuples.get(i);var item=authorizations.get(i);var original=item.authorization();require(tuple.getChainId().equals(original.chainId()) && address(tuple.getAddress()).equals(address(original.delegate())) && tuple.getNonce().equals(original.nonce()),"collection outer delegation changed domain or nonce");
            try {require(address(Sign.recoverAuthorizationSigner(tuple)).equals(publicAddress(registered.get(item.authority().getMpcKeyId()))),"collection delegation signature belongs to another source");}
            catch(java.security.SignatureException invalid){throw new IllegalArgumentException("invalid collection delegation signature",invalid);}
        }
        var expected=MpcEvm7702Codec.batch(chainId,envelope.plan().relayerNonce(),p.maxPriorityFeePerGas(),p.maxFeePerGas(),raw.getGasLimit(),collector,Numeric.toHexString(data),tuples);
        require(Arrays.equals(MpcEvmTransactionCodec.unsignedPayload(expected,chainId),HexFormat.of().parseHex(envelope.outer().unsignedHex())),"collection final outer contains unreviewed wire fields or access list");message(candidate,MpcEvmTransactionCodec.digest(expected,chainId));
    }
    private static List<byte[]> signatures(byte[] data,int expected) {
        int start=Math.addExact(4,word(data,36));require(word(data,start)==expected,"collection signature array count differs");int offsets=Math.addExact(start,32);var result=new ArrayList<byte[]>();
        for(int i=0;i<expected;i++) {int item=Math.addExact(offsets,word(data,Math.addExact(offsets,Math.multiplyExact(i,32))));require(word(data,item)==65 && item<=data.length-97,"invalid collection signature array member");result.add(Arrays.copyOfRange(data,item+32,item+97));}
        return List.copyOf(result);
    }
    private static int word(byte[] bytes,int at){require(at>=0 && at<=bytes.length-32,"truncated collection ABI word");return new BigInteger(1,Arrays.copyOfRange(bytes,at,at+32)).intValueExact();}
    private static boolean codeHash(JsonNode code,JsonNode hash){return code.isString() && code.asText().matches("0x(?:[0-9a-f]{2})+") && Hash.sha3(code.asText()).equalsIgnoreCase(hash.asText());}
    private static Evm7702CollectionPayload preparation(Evm7702SigningPayload envelope,ObjectMapper json){return json.readerFor(Evm7702CollectionPayload.class).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(envelope.plan().preparationJson());}
}
