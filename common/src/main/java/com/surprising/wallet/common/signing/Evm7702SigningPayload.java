package com.surprising.wallet.common.signing;

import com.surprising.wallet.common.chain.ChainAddressRecord;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.HexFormat;
import java.util.List;
import tools.jackson.databind.*;

/** One original whole-batch plan shared by authorization, operation and outer relayer signature slots. */
public record Evm7702SigningPayload(Plan plan,Outer outer) {
    public static final String ENCODING="EIP7702_SIGNING_JSON";
    public Evm7702SigningPayload {if(plan==null)throw new IllegalArgumentException("whole EIP-7702 plan required");}
    public record Plan(long chainId,BigInteger relayerNonce,ChainAddressRecord relayer,String preparationJson,List<String> references) {
        public Plan {
            references=List.copyOf(references);
            if(chainId<=0 || relayerNonce==null || relayerNonce.signum()<0 || relayerNonce.bitLength()>64 || relayer==null
                    || preparationJson==null || preparationJson.isBlank() || preparationJson.length()>512*1024 || references.isEmpty() || references.size()>100
                    || references.stream().anyMatch(r->r==null || r.isBlank() || r.length()>192) || references.stream().distinct().count()!=references.size())
                throw new IllegalArgumentException("invalid immutable EIP-7702 whole-batch plan");
        }
    }
    public record Outer(String unsignedHex,long estimatedGas,BigDecimal reservedFee) {
        public Outer {if(unsignedHex==null || !unsignedHex.matches("(?:[0-9a-f]{2})+") || unsignedHex.length()>1024*1024
                || estimatedGas<=0 || reservedFee==null || reservedFee.signum()<=0)throw new IllegalArgumentException("invalid EIP-7702 selected outer envelope");}
    }
    public static Evm7702SigningPayload decode(MpcSignCommand command,ObjectMapper json) {
        if(!command.chain().isEvm() || !command.payloadEncoding().equals(ENCODING))throw new IllegalArgumentException("EIP-7702 whole-batch encoding required");
        return json.readerFor(Evm7702SigningPayload.class).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(HexFormat.of().parseHex(command.payloadHex()));
    }
}
