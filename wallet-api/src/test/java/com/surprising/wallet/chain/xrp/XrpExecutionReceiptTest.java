package com.surprising.wallet.chain.xrp;

import com.surprising.wallet.sdk.signing.xrp.MpcXrpTransactionCodec;

import com.google.common.primitives.UnsignedInteger;
import java.util.HexFormat;
import org.bitcoinj.base.Sha256Hash;
import org.bitcoinj.crypto.ECKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.xrpl.xrpl4j.codec.binary.XrplBinaryCodec;
import org.xrpl.xrpl4j.crypto.keys.PublicKey;
import org.xrpl.xrpl4j.model.transactions.*;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.junit.jupiter.api.Assertions.*;

class XrpExecutionReceiptTest {
    private final ObjectMapper json=new ObjectMapper();
    private final ECKey key=new ECKey();
    private final PublicKey pub=PublicKey.fromBase16EncodedPublicKey(HexFormat.of().formatHex(key.getPubKey()));
    private final Address destination=PublicKey.fromBase16EncodedPublicKey(HexFormat.of().formatHex(new ECKey().getPubKey())).deriveAddress();
    @ParameterizedTest @ValueSource(strings={"XRP","IOU","TRUSTSET"})
    void onlyExactValidatedSignedTransactionsCanSettle(String kind) throws Exception {
        CurrencyAmount amount=kind.equals("XRP")?XrpCurrencyAmount.ofDrops(1000000):IssuedCurrencyAmount.builder().issuer(destination).currency("USD").value("1").build();
        Transaction tx=kind.equals("TRUSTSET")?TrustSet.builder().account(pub.deriveAddress()).signingPublicKey(pub).fee(XrpCurrencyAmount.ofDrops(12))
                .sequence(UnsignedInteger.ONE).lastLedgerSequence(UnsignedInteger.valueOf(20)).limitAmount((IssuedCurrencyAmount)amount).build()
                :Payment.builder().account(pub.deriveAddress()).signingPublicKey(pub).destination(destination).amount(amount).fee(XrpCurrencyAmount.ofDrops(12))
                .sequence(UnsignedInteger.ONE).lastLedgerSequence(UnsignedInteger.valueOf(20)).build();
        var unsigned=MpcXrpTransactionCodec.prepare(tx);
        var signed=MpcXrpTransactionCodec.assemble(unsigned,key.getPubKey(),key.sign(Sha256Hash.wrap(MpcXrpTransactionCodec.digest(unsigned))).encodeToDER());
        ObjectNode receipt=json.createObjectNode().put("validated",true).put("hash",signed.txId()).put("ledger_index",10);
        receipt.set("tx_json",json.readTree(XrplBinaryCodec.getInstance().decode(signed.transactionHex())));
        ObjectNode meta=json.createObjectNode().put("TransactionResult","tesSUCCESS");
        if(kind.equals("XRP"))meta.put("delivered_amount","1000000");
        else if(kind.equals("IOU"))meta.set("delivered_amount",json.createObjectNode().put("issuer",destination.value()).put("currency","USD").put("value","1"));
        receipt.set("meta",meta);
        assertTrue(XrpExecutionReceipt.verify(receipt,unsigned,signed).paid());
        receipt.put("validated",false);assertThrows(IllegalStateException.class,()->XrpExecutionReceipt.verify(receipt,unsigned,signed));receipt.put("validated",true);
        receipt.put("hash","00".repeat(32));assertThrows(IllegalStateException.class,()->XrpExecutionReceipt.verify(receipt,unsigned,signed));receipt.put("hash",signed.txId());
        ((ObjectNode)receipt.path("tx_json")).put("Sequence",2);assertThrows(IllegalStateException.class,()->XrpExecutionReceipt.verify(receipt,unsigned,signed));
        ((ObjectNode)receipt.path("tx_json")).put("Sequence",1);
        if(!kind.equals("TRUSTSET")) {
            meta.put("delivered_amount","unavailable");assertThrows(RuntimeException.class,()->XrpExecutionReceipt.verify(receipt,unsigned,signed));
        }
        meta.put("TransactionResult","tecPATH_DRY");assertFalse(XrpExecutionReceipt.verify(receipt,unsigned,signed).paid());
        meta.put("TransactionResult","unknown");assertThrows(IllegalStateException.class,()->XrpExecutionReceipt.verify(receipt,unsigned,signed));
    }
}
