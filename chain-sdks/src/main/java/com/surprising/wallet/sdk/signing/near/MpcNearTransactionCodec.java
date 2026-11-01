package com.surprising.wallet.sdk.signing.near;

import com.surprising.wallet.sdk.signing.ThresholdSignatureVerifier;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.bitcoinj.base.Base58;
import tools.jackson.databind.ObjectMapper;

/** Public NEAR Transfer / NEP-141 Borsh bytes, SHA-256 signing message and externally verified Ed25519 witness. */
public final class MpcNearTransactionCodec {
    private static final HexFormat HEX=HexFormat.of();
    private static final ObjectMapper JSON=new ObjectMapper();
    private MpcNearTransactionCodec() { }

    public record Unsigned(String network,String genesisHash,String sender,long nonce,String recipient,BigInteger amountAtomic,
                           String tokenContract,long gas,String memo,long blockHeight,long validUntilHeight,String blockHash,String publicKeyHex) {
        public Unsigned {
            require(Set.of("mainnet","testnet","betanet","localnet","regtest").contains(network),"unsupported NEAR network");
            require(genesisHash!=null && Base58.decode(genesisHash).length==32 && blockHash!=null && Base58.decode(blockHash).length==32,"NEAR genesis or reference block identity is absent");
            sender=account(sender);recipient=account(recipient);
            require(publicKeyHex!=null && publicKeyHex.matches("[0-9a-f]{64}") && sender.equals(address(HEX.parseHex(publicKeyHex))),"NEAR implicit sender differs from joint public key");
            require(!sender.equals(recipient) && nonce>0 && blockHeight>=0 && validUntilHeight>blockHeight,"invalid NEAR nonce, payment or reference lifetime");
            require(amountAtomic!=null && amountAtomic.signum()>0 && amountAtomic.bitLength()<=128,"NEAR payment must be positive u128");
            if(tokenContract==null)require(gas==0 && memo==null,"NEAR native payment contains a contract action");
            else {
                tokenContract=account(tokenContract);
                require(!tokenContract.equals(sender),"NEAR payment signer cannot also be its token contract");
                require(gas>0 && gas<=300_000_000_000_000L,"NEP-141 gas exceeds protocol action bounds");
                require(memo==null || memo.getBytes(StandardCharsets.UTF_8).length<=256,"NEP-141 memo exceeds fixed payment bounds");
            }
        }
    }
    public record Signed(String signedTransactionBase64,String txHash) { }

    /** Fixed NEP-145 registration only; the beneficiary may be the payer itself. */
    public record Registration(String network,String genesisHash,String sender,long nonce,String beneficiary,
            String contract,BigInteger depositAtomic,long gas,long blockHeight,long validUntilHeight,
            String blockHash,String publicKeyHex) {
        public Registration {
            // The existing native envelope validates the shared identity/reference domain without authorizing it.
            new Unsigned(network,genesisHash,sender,nonce,contract,depositAtomic,null,0,null,
                    blockHeight,validUntilHeight,blockHash,publicKeyHex);
            beneficiary=account(beneficiary);contract=account(contract);
            require(gas>0 && gas<=30_000_000_000_000L,"NEP-145 registration gas exceeds fixed bounds");
            require(depositAtomic.compareTo(BigInteger.TEN.pow(23))<=0,"NEP-145 registration deposit exceeds 0.1 NEAR");
        }
    }
    public static byte[] arguments(Registration u) {
        return JSON.writeValueAsBytes(JSON.createObjectNode().put("account_id",u.beneficiary()).put("registration_only",true));
    }
    public static byte[] transactionBytes(Registration u) {
        var bytes=new Writer();bytes.string(u.sender());bytes.u8(0);bytes.bytes(HEX.parseHex(u.publicKeyHex()));
        bytes.u64(u.nonce());bytes.string(u.contract());bytes.bytes(Base58.decode(u.blockHash()));bytes.u32(1);
        bytes.u8(2);bytes.string("storage_deposit");bytes.vector(arguments(u));bytes.u64(u.gas());bytes.u128(u.depositAtomic());
        return bytes.out.toByteArray();
    }
    public static byte[] signingBytes(Registration u) {
        try{return MessageDigest.getInstance("SHA-256").digest(transactionBytes(u));}
        catch(java.security.NoSuchAlgorithmException unavailable){throw new IllegalStateException(unavailable);}
    }
    public static String transactionHash(Registration u){return Base58.encode(signingBytes(u));}
    public static String publicKey(Registration u){return "ed25519:"+Base58.encode(HEX.parseHex(u.publicKeyHex()));}
    public static Signed assemble(Registration u,byte[] signature) {
        require(signature!=null && ThresholdSignatureVerifier.ed25519(HEX.parseHex(u.publicKeyHex()),signingBytes(u),signature),"NEP-145 witness differs from fixed registration");
        var bytes=new Writer();bytes.bytes(transactionBytes(u));bytes.u8(0);bytes.bytes(signature);
        return new Signed(Base64.getEncoder().encodeToString(bytes.out.toByteArray()),transactionHash(u));
    }
    public static void verifySelected(Registration u,Signed signed) {
        require(signed!=null && signed.signedTransactionBase64()!=null,"NEP-145 selected wire is absent");
        byte[] wire=Base64.getDecoder().decode(signed.signedTransactionBase64());int length=transactionBytes(u).length;
        require(wire.length==length+65 && wire[length]==0,"NEP-145 selected witness scheme or length differs");
        require(assemble(u,Arrays.copyOfRange(wire,length+1,wire.length)).equals(signed),"NEP-145 selected wire/hash differs");
    }

    public static byte[] transactionBytes(Unsigned u) {
        var bytes=new Writer();bytes.string(u.sender());bytes.u8(0);bytes.bytes(HEX.parseHex(u.publicKeyHex()));
        bytes.u64(u.nonce());bytes.string(u.tokenContract()==null?u.recipient():u.tokenContract());bytes.bytes(Base58.decode(u.blockHash()));bytes.u32(1);
        if(u.tokenContract()==null){bytes.u8(3);bytes.u128(u.amountAtomic());}
        else {
            bytes.u8(2);bytes.string("ft_transfer");bytes.vector(arguments(u));bytes.u64(u.gas());bytes.u128(BigInteger.ONE);
        }
        return bytes.out.toByteArray();
    }
    public static byte[] signingBytes(Unsigned u) {
        try{return MessageDigest.getInstance("SHA-256").digest(transactionBytes(u));}
        catch(java.security.NoSuchAlgorithmException unavailable){throw new IllegalStateException(unavailable);}
    }
    public static String transactionHash(Unsigned u){return Base58.encode(signingBytes(u));}
    public static byte[] arguments(Unsigned u) {
        require(u.tokenContract()!=null,"NEAR native payment has no contract arguments");
        var args=JSON.createObjectNode().put("receiver_id",u.recipient()).put("amount",u.amountAtomic().toString());if(u.memo()!=null)args.put("memo",u.memo());
        return JSON.writeValueAsBytes(args);
    }
    public static Signed assemble(Unsigned u,byte[] signature) {
        require(signature!=null && ThresholdSignatureVerifier.ed25519(HEX.parseHex(u.publicKeyHex()),signingBytes(u),signature),"NEAR external witness differs from fixed Borsh transaction");
        var bytes=new Writer();bytes.bytes(transactionBytes(u));bytes.u8(0);bytes.bytes(signature);
        return new Signed(Base64.getEncoder().encodeToString(bytes.out.toByteArray()),transactionHash(u));
    }
    public static void verifySelected(Unsigned u,Signed signed) {
        require(signed!=null && signed.signedTransactionBase64()!=null,"NEAR selected wire is absent");
        byte[] wire=Base64.getDecoder().decode(signed.signedTransactionBase64());int length=transactionBytes(u).length;
        require(wire.length==length+65 && wire[length]==0,"NEAR selected witness scheme or wire length differs");
        require(assemble(u,Arrays.copyOfRange(wire,length+1,wire.length)).equals(signed),"NEAR selected wire or hash differs from fixed payment");
    }
    public static String address(byte[] publicKey){require(publicKey!=null && publicKey.length==32,"NEAR public key must be exactly 32 bytes");return HEX.formatHex(publicKey);}
    public static String publicKey(Unsigned u){return "ed25519:"+Base58.encode(HEX.parseHex(u.publicKeyHex()));}
    public static String account(String value){require(value!=null && value.length()>=2 && value.length()<=64 && value.matches("[a-z0-9]+(?:[-_.][a-z0-9]+)*"),"invalid NEAR account ID");return value;}
    private static final class Writer {
        private final ByteArrayOutputStream out=new ByteArrayOutputStream();
        void u8(int value){out.write(value);}
        void u32(int value){bytes(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array());}
        void u64(long value){bytes(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array());}
        void u128(BigInteger value){byte[] result=new byte[16],source=value.toByteArray();for(int i=0;i<Math.min(16,source.length);i++)result[i]=source[source.length-1-i];bytes(result);}
        void string(String value){vector(value.getBytes(StandardCharsets.UTF_8));}
        void vector(byte[] value){u32(value.length);bytes(value);}
        void bytes(byte[] value){out.writeBytes(value);}
    }
    private static void require(boolean condition,String message){if(!condition)throw new IllegalArgumentException(message);}
}
