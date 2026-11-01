package com.surprising.wallet.approval.chain;

import com.surprising.wallet.approval.model.RegisteredMpcKey;
import com.surprising.wallet.common.approval.ApprovalRequest;
import com.surprising.wallet.common.approval.SigningCandidate;
import com.surprising.wallet.common.signing.AptosSigningPayload;
import com.surprising.wallet.common.signing.MpcClient;
import com.surprising.wallet.sdk.signing.aptos.MpcAptosTransactionCodec;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

/** Canonical legacy Ed25519, sequence-number APT/primary-FA transfer only; no scripts, sponsored or orderless transactions. */
public final class AptosWithdrawalVerifier {
    private AptosWithdrawalVerifier() { }
    public static ApprovalWithdrawalVerifiers.Verified verify(ApprovalRequest reviewed,RegisteredMpcKey key,SigningCandidate candidate,Instant now,ObjectMapper json) {
        var intent=reviewed.intent();var asset=reviewed.assetSnapshot();var execution=candidate.execution();
        require(intent.chain().equals("APTOS") && asset.chain().equals("APTOS") && asset.nativeSymbol().equals("APT") && asset.nativeDecimals()==8
                && key.tenantId().equals(intent.tenantId()) && execution.tenantId().equals(intent.tenantId())
                && key.keyId().equals(execution.keyId()) && key.epoch()==execution.epoch() && key.keyGroup().equals("APTOS")
                && key.state().equals("ACTIVE") && key.algorithm().equals("ED25519") && execution.algorithm()==MpcClient.Algorithm.ED25519 && execution.operation()==MpcClient.Operation.SIGN
                && key.publicKeyHex()!=null && key.publicKeyHex().matches("[0-9a-f]{64}"),"Aptos execution differs from its independent tenant/key/network domain");
        require(reviewed.intentHash().equals(asset.bind(intent,json)),"Aptos review hash differs from its independent frozen asset facts");
        var payload=json.readerFor(AptosSigningPayload.class).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .<AptosSigningPayload>readValue(HexFormat.of().parseHex(candidate.command().payloadHex()));
        require(payload!=null && payload.unsigned()!=null && payload.amount()!=null && payload.customerFee()!=null
                && payload.symbol()!=null && payload.symbol().equals(intent.assetSymbol()) && payload.amount().compareTo(intent.amount())==0 && payload.customerFee().signum()>=0
                && payload.decimals()==asset.decimals(),"Aptos payment metadata differs from its reviewed exact amount and independent precision");
        var unsigned=payload.unsigned();
        require(asset.networkIdentity().equals("aptos:"+unsigned.chainId()) && unsigned.publicKeyHex().equals(key.publicKeyHex())
                && unsigned.sender().equals(MpcAptosTransactionCodec.address(HexFormat.of().parseHex(key.publicKeyHex())))
                && unsigned.recipient().equals(MpcAptosTransactionCodec.canonicalAddress(intent.recipient()))
                && Objects.equals(unsigned.metadata(),asset.tokenIdentifier()),"Aptos sender, recipient, network or asset metadata differs");
        BigInteger amount;
        try { amount=intent.amount().movePointRight(asset.decimals()).toBigIntegerExact(); }
        catch(ArithmeticException invalid) { throw new IllegalArgumentException("Aptos amount exceeds independent precision"); }
        require(amount.equals(BigInteger.valueOf(unsigned.amountAtomic())),"Aptos actual BCS transfer amount differs from the approved intent");
        require(unsigned.expiration()>now.getEpochSecond() && unsigned.expiration()<=now.plusSeconds(3600).getEpochSecond(),"Aptos transaction expiry is outside the independent one-hour signing window");
        // Encode only the fixed framework transfer, then compare every byte actually passed to the native signer.
        require(MessageDigest.isEqual(MpcAptosTransactionCodec.signingBytes(unsigned),HexFormat.of().parseHex(execution.messageHex())),
                "Aptos native message differs from its canonical approved BCS transaction");
        BigInteger fee=BigInteger.valueOf(unsigned.maxGasAmount()).multiply(BigInteger.valueOf(unsigned.gasUnitPrice()));
        require(fee.compareTo(intent.maximumNetworkFee().movePointRight(8).toBigIntegerExact())<=0
                && fee.compareTo(asset.networkFeeLimit().movePointRight(8).toBigIntegerExact())<=0,"Aptos maximum gas charge exceeds the reviewed or independent fee ceiling");
        return new ApprovalWithdrawalVerifiers.Verified("APTOS:"+asset.networkIdentity()+":"+unsigned.sender()+":"+unsigned.sequence(),fee,true);
    }
    private static void require(boolean value,String message) { if(!value)throw new IllegalArgumentException(message); }
}
