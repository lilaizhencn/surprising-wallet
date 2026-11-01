package com.surprising.wallet.chain.solana;

import com.surprising.wallet.sdk.signing.solana.MpcSolanaTransactionCodec;

import com.surprising.wallet.common.signing.SolanaSigningPayload;
import com.surprising.wallet.testutil.SolanaReceiptTestSupport;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.p2p.solanaj.core.*;
import org.p2p.solanaj.utils.Base58;
import tools.jackson.databind.node.*;
import static org.junit.jupiter.api.Assertions.*;

class SolanaExecutionReceiptTest {
    private final Account source=new Account(),payer=new Account(),recipient=new Account(),mint=new Account();
    private SolanaSigningPayload payload(boolean token) {
        var unsigned=MpcSolanaTransactionCodec.payment(source.getPublicKeyBase58(),token?payer.getPublicKeyBase58():source.getPublicKeyBase58(),
                recipient.getPublicKeyBase58(),token?mint.getPublicKeyBase58():null,1_000_000,token?6:9,Base58.encode(new byte[32]),"receipt-proof");
        return new SolanaSigningPayload(token?"USDC":"SOL",new BigDecimal(token?"1":"0.001"),BigDecimal.ZERO,token?6:9,
                token?mint.getPublicKeyBase58():null,source.getPublicKeyBase58(),recipient.getPublicKeyBase58(),token?payer.getPublicKeyBase58():source.getPublicKeyBase58(),1000,unsigned);
    }
    private MpcSolanaTransactionCodec.Signed signed(SolanaSigningPayload payload) {
        var message=MpcSolanaTransactionCodec.restore(payload.unsigned());var sdk=new Transaction();message.instructions().forEach(sdk::addInstruction);sdk.setRecentBlockHash(message.blockhash());
        var signers=payload.mint()==null?List.of(source):List.of(payer,source);sdk.sign(signers);byte[] raw=sdk.serialize();
        var signatures=new HashMap<String,byte[]>();for(int i=0;i<signers.size();i++)signatures.put(signers.get(i).getPublicKeyBase58(),Arrays.copyOfRange(raw,1+64*i,65+64*i));
        return MpcSolanaTransactionCodec.assemble(payload.unsigned(),signatures);
    }
    @Test void confirmedNativePaymentRequiresExactRecipientAndNetworkCost() {
        var payload=payload(false);var signed=signed(payload);var receipt=SolanaReceiptTestSupport.receipt(payload,signed,false,false);
        assertEquals(new SolanaExecutionReceipt.Result(true,123,5000),SolanaExecutionReceipt.verify(receipt,SolanaReceiptTestSupport.status(false),payload,signed));
        ((ArrayNode)receipt.path("meta").path("postBalances")).set(0,LongNode.valueOf(receipt.path("meta").path("postBalances").path(0).asLong()-1));
        assertThrows(IllegalStateException.class,()->SolanaExecutionReceipt.verify(receipt,SolanaReceiptTestSupport.status(false),payload,signed));
    }
    @Test void splReceiptProvesBothTokenDeltasAndIncludesNewAtaRentInGasSpending() {
        var payload=payload(true);var signed=signed(payload);
        assertEquals(2_049_280,SolanaExecutionReceipt.verify(SolanaReceiptTestSupport.receipt(payload,signed,false,true),SolanaReceiptTestSupport.status(false),payload,signed).costLamports());
        assertEquals(10_000,SolanaExecutionReceipt.verify(SolanaReceiptTestSupport.receipt(payload,signed,false,false),SolanaReceiptTestSupport.status(false),payload,signed).costLamports());
        var receipt=SolanaReceiptTestSupport.receipt(payload,signed,false,true);
        ((ObjectNode)receipt.path("meta").path("postTokenBalances").path(1).path("uiTokenAmount")).put("amount","999999");
        assertThrows(IllegalStateException.class,()->SolanaExecutionReceipt.verify(receipt,SolanaReceiptTestSupport.status(false),payload,signed));
    }
    @Test void wrongMintOwnerPrecisionMissingSourceOrDuplicateProofCannotConfirm() {
        var payload=payload(true);var signed=signed(payload);
        for(String field:List.of("mint","owner","decimals","missing","duplicate")) {
            var receipt=SolanaReceiptTestSupport.receipt(payload,signed,false,true);var rows=(ArrayNode)receipt.path("meta").path("postTokenBalances");
            var row=(ObjectNode)rows.path(0);
            switch(field){case "mint","owner"->row.put(field,"wrong");case "decimals"->((ObjectNode)row.path("uiTokenAmount")).put("decimals",9);case "missing"->rows.remove(0);case "duplicate"->rows.add(row.deepCopy());}
            assertThrows(IllegalStateException.class,()->SolanaExecutionReceipt.verify(receipt,SolanaReceiptTestSupport.status(false),payload,signed),field);
        }
    }
    @Test void onlyConfirmedMatchingExecutionFailureWithFeeOnlyDebitCanRefund() {
        var payload=payload(true);var signed=signed(payload);var receipt=SolanaReceiptTestSupport.receipt(payload,signed,true,true);
        assertEquals(new SolanaExecutionReceipt.Result(false,123,10_000),SolanaExecutionReceipt.verify(receipt,SolanaReceiptTestSupport.status(true),payload,signed));
        assertThrows(IllegalStateException.class,()->SolanaExecutionReceipt.verify(receipt,SolanaReceiptTestSupport.status(false),payload,signed));
        ((ArrayNode)receipt.path("meta").path("postBalances")).set(0,LongNode.valueOf(0));
        assertThrows(IllegalStateException.class,()->SolanaExecutionReceipt.verify(receipt,SolanaReceiptTestSupport.status(true),payload,signed));
    }
    @Test void unconfirmedWrongRawWrongSlotOrDifferentFixedMessageCannotSettle() {
        var payload=payload(false);var signed=signed(payload);var receipt=SolanaReceiptTestSupport.receipt(payload,signed,false,false);
        var status=SolanaReceiptTestSupport.status(false).put("confirmationStatus","processed");
        assertThrows(IllegalStateException.class,()->SolanaExecutionReceipt.verify(receipt,status,payload,signed));
        receipt.put("slot",124);assertThrows(IllegalStateException.class,()->SolanaExecutionReceipt.verify(receipt,SolanaReceiptTestSupport.status(false),payload,signed));receipt.put("slot",123);
        var changed=new SolanaSigningPayload(payload.symbol(),payload.amount(),payload.customerFee(),9,null,payload.sender(),payload.recipient(),payload.feePayer(),1000,
                MpcSolanaTransactionCodec.payment(payload.sender(),payload.feePayer(),payload.recipient(),null,1_000_001,9,Base58.encode(new byte[32]),"receipt-proof"));
        assertThrows(IllegalStateException.class,()->SolanaExecutionReceipt.verify(receipt,SolanaReceiptTestSupport.status(false),changed,signed));
        ((ArrayNode)receipt.path("transaction")).set(0,StringNode.valueOf("wrong"));assertThrows(IllegalStateException.class,()->SolanaExecutionReceipt.verify(receipt,SolanaReceiptTestSupport.status(false),payload,signed));
    }
}
