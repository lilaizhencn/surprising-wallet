package com.surprising.wallet.model.mpc;

import com.surprising.wallet.sdk.signing.MoneroPublicAddressCodec;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.HexFormat;
import java.util.Set;
import java.util.TreeMap;
import tools.jackson.databind.ObjectMapper;

/** Immutable native Monero payment policy; no private wallet material or caller-supplied transaction set. */
public record MoneroSigningPayload(String network,String genesisHash,String sender,String recipient,
                                   BigDecimal amount,BigDecimal customerFee,BigInteger maximumFeeAtomic,int requiredConfirmations) {
    public static final String ENCODING="MONERO_NATIVE_PAYMENT_JSON";
    public MoneroSigningPayload {
        require(network!=null && Set.of("mainnet","testnet","stagenet","regtest").contains(network)
                && genesisHash!=null && genesisHash.matches("[0-9a-f]{64}"),"Monero payment network identity is required");
        require(MoneroPublicAddressCodec.isValid(sender,network) && MoneroPublicAddressCodec.decode(sender).subaddress()
                && MoneroPublicAddressCodec.isValid(recipient,network) && !sender.equals(recipient),"Monero payment source or recipient differs");
        require(amount!=null && atomic(amount).signum()>0 && customerFee!=null && atomic(customerFee).signum()>=0,
                "Monero exact payment amounts are required");
        require(maximumFeeAtomic!=null && maximumFeeAtomic.signum()>0 && maximumFeeAtomic.bitLength()<=64
                && requiredConfirmations>0 && requiredConfirmations<=10000,"Monero fee or confirmation policy exceeds its bound");
    }
    public BigInteger amountAtomic(){return atomic(amount);}
    public String preimageHex(ObjectMapper json) {
        var policy=new TreeMap<String,Object>();policy.put("kind","PAYMENT");policy.put("network",network);policy.put("genesisHash",genesisHash);
        policy.put("sender",sender);policy.put("recipient",recipient);policy.put("amountAtomic",amountAtomic().toString());
        policy.put("maximumFeeAtomic",maximumFeeAtomic.toString());return HexFormat.of().formatHex(json.writeValueAsBytes(policy));
    }
    private static BigInteger atomic(BigDecimal value) {
        var number=value.movePointRight(12).toBigIntegerExact();require(number.signum()>=0 && number.bitLength()<=64,"Monero amount exceeds uint64");return number;
    }
    private static void require(boolean valid,String message){if(!valid)throw new IllegalArgumentException(message);}
}
