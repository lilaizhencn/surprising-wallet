package com.surprising.wallet.chain.tron;

import com.surprising.wallet.sdk.signing.tron.MpcTronTransactionCodec;
import com.surprising.wallet.sdk.signing.tron.TronAddressCodec;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.HexFormat;
import org.tron.trident.proto.Response;
import org.tron.trident.proto.Chain;

/** Checks the solidified execution fact against the exact accepted payment, without RPC or ledger access. */
public final class TronExecutionReceipt {
    private static final byte[] TRANSFER=HexFormat.of().parseHex("ddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef");
    private TronExecutionReceipt() { }
    public enum Outcome { PAID, FAILED }

    public static Outcome verify(Response.TransactionInfo receipt,MpcTronTransactionCodec.Unsigned unsigned) {
        if(receipt.getBlockNumber()<=0 || receipt.getFee()<0
                || !Arrays.equals(receipt.getId().toByteArray(),MpcTronTransactionCodec.digest(unsigned))) {
            throw new IllegalArgumentException("TRON receipt does not identify the confirmed accepted transaction");
        }
        if(Response.TransactionInfo.code.forNumber(receipt.getResultValue())==null
                || Chain.Transaction.Result.contractResult.forNumber(receipt.getReceipt().getResultValue())==null) {
            throw new IllegalArgumentException("TRON receipt has an unrecognized execution result");
        }
        // TransactionInfo.code: SUCESS=0, FAILED=1. ContractResult: DEFAULT=0, SUCCESS=1, known failures>=2.
        if(receipt.getResultValue()!=0 || receipt.getReceipt().getResultValue()>1) return Outcome.FAILED;
        if(unsigned.contract()==null) return Outcome.PAID;
        byte[] contract=addressBytes(unsigned.contract()),source=addressBytes(unsigned.owner()),target=addressBytes(unsigned.recipient());
        BigInteger received=BigInteger.ZERO;
        for(var log:receipt.getLogList()) {
            byte[] emitted=log.getAddress().toByteArray();
            if(emitted.length==21 && emitted[0]==0x41) emitted=Arrays.copyOfRange(emitted,1,21);
            if(!Arrays.equals(emitted,contract) || log.getTopicsCount()!=3
                    || !Arrays.equals(log.getTopics(0).toByteArray(),TRANSFER)
                    || !topic(log.getTopics(1).toByteArray(),source) || !topic(log.getTopics(2).toByteArray(),target)) continue;
            if(log.getData().size()!=32) throw new IllegalArgumentException("TRC20 receipt has a malformed transfer amount");
            received=received.add(new BigInteger(1,log.getData().toByteArray()));
        }
        if(!received.equals(unsigned.amountAtomic())) {
            throw new IllegalStateException("TRC20 execution does not prove the exact accepted payment; reconciliation required");
        }
        return Outcome.PAID;
    }
    private static byte[] addressBytes(String address) {return HexFormat.of().parseHex(TronAddressCodec.base58ToHex(address).substring(2));}
    private static boolean topic(byte[] topic,byte[] address) {
        if(topic.length!=32 || !Arrays.equals(Arrays.copyOfRange(topic,12,32),address)) return false;
        for(int i=0;i<12;i++) if(topic[i]!=0) return false;
        return true;
    }
}
