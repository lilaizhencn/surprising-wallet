package com.surprising.wallet.approval.chain;

import static com.surprising.wallet.approval.chain.WithdrawalWireChecks.*;
import com.surprising.wallet.approval.gateway.ApprovalChainRpcClient;
import com.surprising.wallet.common.approval.*;
import com.surprising.wallet.common.chain.EvmFeeModel;
import com.surprising.wallet.common.signing.*;
import java.math.BigInteger;
import java.util.*;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.datatypes.*;
import org.web3j.abi.datatypes.generated.Uint256;
import tools.jackson.databind.*;

/** Native expenditure reservation. Separate L1/operator charges are observed policy estimates, not signed protocol caps. */
final class EvmFeePolicy {
    static JsonNode observe(ApprovalRequest operation,SigningCandidate candidate,ApprovalChainRpcClient rpc,ObjectMapper json) {
        var asset=operation.assetSnapshot();var reads=rpc.open(asset,"DEFAULT");String configured=reads.parameter("feeModel");
        var model=EvmFeeModel.parse(configured);require(model.configValue().equals(configured),"explicit canonical EVM fee model required");
        if(!Set.of("localnet","regtest").contains(asset.network())) {
            String expected=switch(candidate.command().chain()) {case OPTIMISM,BASE,UNICHAIN->"op-stack";case SCROLL->"scroll";case ARBITRUM,ROBINHOOD_CHAIN->"arbitrum-nitro";default->configured;};
            require(expected.equals(configured),"independently installed fee model differs from this EVM chain");
        }
        int bytes=new BigInteger(reads.parameter("maximumSignedTransactionBytes")).intValueExact();require(bytes>=136 && bytes<=524288,"bounded EVM signed transaction size required");
        var chain=reads.rpc("eth_chainId",List.of());require(asset.networkIdentity().equals("eip155:"+Evm7702WithdrawalVerifier.quantity(chain)),"independent EVM fee node chain differs");
        var block=reads.rpc("eth_getBlockByNumber",List.of("latest",false));String number=block.path("number").asText();Evm7702WithdrawalVerifier.quantity(block.path("number"));
        require(block.path("hash").asText().matches("0x[0-9a-f]{64}"),"canonical EVM fee block required");
        BigInteger gas=candidate.command().payloadEncoding().equals(Evm7702SigningPayload.ENCODING)?new BigInteger(reads.parameter("maximumBatchGas"))
                :payload(candidate,EvmSigningPayload.class,json).raw().getGasLimit();require(gas.signum()>0 && gas.bitLength()<=64,"bounded EVM gas required");
        BigInteger quote=BigInteger.ZERO,reserve=BigInteger.ZERO;int headroom=10000;
        if(model.hasSeparateL1Fee()) {
            String oracle=model==EvmFeeModel.SCROLL?"0x5300000000000000000000000000000000000002":"0x420000000000000000000000000000000000000f";
            // The whole size policy is used for all 7702 inner slots before the final signed tuples exist.
            String data=model==EvmFeeModel.OP_STACK?uintCall("getL1FeeUpperBound",BigInteger.valueOf(bytes))
                    :FunctionEncoder.encode(new Function("getL1Fee",List.of(new DynamicBytes(HexFormat.of().parseHex("ff".repeat(bytes)))),List.of()));
            quote=call(reads,oracle,data,number);
            if(model.hasOperatorFee())quote=quote.add(call(reads,oracle,uintCall("getOperatorFee",gas),number));
            reserve=new BigInteger(reads.parameter("maximumExtraFeeAtomic"));headroom=new BigInteger(reads.parameter("extraFeeHeadroomBps")).intValueExact();
            require(reserve.signum()>0 && reserve.bitLength()<=256 && headroom>=10000 && headroom<=1000000
                    && quote.multiply(BigInteger.valueOf(headroom)).compareTo(reserve.multiply(BigInteger.valueOf(10000)))<=0,"independent L1/operator quote exceeds installed fee reserve with headroom");
        }
        var facts=json.createObjectNode().put("feeModel",configured).put("maximumSignedTransactionBytes",bytes).put("maximumGas",gas.toString())
                .put("extraFeeReserveAtomic",reserve.toString()).put("extraFeeQuoteAtomic",quote.toString()).put("headroomBps",headroom);
        facts.set("chainId",chain);facts.set("block",block);return facts;
    }
    static BigInteger verify(List<ApprovalRequest> operations,SigningCandidate candidate,JsonNode facts,BigInteger executionExpense,ObjectMapper json) {
        var asset=operations.getFirst().assetSnapshot();require(facts!=null && asset.networkIdentity().equals("eip155:"+Evm7702WithdrawalVerifier.quantity(facts.path("chainId"))),"original independent EVM fee evidence required");
        String configured=facts.path("feeModel").asText();var model=EvmFeeModel.parse(configured);require(model.configValue().equals(configured),"original EVM fee model missing");
        int bytes=integer(facts.path("maximumSignedTransactionBytes")).intValueExact();require(bytes>=136 && bytes<=524288,"original EVM size policy missing");
        if(candidate.command().payloadEncoding().equals(Evm7702SigningPayload.ENCODING)) {
            var envelope=Evm7702SigningPayload.decode(candidate.command(),json);if(envelope.outer()!=null)require(envelope.outer().unsignedHex().length()/2+68<=bytes,"EIP-7702 final outer exceeds its original whole-batch L1 size reservation");
        } else require(payload(candidate,EvmSigningPayload.class,json).unsignedHex().length()/2+68<=bytes,"EVM final wire exceeds independently reserved L1 size");
        BigInteger extra=integer(facts.path("extraFeeReserveAtomic")),quote=integer(facts.path("extraFeeQuoteAtomic"));
        require(model.hasSeparateL1Fee()?extra.signum()>0 && quote.compareTo(extra)<=0:extra.signum()==0 && quote.signum()==0,"original EVM additional native-fee reservation differs");
        BigInteger total=executionExpense.add(extra),allowance=operations.stream().map(WithdrawalWireChecks::ceiling).reduce(BigInteger.ZERO,BigInteger::add);
        require(total.compareTo(allowance)<=0 && total.compareTo(asset.networkFeeLimit().movePointRight(asset.nativeDecimals()).toBigIntegerExact())<=0,"total execution/L1/operator exposure exceeds reviewed or independent native expense ceiling");
        return total;
    }
    private static String uintCall(String method,BigInteger value) {return FunctionEncoder.encode(new Function(method,List.of(new Uint256(value)),List.of()));}
    private static BigInteger call(ApprovalChainRpcClient.Reads reads,String oracle,String data,String block) {
        var result=reads.rpc("eth_call",List.of(Map.of("to",oracle,"data",data),block));require(result.asText().matches("0x[0-9a-f]{64}"),"independent fee oracle must return one uint256");return Evm7702WithdrawalVerifier.quantity(result);
    }
}
