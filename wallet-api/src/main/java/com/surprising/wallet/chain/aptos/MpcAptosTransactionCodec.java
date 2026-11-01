package com.surprising.wallet.chain.aptos;

import com.surprising.wallet.sdk.signing.ThresholdSignatureVerifier;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Canonical public-only APT/primary-FA payments and verified external legacy Ed25519 authenticator. */
public final class MpcAptosTransactionCodec {
    /** Ed25519 authentication key: SHA3-256(public key || scheme zero). */
    public static String address(byte[] publicKey) {
        require(publicKey != null && publicKey.length == 32, "Aptos public key must be 32 bytes");
        var encoded = new ByteArrayOutputStream();
        encoded.writeBytes(publicKey);
        encoded.write(0);
        return AptosHex.withPrefix(sha3(encoded.toByteArray()));
    }
    private static final HexFormat HEX=HexFormat.of();
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final byte[] RAW_DOMAIN=sha3("APTOS::RawTransaction".getBytes(StandardCharsets.US_ASCII));
    private static final byte[] TRANSACTION_DOMAIN=sha3("APTOS::Transaction".getBytes(StandardCharsets.US_ASCII));
    private MpcAptosTransactionCodec() { }
    public record Unsigned(String sender,long sequence,String recipient,long amountAtomic,String metadata,
                           long maxGasAmount,long gasUnitPrice,long expiration,int chainId,String publicKeyHex) {
        public Unsigned {
            sender=canonicalAddress(sender);recipient=canonicalAddress(recipient);if(metadata!=null)metadata=canonicalAddress(metadata);
            require(!sender.equals(recipient) && sequence>=0 && amountAtomic>0 && maxGasAmount>0 && gasUnitPrice>0
                    && expiration>0 && chainId>0 && chainId<=255,"invalid Aptos payment, sequence, fee or network");
            require(publicKeyHex!=null && publicKeyHex.matches("[0-9a-f]{64}"),"Aptos joint public key must be 32 bytes");
            require(sender.equals(address(HEX.parseHex(publicKeyHex))),"Aptos sender differs from the joint legacy authentication key");
        }
    }
    public record Signed(String transactionHex,String txId,String json) { }
    public static String primaryStore(String owner,String metadata) {
        var out=new ByteArrayOutputStream();out.writeBytes(AptosHex.addressBytes(canonicalAddress(owner)));out.writeBytes(AptosHex.addressBytes(canonicalAddress(metadata)));out.write(0xfc);
        return AptosHex.withPrefix(sha3(out.toByteArray()));
    }
    public static byte[] raw(Unsigned unsigned) {
        String module=unsigned.metadata()==null?"0x1::aptos_account":"0x1::primary_fungible_store";
        var args=new ArrayList<byte[]>();if(unsigned.metadata()!=null)args.add(AptosBcs.addressArg(unsigned.metadata()));
        args.add(AptosBcs.addressArg(unsigned.recipient()));args.add(AptosBcs.u64Arg(unsigned.amountAtomic()));
        return AptosBcs.rawEntryFunctionTransaction(unsigned.sender(),unsigned.sequence(),new AptosBcs.EntryFunctionPayload(module,"transfer",
                unsigned.metadata()==null?List.of():List.of("0x1::fungible_asset::Metadata"),args),unsigned.maxGasAmount(),unsigned.gasUnitPrice(),unsigned.expiration(),unsigned.chainId());
    }
    public static byte[] signingBytes(Unsigned unsigned) {var out=new ByteArrayOutputStream();out.writeBytes(RAW_DOMAIN);out.writeBytes(raw(unsigned));return out.toByteArray();}
    public static Signed assemble(Unsigned unsigned,byte[] suppliedSignature) {
        require(suppliedSignature!=null,"Aptos required signature is absent");byte[] signature=suppliedSignature.clone();byte[] publicKey=HEX.parseHex(unsigned.publicKeyHex());
        require(ThresholdSignatureVerifier.ed25519(publicKey,signingBytes(unsigned),signature),"Aptos MPC signature differs from fixed raw and public key");
        var wire=new ByteArrayOutputStream();wire.writeBytes(raw(unsigned));wire.write(0); // TransactionAuthenticator::Ed25519
        wire.write(32);wire.writeBytes(publicKey);wire.write(64);wire.writeBytes(signature);
        var hash=new ByteArrayOutputStream();hash.writeBytes(TRANSACTION_DOMAIN);hash.write(0);hash.writeBytes(wire.toByteArray()); // Transaction::UserTransaction
        var request=unsignedJson(unsigned);var auth=JSON.createObjectNode().put("type","ed25519_signature")
                .put("public_key",AptosHex.withPrefix(publicKey)).put("signature",AptosHex.withPrefix(signature));request.set("signature",auth);
        return new Signed(HEX.formatHex(wire.toByteArray()),AptosHex.withPrefix(sha3(hash.toByteArray())),JSON.writeValueAsString(request));
    }
    public static ObjectNode unsignedJson(Unsigned unsigned) {
        var request=JSON.createObjectNode().put("sender",unsigned.sender()).put("sequence_number",Long.toString(unsigned.sequence()))
                .put("max_gas_amount",Long.toString(unsigned.maxGasAmount())).put("gas_unit_price",Long.toString(unsigned.gasUnitPrice())).put("expiration_timestamp_secs",Long.toString(unsigned.expiration()));
        var payload=JSON.createObjectNode().put("type","entry_function_payload").put("function",unsigned.metadata()==null?"0x1::aptos_account::transfer":"0x1::primary_fungible_store::transfer");
        var types=JSON.createArrayNode();if(unsigned.metadata()!=null)types.add("0x1::fungible_asset::Metadata");payload.set("type_arguments",types);
        var args=JSON.createArrayNode();if(unsigned.metadata()!=null)args.add(unsigned.metadata());args.add(unsigned.recipient());args.add(Long.toString(unsigned.amountAtomic()));payload.set("arguments",args);
        request.set("payload",payload);return request;
    }
    public static String canonicalAddress(String value) {require(value!=null && value.matches("(?i)0x[0-9a-f]{1,64}"),"invalid Aptos address");return AptosHex.normalizeAddress(value);}
    private static byte[] sha3(byte[] bytes) {try{return MessageDigest.getInstance("SHA3-256").digest(bytes);}catch(NoSuchAlgorithmException absent){throw new IllegalStateException(absent);}}
    private static void require(boolean condition,String message){if(!condition)throw new IllegalArgumentException(message);}
}
