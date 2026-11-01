package com.surprising.wallet.chain.xrp;

import java.math.BigDecimal;
import org.xrpl.xrpl4j.codec.binary.XrplBinaryCodec;
import org.xrpl.xrpl4j.model.transactions.IssuedCurrencyAmount;
import org.xrpl.xrpl4j.model.transactions.Payment;
import org.xrpl.xrpl4j.model.transactions.XrpCurrencyAmount;
import tools.jackson.databind.JsonNode;

/** Only validated, byte-identical transactions with exact delivery are settlement evidence. */
public final class XrpExecutionReceipt {
    private XrpExecutionReceipt() { }
    public record Result(boolean paid,long ledger,long fee) { }
    public static Result verify(JsonNode receipt,MpcXrpTransactionCodec.Unsigned unsigned,
                                MpcXrpTransactionCodec.Signed signed) {
        require(receipt.path("validated").asBoolean(false),"XRPL transaction is not validated");
        var tx=receipt.has("tx_json")?receipt.path("tx_json"):receipt;
        require(binary(tx).equalsIgnoreCase(signed.transactionHex()),
                "XRPL receipt differs from selected signed transaction");
        String hash=receipt.path("hash").asText(tx.path("hash").asText(""));
        require(hash.equalsIgnoreCase(signed.txId()),"XRPL receipt hash differs");
        long ledger=receipt.path("ledger_index").asLong(tx.path("ledger_index").asLong(0));
        var meta=receipt.has("meta")?receipt.path("meta"):receipt.path("metaData");
        String code=meta.path("TransactionResult").asText("");
        require(ledger>0 && ("tesSUCCESS".equals(code)||code.startsWith("tec")),"XRPL receipt has no final execution result");
        var original=MpcXrpTransactionCodec.restore(unsigned);
        if("tesSUCCESS".equals(code) && original instanceof Payment payment) {
            var delivered=meta.path("delivered_amount");
            boolean exact;
            if(payment.amount() instanceof XrpCurrencyAmount amount)
                exact=delivered.isTextual() && new BigDecimal(delivered.asText()).compareTo(new BigDecimal(amount.value().bigIntegerValue()))==0;
            else {
                var amount=(IssuedCurrencyAmount)payment.amount();
                exact=delivered.isObject() && amount.issuer().value().equals(delivered.path("issuer").asText())
                        && amount.currency().equals(delivered.path("currency").asText())
                        && new BigDecimal(amount.value()).compareTo(new BigDecimal(delivered.path("value").asText("0")))==0;
            }
            require(exact,"XRPL validated payment does not prove the exact delivered amount");
        }
        return new Result("tesSUCCESS".equals(code),ledger,original.fee().value().longValue());
    }
    private static String binary(JsonNode transaction) {
        try {return XrplBinaryCodec.getInstance().encode(transaction.toString());}
        catch(com.fasterxml.jackson.core.JsonProcessingException invalid) {throw new IllegalStateException("invalid XRPL receipt transaction",invalid);}
    }
    private static void require(boolean condition,String message) {if(!condition)throw new IllegalStateException(message);}
}
