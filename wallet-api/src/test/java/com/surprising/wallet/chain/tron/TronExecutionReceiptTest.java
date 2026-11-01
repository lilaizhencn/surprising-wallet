package com.surprising.wallet.chain.tron;

import com.surprising.wallet.sdk.signing.tron.MpcTronTransactionCodec;
import com.surprising.wallet.sdk.signing.tron.TronAddressCodec;

import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import java.math.BigInteger;
import java.util.HexFormat;
import org.bitcoinj.crypto.ECKey;
import org.junit.jupiter.api.Test;
import org.tron.TronWalletApi;
import org.tron.trident.core.ApiWrapper;
import org.tron.trident.proto.Chain;
import org.tron.trident.proto.Contract;
import org.tron.trident.proto.Response;
import org.web3j.utils.Numeric;

import static org.junit.jupiter.api.Assertions.*;

class TronExecutionReceiptTest {
    private static final HexFormat HEX=HexFormat.of();
    private final String source=TronWalletApi.getAddress(new ECKey().getPubKey());
    private final String recipient=TronWalletApi.getAddress(new ECKey().getPubKey());
    private final String contract=TronWalletApi.getAddress(new ECKey().getPubKey());
    private final BigInteger amount=BigInteger.valueOf(1_000_000);

    @Test void exactTokenTransferOrMatchingSplitTransfersProveThePayment() {
        var payload=unsigned();
        assertEquals(TronExecutionReceipt.Outcome.PAID,TronExecutionReceipt.verify(receipt(payload).addLog(log(amount)).build(),payload));
        assertEquals(TronExecutionReceipt.Outcome.PAID,TronExecutionReceipt.verify(receipt(payload)
                .addLog(log(amount.divide(BigInteger.TWO))).addLog(log(amount.divide(BigInteger.TWO))).build(),payload));
    }

    @Test void missingWrongOrDuplicatedTokenPaymentsCannotSettleAsPaid() {
        var payload=unsigned();
        assertThrows(IllegalStateException.class,()->TronExecutionReceipt.verify(receipt(payload).build(),payload));
        assertThrows(IllegalStateException.class,()->TronExecutionReceipt.verify(receipt(payload).addLog(log(amount.add(BigInteger.ONE))).build(),payload));
        assertThrows(IllegalStateException.class,()->TronExecutionReceipt.verify(receipt(payload).addLog(log(amount)).addLog(log(amount)).build(),payload));
        var wrongContract=log(amount).toBuilder().setAddress(ByteString.copyFrom(new byte[20]));
        assertThrows(IllegalStateException.class,()->TronExecutionReceipt.verify(receipt(payload).addLog(wrongContract).build(),payload));
        var wrongRecipient=log(amount).toBuilder().setTopics(2,ByteString.copyFrom(new byte[32]));
        assertThrows(IllegalStateException.class,()->TronExecutionReceipt.verify(receipt(payload).addLog(wrongRecipient).build(),payload));
        var malformed=log(amount).toBuilder().setData(ByteString.copyFrom(new byte[31]));
        assertThrows(IllegalArgumentException.class,()->TronExecutionReceipt.verify(receipt(payload).addLog(malformed).build(),payload));
    }

    @Test void onlyRecognizedExecutionFailuresPermitTheFailureOutcome() {
        var payload=unsigned();
        assertEquals(TronExecutionReceipt.Outcome.FAILED,TronExecutionReceipt.verify(receipt(payload).setResultValue(1).build(),payload));
        assertEquals(TronExecutionReceipt.Outcome.FAILED,TronExecutionReceipt.verify(receipt(payload)
                .setReceipt(Response.ResourceReceipt.newBuilder().setResult(Chain.Transaction.Result.contractResult.REVERT)).build(),payload));
        assertThrows(IllegalArgumentException.class,()->TronExecutionReceipt.verify(receipt(payload).setResultValue(999).build(),payload));
        assertThrows(IllegalArgumentException.class,()->TronExecutionReceipt.verify(receipt(payload)
                .setReceipt(Response.ResourceReceipt.newBuilder().setResultValue(999)).build(),payload));
    }

    @Test void wrongHashHeightOrNegativeFeeIsNotAConfirmedExecutionFact() {
        var payload=unsigned();
        assertThrows(IllegalArgumentException.class,()->TronExecutionReceipt.verify(receipt(payload).setId(ByteString.copyFrom(new byte[32])).build(),payload));
        assertThrows(IllegalArgumentException.class,()->TronExecutionReceipt.verify(receipt(payload).setBlockNumber(0).build(),payload));
        assertThrows(IllegalArgumentException.class,()->TronExecutionReceipt.verify(receipt(payload).setFee(-1).build(),payload));
    }

    private Response.TransactionInfo.Builder receipt(MpcTronTransactionCodec.Unsigned payload) {
        return Response.TransactionInfo.newBuilder().setId(ByteString.copyFrom(MpcTronTransactionCodec.digest(payload)))
                .setBlockNumber(100).setFee(200_000).setReceipt(Response.ResourceReceipt.newBuilder().setResultValue(1));
    }
    private Response.TransactionInfo.Log log(BigInteger value) {
        return Response.TransactionInfo.Log.newBuilder().setAddress(ByteString.copyFrom(HEX.parseHex(TronAddressCodec.base58ToHex(contract).substring(2))))
                .addTopics(ByteString.copyFrom(HEX.parseHex("ddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef")))
                .addTopics(topic(source)).addTopics(topic(recipient)).setData(ByteString.copyFrom(Numeric.toBytesPadded(value,32))).build();
    }
    private static ByteString topic(String address) {
        return ByteString.copyFrom(HEX.parseHex("0".repeat(24)+TronAddressCodec.base58ToHex(address).substring(2)));
    }
    private MpcTronTransactionCodec.Unsigned unsigned() {
        String data="a9059cbb"+"0".repeat(24)+TronAddressCodec.base58ToHex(recipient).substring(2)+Numeric.toHexStringNoPrefixZeroPadded(amount,64);
        var call=Contract.TriggerSmartContract.newBuilder().setOwnerAddress(ApiWrapper.parseAddress(source)).setContractAddress(ApiWrapper.parseAddress(contract))
                .setData(ByteString.copyFrom(HEX.parseHex(data))).build();
        var tx=Chain.Transaction.newBuilder().setRawData(Chain.Transaction.raw.newBuilder().setTimestamp(1_000).setExpiration(61_000)
                .setRefBlockBytes(ByteString.copyFrom(new byte[2])).setRefBlockHash(ByteString.copyFrom(new byte[8])).setFeeLimit(30_000_000)
                .addContract(Chain.Transaction.Contract.newBuilder().setType(Chain.Transaction.Contract.ContractType.TriggerSmartContract).setParameter(Any.pack(call)))).build();
        return MpcTronTransactionCodec.prepare(tx,source,recipient,amount,contract,30_000_000);
    }
}
