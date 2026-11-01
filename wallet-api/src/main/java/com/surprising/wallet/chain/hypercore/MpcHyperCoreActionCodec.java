package com.surprising.wallet.chain.hypercore;

import com.surprising.wallet.sdk.signing.ThresholdSignatureVerifier;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Set;
import org.bitcoinj.crypto.ECKey;
import org.web3j.crypto.ECDSASignature;
import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;
import org.web3j.utils.Numeric;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Hyperliquid user-signed EIP-712 transfers around an external MPC signature. */
public final class MpcHyperCoreActionCodec {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final HyperCoreSigner TYPED=new HyperCoreSigner(JSON);
    private static final Set<String> USD_FIELDS=Set.of("destination","amount","time","type","signatureChainId","hyperliquidChain");
    private static final Set<String> SPOT_FIELDS=Set.of("destination","amount","time","type","token","signatureChainId","hyperliquidChain");
    private MpcHyperCoreActionCodec() { }
    public record Unsigned(String owner,String actionJson) {
        public Unsigned {
            if(owner==null || !owner.matches("0x[0-9a-f]{40}") || actionJson==null || actionJson.isBlank() || actionJson.length()>16384)
                throw new IllegalArgumentException("invalid immutable HyperCore action");
        }
    }
    public static Unsigned prepare(String owner,String recipient,BigDecimal amount,String token,long nonce,boolean mainnet) {
        var action=JSON.createObjectNode().put("destination",recipient).put("amount",amount.stripTrailingZeros().toPlainString()).put("time",nonce)
                .put("type",token==null?"usdSend":"spotSend");
        if(token!=null)action.put("token",token);
        action.put("signatureChainId",HyperCoreSigner.SIGNATURE_CHAIN_ID).put("hyperliquidChain",mainnet?"Mainnet":"Testnet");
        var result=new Unsigned(owner,action.toString());restore(result);return result;
    }
    public static ObjectNode restore(Unsigned unsigned) {
        var action=JSON.readValue(unsigned.actionJson(),ObjectNode.class);
        boolean usd="usdSend".equals(action.path("type").asText());
        require(usd || "spotSend".equals(action.path("type").asText()),"unsupported HyperCore MPC action");
        require(action.propertyNames().equals(usd?USD_FIELDS:SPOT_FIELDS) && action.toString().equals(unsigned.actionJson()),"HyperCore action has unrecognized or noncanonical fields");
        require(action.path("destination").asText().matches("0x[0-9a-f]{40}") && action.path("time").isIntegralNumber()
                        && action.path("time").canConvertToLong() && action.path("time").asLong()>0
                        && HyperCoreSigner.SIGNATURE_CHAIN_ID.equals(action.path("signatureChainId").asText())
                        && Set.of("Mainnet","Testnet").contains(action.path("hyperliquidChain").asText()),"HyperCore destination, nonce or signing domain differs");
        String amount=action.path("amount").asText();BigDecimal value=new BigDecimal(amount);
        require(action.path("amount").isTextual() && value.signum()>0 && amount.equals(value.stripTrailingZeros().toPlainString())
                && (usd || (action.path("token").isTextual() && action.path("token").asText().matches("[A-Za-z0-9._-]+:0x[0-9a-f]{32}"))),"HyperCore amount or wire token is invalid");
        return action;
    }
    public static byte[] digest(Unsigned unsigned) {
        var action=restore(unsigned);boolean mainnet="Mainnet".equals(action.path("hyperliquidChain").asText());
        return "usdSend".equals(action.path("type").asText())?TYPED.hashUsdSend(action,mainnet):TYPED.hashSpotSend(action,mainnet);
    }
    public static ObjectNode assemble(Unsigned unsigned,byte[] publicKey,byte[] derSignature) {
        byte[] digest=digest(unsigned);
        BigInteger point=Sign.publicFromPoint(ECKey.fromPublicOnly(publicKey).decompress().getPubKey());
        require(unsigned.owner().equals("0x"+Keys.getAddress(point)) && ThresholdSignatureVerifier.ecdsa(publicKey,digest,derSignature),
                "HyperCore MPC signature or joint owner differs from immutable action");
        ECKey.ECDSASignature der;
        try {der=ECKey.ECDSASignature.decodeFromDER(derSignature);}
        catch(org.bitcoinj.crypto.SignatureDecodeException invalid){throw new IllegalArgumentException("invalid HyperCore MPC signature",invalid);}
        var signature=Sign.createSignatureData(new ECDSASignature(der.r,der.s).toCanonicalised(),point,digest);
        int v=new BigInteger(1,signature.getV()).intValueExact();require(v==27 || v==28,"unsupported HyperCore recovery identifier");
        var body=JSON.createObjectNode();body.set("action",restore(unsigned));body.put("nonce",body.path("action").path("time").asLong());
        body.set("signature",JSON.createObjectNode().put("r",Numeric.toHexString(signature.getR())).put("s",Numeric.toHexString(signature.getS())).put("v",v));return body;
    }
    private static void require(boolean condition,String message){if(!condition)throw new IllegalArgumentException(message);}
}
