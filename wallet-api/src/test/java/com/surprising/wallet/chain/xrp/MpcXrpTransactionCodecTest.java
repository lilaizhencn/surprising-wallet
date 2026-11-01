package com.surprising.wallet.chain.xrp;

import com.surprising.wallet.sdk.signing.xrp.MpcXrpTransactionCodec;

import com.google.common.primitives.UnsignedInteger;
import com.google.common.primitives.UnsignedLong;
import java.util.HexFormat;
import org.bitcoinj.base.Sha256Hash;
import org.bitcoinj.crypto.ECKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.xrpl.xrpl4j.codec.binary.XrplBinaryCodec;
import org.xrpl.xrpl4j.crypto.HashingUtils;
import org.xrpl.xrpl4j.crypto.keys.PublicKey;
import org.xrpl.xrpl4j.crypto.signing.SignatureUtils;
import org.xrpl.xrpl4j.crypto.signing.bc.BcSignatureService;
import org.xrpl.xrpl4j.model.flags.PaymentFlags;
import org.xrpl.xrpl4j.model.jackson.ObjectMapperFactory;
import org.xrpl.xrpl4j.model.transactions.*;

import static org.junit.jupiter.api.Assertions.*;

class MpcXrpTransactionCodecTest {
    private static final HexFormat HEX=HexFormat.of();
    private final ECKey key=new ECKey();
    private final PublicKey pub=PublicKey.fromBase16EncodedPublicKey(HEX.formatHex(key.getPubKey()));
    private final Address recipient=PublicKey.fromBase16EncodedPublicKey(HEX.formatHex(new ECKey().getPubKey())).deriveAddress();

    @ParameterizedTest @ValueSource(strings={"XRP","IOU","TRUSTSET"})
    void acceptedBinaryAndExternalDerProduceSdkVerifiedCanonicalSignedTransaction(String kind) throws Exception {
        Transaction tx=transaction(kind);
        var unsigned=MpcXrpTransactionCodec.prepare(tx);
        assertArrayEquals(HashingUtils.sha512Half(SignatureUtils.getInstance().toSignableBytes(tx)).toByteArray(),MpcXrpTransactionCodec.digest(unsigned));
        var original=MpcXrpTransactionCodec.restore(unsigned);
        assertEquals(tx.account(),original.account());assertEquals(tx.sequence(),original.sequence());
        byte[] der=key.sign(Sha256Hash.wrap(MpcXrpTransactionCodec.digest(unsigned))).encodeToDER();
        var signed=MpcXrpTransactionCodec.assemble(unsigned,key.getPubKey(),der);
        String json=XrplBinaryCodec.getInstance().decode(signed.transactionHex());
        Transaction decoded=kind.equals("TRUSTSET") ? ObjectMapperFactory.create().readValue(json,TrustSet.class):ObjectMapperFactory.create().readValue(json,Payment.class);
        var signature=decoded.transactionSignature().orElseThrow();
        assertTrue(new BcSignatureService().verify(Signer.builder().signingPublicKey(pub).transactionSignature(signature).build(),original));
        assertEquals(signed.txId(),SignatureUtils.getInstance().addSignatureToTransaction(original,signature).hash().value());
        assertTrue(ECKey.ECDSASignature.decodeFromDER(signature.value().toByteArray()).isCanonical());
        assertThrows(IllegalArgumentException.class,()->MpcXrpTransactionCodec.prepare(decoded));
        assertThrows(IllegalArgumentException.class,()->MpcXrpTransactionCodec.assemble(unsigned,new ECKey().getPubKey(),der));
    }

    @Test void wrongSignatureAndMutatedReservedSequenceCannotAssembleTheAcceptedTransaction() {
        var payment=(Payment)transaction("XRP");var unsigned=MpcXrpTransactionCodec.prepare(payment);
        byte[] der=key.sign(Sha256Hash.wrap(MpcXrpTransactionCodec.digest(unsigned))).encodeToDER();
        var changed=MpcXrpTransactionCodec.prepare(Payment.builder().from(payment).sequence(UnsignedInteger.valueOf(19)).build());
        assertThrows(IllegalArgumentException.class,()->MpcXrpTransactionCodec.assemble(changed,key.getPubKey(),der));
        byte[] wrong=new ECKey().sign(Sha256Hash.wrap(MpcXrpTransactionCodec.digest(unsigned))).encodeToDER();
        assertThrows(IllegalArgumentException.class,()->MpcXrpTransactionCodec.assemble(unsigned,key.getPubKey(),wrong));
    }

    @Test void partialDeliveryUnboundOwnerOrMissingSequenceExpiryAndFeeAreRejected() {
        var payment=(Payment)transaction("XRP");
        assertThrows(IllegalArgumentException.class,()->MpcXrpTransactionCodec.prepare(Payment.builder().from(payment)
                .flags(PaymentFlags.builder().tfPartialPayment(true).build()).build()));
        assertThrows(IllegalArgumentException.class,()->MpcXrpTransactionCodec.prepare(Payment.builder().from(payment).account(recipient).build()));
        assertThrows(IllegalArgumentException.class,()->MpcXrpTransactionCodec.prepare(Payment.builder().from(payment).sequence(UnsignedInteger.ZERO).build()));
        assertThrows(IllegalArgumentException.class,()->MpcXrpTransactionCodec.prepare(Payment.builder().from(payment).lastLedgerSequence(UnsignedInteger.ZERO).build()));
        assertThrows(IllegalArgumentException.class,()->MpcXrpTransactionCodec.prepare(Payment.builder().from(payment).fee(XrpCurrencyAmount.ofDrops(0)).build()));
    }

    @Test void issuedCurrencySerializationMustNotSilentlyRoundTheAcceptedPayment() {
        var payment=(Payment)transaction("IOU");
        var amount=IssuedCurrencyAmount.builder().issuer(recipient).currency("USD").value("1.123456789123456789").build();
        assertThrows(RuntimeException.class,()->MpcXrpTransactionCodec.prepare(Payment.builder().from(payment).amount(amount).build()));
    }

    private Transaction transaction(String kind) {
        if(kind.equals("TRUSTSET")) return TrustSet.builder().account(pub.deriveAddress()).signingPublicKey(pub)
                .fee(XrpCurrencyAmount.of(UnsignedLong.valueOf(12))).sequence(UnsignedInteger.valueOf(17))
                .lastLedgerSequence(UnsignedInteger.valueOf(100)).limitAmount(IssuedCurrencyAmount.builder().issuer(recipient).currency("USD").value("1000000000").build()).build();
        CurrencyAmount amount=kind.equals("XRP") ? XrpCurrencyAmount.of(UnsignedLong.valueOf(1_234_567))
                :IssuedCurrencyAmount.builder().issuer(recipient).currency("USD").value("1.234567").build();
        return Payment.builder().account(pub.deriveAddress()).signingPublicKey(pub).destination(recipient).amount(amount)
                .fee(XrpCurrencyAmount.of(UnsignedLong.valueOf(12))).sequence(UnsignedInteger.valueOf(17)).lastLedgerSequence(UnsignedInteger.valueOf(100)).build();
    }
}
