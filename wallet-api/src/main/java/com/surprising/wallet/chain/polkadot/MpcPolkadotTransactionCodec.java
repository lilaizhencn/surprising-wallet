package com.surprising.wallet.chain.polkadot;

import com.surprising.wallet.sdk.signing.ThresholdSignatureVerifier;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.bitcoinj.base.Base58;
import org.bouncycastle.jcajce.provider.digest.Blake2b;

/** Public SCALE V4 payments. Unknown extensions are rejected; no fee-asset conversion or local signing key. */
public final class MpcPolkadotTransactionCodec {
    private static final HexFormat HEX=HexFormat.of();
    private static final BigInteger U32=BigInteger.ONE.shiftLeft(32).subtract(BigInteger.ONE);
    private static final Set<String> EMPTY=Set.of("AuthorizeCall","CheckNonZeroSender","CheckWeight","WeightReclaim","StorageWeightReclaim","EthSetOrigin","PrevalidateAttests");
    private static final Set<String> FIELDS=Set.of("CheckSpecVersion","CheckTxVersion","CheckGenesis","CheckMortality","CheckNonce","ChargeTransactionPayment","ChargeAssetTxPayment","CheckMetadataHash");
    private static final byte[] PREFIX="SS58PRE".getBytes(StandardCharsets.US_ASCII);
    private MpcPolkadotTransactionCodec() { }
    public enum Role { RELAY,ASSET_HUB }
    public record CallIndices(String keepAlive,String allowDeath) {
        public CallIndices {require(keepAlive!=null && keepAlive.matches("0x[0-9a-f]{4}") && allowDeath!=null && allowDeath.matches("0x[0-9a-f]{4}") && !keepAlive.equals(allowDeath),"Polkadot typed payment call indices are absent or ambiguous");}
    }
    public record Unsigned(String network,Role role,String genesisHash,long specVersion,long transactionVersion,
                           String sender,String publicKeyHex,String recipient,BigInteger amountAtomic,String assetId,
                           long nonce,int eraPeriod,long blockHeight,String blockHash,List<String> extensions,CallIndices callIndices,boolean keepAlive) {
        public Unsigned {
            require(network!=null && !network.isBlank() && network.length()<=32 && role!=null,"Polkadot network role is required");
            hash(genesisHash);hash(blockHash);require(specVersion>0 && specVersion<=0xffff_ffffL && transactionVersion>=0 && transactionVersion<=0xffff_ffffL,"Polkadot runtime versions exceed u32");
            require(publicKeyHex!=null && publicKeyHex.matches("[0-9a-f]{64}") && Arrays.equals(account(sender),HEX.parseHex(publicKeyHex)),"Polkadot sender differs from joint public key");
            require(!Arrays.equals(account(sender),account(recipient)),"Polkadot payment cannot target its sender");
            require(amountAtomic!=null && amountAtomic.signum()>0 && amountAtomic.bitLength()<=128,"Polkadot principal must be positive u128");
            if(assetId!=null)require(role==Role.ASSET_HUB && assetId.matches("0|[1-9][0-9]{0,9}") && new BigInteger(assetId).compareTo(U32)<=0,"Polkadot asset id must be Asset Hub u32");
            require(nonce>=0 && nonce<=0xffff_ffffL && blockHeight>=0 && blockHeight<=0xffff_ffffL,"Polkadot nonce or reference height exceeds u32");
            require(eraPeriod>=4 && eraPeriod<=4096 && Integer.bitCount(eraPeriod)==1,"Polkadot payment requires an exact bounded mortal era");
            extensions=List.copyOf(extensions);var unique=new HashSet<>(extensions);
            require(unique.size()==extensions.size() && extensions.size()<=20 && extensions.stream().allMatch(name->EMPTY.contains(name)||FIELDS.contains(name)),"unsupported Polkadot transaction extension");
            require(unique.containsAll(Set.of("CheckSpecVersion","CheckTxVersion","CheckGenesis","CheckMortality","CheckNonce"))
                    && (unique.contains("ChargeTransactionPayment")^unique.contains("ChargeAssetTxPayment")),"Polkadot version, network, era, nonce or native fee domain is incomplete");
            Objects.requireNonNull(callIndices,"Polkadot typed payment calls are required");
        }
        public long validUntilHeight(){return Math.addExact(blockHeight,eraPeriod);}
    }
    public record Signed(String extrinsicHex,String txHash) { }
    public static byte[] callBytes(Unsigned u) {
        var out=new Writer();out.bytes(HEX.parseHex((u.keepAlive()?u.callIndices().keepAlive():u.callIndices().allowDeath()).substring(2)));
        if(u.assetId()!=null)out.compact(new BigInteger(u.assetId()));
        out.u8(0);out.bytes(account(u.recipient()));out.compact(u.amountAtomic());return out.data();
    }
    public static byte[] extra(Unsigned u) {
        var out=new Writer();
        for(String name:u.extensions())switch(name) {
            case "CheckMortality" -> {int encoded=(Integer.numberOfTrailingZeros(u.eraPeriod())-1)|((int)(u.blockHeight()%u.eraPeriod())<<4);out.u16(encoded);}
            case "CheckNonce" -> out.compact(BigInteger.valueOf(u.nonce()));
            case "ChargeTransactionPayment" -> out.compact(BigInteger.ZERO);
            case "ChargeAssetTxPayment" -> {out.compact(BigInteger.ZERO);out.u8(0);}
            case "CheckMetadataHash" -> out.u8(0);
            default -> { }
        }
        return out.data();
    }
    public static byte[] payloadBytes(Unsigned u) {
        var out=new Writer();out.bytes(callBytes(u));out.bytes(extra(u));
        for(String name:u.extensions())switch(name) {
            case "CheckSpecVersion" -> out.u32(u.specVersion());
            case "CheckTxVersion" -> out.u32(u.transactionVersion());
            case "CheckGenesis" -> out.bytes(hash(u.genesisHash()));
            case "CheckMortality" -> out.bytes(hash(u.blockHash()));
            case "CheckMetadataHash" -> out.u8(0);
            default -> { }
        }
        return out.data();
    }
    public static byte[] signingBytes(Unsigned u){byte[] bytes=payloadBytes(u);return bytes.length>256?blake(bytes):bytes;}
    public static Signed assemble(Unsigned u,byte[] signature) {
        require(signature!=null && ThresholdSignatureVerifier.ed25519(HEX.parseHex(u.publicKeyHex()),signingBytes(u),signature),"Polkadot external Ed25519 witness differs from fixed SCALE domain");
        var body=new Writer();body.u8(0x84);body.u8(0);body.bytes(HEX.parseHex(u.publicKeyHex()));body.u8(0);body.bytes(signature);body.bytes(extra(u));body.bytes(callBytes(u));
        byte[] inner=body.data();var out=new Writer();out.compact(BigInteger.valueOf(inner.length));out.bytes(inner);byte[] encoded=out.data();
        return new Signed("0x"+HEX.formatHex(encoded),"0x"+HEX.formatHex(blake(encoded)));
    }
    public static void verifySelected(Unsigned u,Signed selected) {
        require(selected!=null && selected.extrinsicHex()!=null && selected.extrinsicHex().matches("0x[0-9a-f]+"),"Polkadot selected wire is absent or malformed");
        byte[] wire=HEX.parseHex(selected.extrinsicHex().substring(2));int length=99+extra(u).length+callBytes(u).length;int prefix=new Writer().compact(BigInteger.valueOf(length)).data().length;
        require(wire.length==prefix+length && wire[prefix]==(byte)0x84 && wire[prefix+1]==0 && wire[prefix+34]==0,"Polkadot selected V4/account/signature variant differs");
        require(assemble(u,Arrays.copyOfRange(wire,prefix+35,prefix+99)).equals(selected),"Polkadot selected raw/hash differs from exact payment");
    }
    public static String address(byte[] publicKey,int prefix) {
        require(publicKey!=null && publicKey.length==32 && prefix>=0 && prefix<=16383 && prefix!=46 && prefix!=47,"invalid Polkadot public key or SS58 prefix");var out=new Writer();
        if(prefix<64)out.u8(prefix);else{out.u8(((prefix&0xfc)>>2)|0x40);out.u8((prefix>>8)|((prefix&3)<<6));}out.bytes(publicKey);byte[] payload=out.data();var checksum=new Writer();checksum.bytes(PREFIX);checksum.bytes(payload);byte[] digest=new Blake2b.Blake2b512().digest(checksum.data());out.u8(digest[0]);out.u8(digest[1]);return Base58.encode(out.data());
    }
    public static byte[] account(String address) {
        require(address!=null && address.length()>=47 && address.length()<=50,"invalid Polkadot address length");byte[] decoded=Base58.decode(address);require(decoded.length==35||decoded.length==36,"invalid SS58 AccountId32 encoding");
        int first=decoded[0]&255,prefixLength=first<64?1:2;require(first<128 && decoded.length==prefixLength+34,"invalid SS58 address variant");
        int prefix=prefixLength==1?first:((first&0x3f)<<2)|((decoded[1]&0x3f)<<8)|((decoded[1]&0xc0)>>6);byte[] publicKey=Arrays.copyOfRange(decoded,prefixLength,prefixLength+32);
        require(address(publicKey,prefix).equals(address),"Polkadot SS58 checksum or prefix encoding differs");return publicKey;
    }
    private static byte[] hash(String value){require(value!=null && value.matches("0x[0-9a-f]{64}"),"Polkadot genesis/reference hash must be exactly 32 bytes");return HEX.parseHex(value.substring(2));}
    private static byte[] blake(byte[] bytes){return new Blake2b.Blake2b256().digest(bytes);}
    private static final class Writer {
        final ByteArrayOutputStream out=new ByteArrayOutputStream();
        Writer compact(BigInteger value) {
            require(value.signum()>=0 && value.bitLength()<=128,"SCALE compact value exceeds u128");
            if(value.bitLength()<=6)u8(value.intValue()<<2);
            else if(value.bitLength()<=14)u16((value.intValue()<<2)|1);
            else if(value.bitLength()<=30)u32((value.longValue()<<2)|2);
            else{int length=(value.bitLength()+7)/8;u8(((length-4)<<2)|3);for(int i=0;i<length;i++)u8(value.shiftRight(i*8).intValue());}
            return this;
        }
        void u8(int value){out.write(value);}
        void u16(int value){bytes(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort((short)value).array());}
        void u32(long value){bytes(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt((int)value).array());}
        void bytes(byte[] value){out.writeBytes(value);}byte[] data(){return out.toByteArray();}
    }
    private static void require(boolean condition,String message){if(!condition)throw new IllegalArgumentException(message);}
}
