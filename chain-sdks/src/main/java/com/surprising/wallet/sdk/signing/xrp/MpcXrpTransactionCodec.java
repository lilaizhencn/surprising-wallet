package com.surprising.wallet.sdk.signing.xrp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.surprising.wallet.sdk.signing.ThresholdSignatureVerifier;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;
import org.bitcoinj.crypto.ECKey;
import org.xrpl.xrpl4j.codec.addresses.UnsignedByteArray;
import org.xrpl.xrpl4j.codec.binary.XrplBinaryCodec;
import org.xrpl.xrpl4j.crypto.HashingUtils;
import org.xrpl.xrpl4j.codec.addresses.KeyType;
import org.xrpl.xrpl4j.crypto.signing.Signature;
import org.xrpl.xrpl4j.crypto.signing.SignatureUtils;
import org.xrpl.xrpl4j.model.jackson.ObjectMapperFactory;
import org.xrpl.xrpl4j.model.transactions.Payment;
import org.xrpl.xrpl4j.model.transactions.CurrencyAmount;
import org.xrpl.xrpl4j.model.transactions.XrpCurrencyAmount;
import org.xrpl.xrpl4j.model.transactions.IssuedCurrencyAmount;
import org.xrpl.xrpl4j.model.transactions.Transaction;
import org.xrpl.xrpl4j.model.transactions.TrustSet;

/** XRPL canonical binary Payment/TrustSet, SHA512Half signing digest and external low-S DER assembly. */
public final class MpcXrpTransactionCodec {
    private static final HexFormat HEX=HexFormat.of();
    private static final XrplBinaryCodec CODEC=XrplBinaryCodec.getInstance();
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON=ObjectMapperFactory.create();
    private static final SignatureUtils SIGNATURES=SignatureUtils.getInstance();
    private MpcXrpTransactionCodec() { }

    public record Unsigned(String transactionHex) {
        public Unsigned {
            if(transactionHex==null || transactionHex.isEmpty() || transactionHex.length()>2*1024*1024
                    || transactionHex.length()%2!=0 || !transactionHex.matches("[0-9a-f]+"))
                throw new IllegalArgumentException("invalid immutable XRPL binary envelope");
        }
    }
    public record Signed(String transactionHex,String txId) { }

    public static Unsigned prepare(Transaction transaction) {
        try {
            var unsigned=new Unsigned(CODEC.encode(JSON.writeValueAsString(transaction)).toLowerCase(Locale.ROOT));
            var restored=restore(unsigned);
            if(transaction instanceof Payment original && restored instanceof Payment decoded)
                require(exactAmount(original.amount(),decoded.amount()),"XRPL payment amount cannot be represented exactly");
            if(transaction instanceof TrustSet original && restored instanceof TrustSet decoded)
                require(exactAmount(original.limitAmount(),decoded.limitAmount()),"XRPL trust limit cannot be represented exactly");
            return unsigned;
        } catch(JsonProcessingException invalid) {throw new IllegalArgumentException("invalid unsigned XRPL transaction",invalid);}
    }

    public static Transaction restore(Unsigned unsigned) {
        try {
            String decoded=CODEC.decode(unsigned.transactionHex());
            String type=JSON.readTree(decoded).path("TransactionType").asText();
            Transaction tx=switch(type) {
                case "Payment" -> JSON.readValue(decoded,Payment.class);
                case "TrustSet" -> JSON.readValue(decoded,TrustSet.class);
                default -> throw new IllegalArgumentException("unsupported MPC XRPL transaction type");
            };
            require(CODEC.encode(JSON.writeValueAsString(tx)).equalsIgnoreCase(unsigned.transactionHex()),"XRPL envelope is noncanonical or contains unrecognized fields");
            require(tx.transactionSignature().isEmpty() && tx.signers().isEmpty() && tx.sequence().longValue()>0
                            && tx.ticketSequence().isEmpty() && tx.lastLedgerSequence().isPresent()
                            && tx.lastLedgerSequence().orElseThrow().longValue()>0 && tx.fee().value().bigIntegerValue().signum()>0
                            && tx.signingPublicKey().keyType()==KeyType.SECP256K1
                            && tx.signingPublicKey().deriveAddress().equals(tx.account()),
                    "XRPL envelope is signed or lacks a joint owner key, reserved sequence, expiry or fee");
            if(tx instanceof Payment payment) require(!payment.flags().tfPartialPayment() && payment.paths().isEmpty()
                    && payment.sendMax().isEmpty() && payment.deliverMin().isEmpty(),"XRPL MPC payments must deliver the exact direct amount");
            return tx;
        } catch(JsonProcessingException invalid) {throw new IllegalArgumentException("invalid immutable XRPL transaction",invalid);}
    }

    public static byte[] digest(Unsigned unsigned) {
        return HashingUtils.sha512Half(SIGNATURES.toSignableBytes(restore(unsigned))).toByteArray();
    }

    public static Signed assemble(Unsigned unsigned,byte[] publicKey,byte[] derSignature) {
        var tx=restore(unsigned);
        require(Arrays.equals(tx.signingPublicKey().value().toByteArray(),publicKey)
                        && ThresholdSignatureVerifier.ecdsa(publicKey,digest(unsigned),derSignature),
                "MPC signature or key differs from the fixed XRPL transaction");
        ECKey.ECDSASignature decoded;
        try {decoded=ECKey.ECDSASignature.decodeFromDER(derSignature).toCanonicalised();}
        catch(org.bitcoinj.crypto.SignatureDecodeException invalid) {throw new IllegalArgumentException("invalid XRPL MPC DER",invalid);}
        var signature=Signature.builder().value(UnsignedByteArray.of(decoded.encodeToDER())).build();
        var signed=SIGNATURES.addSignatureToTransaction(tx,signature);
        return new Signed(signed.signedTransactionBytes().hexValue().toLowerCase(Locale.ROOT),signed.hash().value());
    }
    private static boolean exactAmount(CurrencyAmount original,CurrencyAmount decoded) {
        if(original instanceof XrpCurrencyAmount first && decoded instanceof XrpCurrencyAmount second) return first.value().equals(second.value());
        if(original instanceof IssuedCurrencyAmount first && decoded instanceof IssuedCurrencyAmount second)
            return first.issuer().equals(second.issuer()) && first.currency().equals(second.currency())
                    && new java.math.BigDecimal(first.value()).compareTo(new java.math.BigDecimal(second.value()))==0;
        return false;
    }
    private static void require(boolean condition,String message) {if(!condition)throw new IllegalArgumentException(message);}
}
