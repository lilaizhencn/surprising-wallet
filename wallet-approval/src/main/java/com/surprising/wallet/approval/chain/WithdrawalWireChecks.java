package com.surprising.wallet.approval.chain;

import com.surprising.wallet.approval.model.RegisteredMpcKey;
import com.surprising.wallet.common.approval.*;
import com.surprising.wallet.common.signing.MpcClient;
import java.math.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import tools.jackson.databind.*;

/** Shared mechanical checks only. Payment semantics and replay domains remain explicit in each chain verifier. */
final class WithdrawalWireChecks {
    private WithdrawalWireChecks() { }
    static <T> T payload(SigningCandidate candidate,Class<T> type,ObjectMapper json) {
        return json.readerFor(type).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readValue(HexFormat.of().parseHex(candidate.command().payloadHex()));
    }
    static void owner(ApprovalRequest reviewed,RegisteredMpcKey key,SigningCandidate candidate,MpcClient.Algorithm algorithm,ObjectMapper json) {
        var intent=reviewed.intent();var command=candidate.command();var execution=candidate.execution();
        require(intent.tenantId().equals(key.tenantId()) && intent.tenantId().equals(command.tenantId())
                && intent.chain().equals(command.chain().name()) && intent.businessReference().equals(command.businessReference())
                && key.keyId().equals(command.keyId()) && key.epoch()==command.keyEpoch()
                && key.state().equals("ACTIVE") && key.keyGroup().equals(command.chain().name())
                && key.algorithm().equals(algorithm.name()) && execution.algorithm()==algorithm && execution.operation()==MpcClient.Operation.SIGN
                && reviewed.intentHash().equals(reviewed.assetSnapshot().bind(intent,json)),"independent reviewed payment, key or native execution differs");
    }
    static void amount(ApprovalRequest reviewed,String symbol,BigDecimal amount,BigDecimal customerFee,int decimals) {
        require(reviewed.intent().assetSymbol().equals(symbol) && amount!=null && amount.compareTo(reviewed.intent().amount())==0
                && customerFee!=null && customerFee.signum()>=0 && decimals==reviewed.assetSnapshot().decimals(),"payment metadata differs from the independently reviewed amount/asset");
    }
    static BigInteger atomic(ApprovalRequest reviewed){return reviewed.intent().amount().movePointRight(reviewed.assetSnapshot().decimals()).toBigIntegerExact();}
    static BigInteger fee(ApprovalRequest reviewed,BigInteger fee) {
        var asset=reviewed.assetSnapshot();
        require(fee!=null && fee.signum()>=0 && fee.compareTo(reviewed.intent().maximumNetworkFee().movePointRight(asset.nativeDecimals()).toBigIntegerExact())<=0
                && fee.compareTo(asset.networkFeeLimit().movePointRight(asset.nativeDecimals()).toBigIntegerExact())<=0,"network expenditure exceeds independent or reviewed fee ceiling");
        return fee;
    }
    static BigInteger ceiling(ApprovalRequest reviewed){return reviewed.intent().maximumNetworkFee().movePointRight(reviewed.assetSnapshot().nativeDecimals()).toBigIntegerExact();}
    static void message(SigningCandidate candidate,byte[] expected) {
        require(MessageDigest.isEqual(expected,HexFormat.of().parseHex(candidate.execution().messageHex())),"native signer message differs from the canonical approved transaction");
    }
    static BigInteger integer(JsonNode value) {
        require(value!=null && (value.isString() || value.isIntegralNumber()) && value.asText().matches("0|[1-9][0-9]{0,77}"),"independent chain atomic integer is absent or malformed");
        return new BigInteger(value.asText());
    }
    static void require(boolean value,String message){if(!value)throw new IllegalArgumentException(message);}
}
