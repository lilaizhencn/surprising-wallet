package com.surprising.wallet.chain.solana;

import com.surprising.wallet.model.mpc.SolanaSigningPayload;
import java.math.BigInteger;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** Exact selected wire, confirmed execution, payment deltas and fee-payer spending are settlement evidence. */
public final class SolanaExecutionReceipt {
    private SolanaExecutionReceipt() { }
    public record Result(boolean paid,long slot,long costLamports) { }
    public static Result verify(JsonNode receipt,JsonNode status,SolanaSigningPayload payload,MpcSolanaTransactionCodec.Signed signed) {
        require(receipt!=null && !receipt.isNull() && status!=null && !status.isNull()
                && Set.of("confirmed","finalized").contains(status.path("confirmationStatus").asText()),"Solana receipt is not confirmed");
        require(signed.transactionBase64().equals(receipt.path("transaction").path(0).asText())
                && "base64".equals(receipt.path("transaction").path(1).asText()),"Solana receipt differs from selected raw transaction");
        long slot=receipt.path("slot").asLong(0);require(slot>0 && slot==status.path("slot").asLong(0),"Solana receipt slot differs from signature status");
        var meta=receipt.path("meta");require(meta.isObject() && meta.has("err") && status.has("err")
                && meta.path("err").equals(status.path("err")),"Solana receipt has no matching execution outcome");
        var message=MpcSolanaTransactionCodec.restore(payload.unsigned());
        byte[] wire=java.util.Base64.getDecoder().decode(signed.transactionBase64());
        int offset=1+64*message.requiredSignatures();
        require(wire.length==offset+payload.unsigned().messageHex().length()/2 && (wire[0]&255)==message.requiredSignatures()
                && java.util.HexFormat.of().formatHex(java.util.Arrays.copyOfRange(wire,offset,wire.length)).equals(payload.unsigned().messageHex())
                && org.p2p.solanaj.utils.Base58.encode(java.util.Arrays.copyOfRange(wire,1,65)).equals(signed.signature()),"Solana selected wire is not bound to fixed message and signature");
        var pre=meta.path("preBalances");var post=meta.path("postBalances");
        require(pre.isArray() && post.isArray() && pre.size()==message.accounts().size() && post.size()==message.accounts().size(),"Solana receipt balance vector differs from fixed accounts");
        BigInteger fee=amount(meta.path("fee")),cost=amount(pre.path(0)).subtract(amount(post.path(0)));
        if(!meta.path("err").isNull()) {
            require(cost.equals(fee),"Solana failed transaction changed more than its network fee");return new Result(false,slot,cost.longValueExact());
        }
        BigInteger atomic=payload.amount().movePointRight(payload.decimals()).toBigIntegerExact();
        if(payload.mint()==null) {
            int recipient=index(message,payload.recipient());
            require(amount(post.path(recipient)).subtract(amount(pre.path(recipient))).equals(atomic),"Solana native receipt does not prove the exact recipient amount");
            cost=cost.subtract(atomic);require(cost.equals(fee),"Solana native fee-payer delta differs from fixed payment and network fee");
        } else {
            int source=index(message,MpcSolanaTransactionCodec.associatedTokenAddress(payload.sender(),payload.mint()));
            int recipient=index(message,MpcSolanaTransactionCodec.associatedTokenAddress(payload.recipient(),payload.mint()));
            BigInteger sourceBefore=token(meta.path("preTokenBalances"),source,payload,payload.sender(),false);
            BigInteger sourceAfter=token(meta.path("postTokenBalances"),source,payload,payload.sender(),false);
            BigInteger before=token(meta.path("preTokenBalances"),recipient,payload,payload.recipient(),true);
            BigInteger after=token(meta.path("postTokenBalances"),recipient,payload,payload.recipient(),false);
            require(sourceBefore.subtract(sourceAfter).equals(atomic) && after.subtract(before).equals(atomic),"Solana SPL receipt does not prove exact source and destination deltas");
            // ATA rent is a real payment by the Gas account, even though it is not burned by the validator.
            require(cost.compareTo(fee)>=0,"Solana SPL fee-payer spending is smaller than network fee");
        }
        return new Result(true,slot,cost.longValueExact());
    }
    private static int index(MpcSolanaTransactionCodec.Message message,String address) {
        for(int i=0;i<message.accounts().size();i++)if(message.accounts().get(i).getPublicKey().toBase58().equals(address))return i;
        throw new IllegalStateException("Solana payment account is absent from fixed message");
    }
    private static BigInteger token(JsonNode balances,int index,SolanaSigningPayload payload,String owner,boolean absentIsZero) {
        require(balances.isArray(),"Solana token balance proof is missing");BigInteger result=null;
        for(var row:balances)if(row.path("accountIndex").asInt(-1)==index) {
            require(result==null && payload.mint().equals(row.path("mint").asText()) && owner.equals(row.path("owner").asText())
                    && row.path("uiTokenAmount").path("decimals").asInt(-1)==payload.decimals(),"Solana token proof identity or precision differs");
            String value=row.path("uiTokenAmount").path("amount").asText();require(value.matches("[0-9]+"),"Solana atomic token amount is invalid");
            result=new BigInteger(value);require(result.bitLength()<=64,"Solana token balance is outside u64");
        }
        require(result!=null || absentIsZero,"Solana token balance is absent");return result==null?BigInteger.ZERO:result;
    }
    private static BigInteger amount(JsonNode node) {
        require(node.isIntegralNumber(),"Solana native amount must be an integer");var value=new BigInteger(node.asText());require(value.signum()>=0 && value.bitLength()<=64,"Solana native amount is outside u64");return value;
    }
    private static void require(boolean condition,String message){if(!condition)throw new IllegalStateException(message);}
}
