package com.surprising.wallet.chain.evm;

import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.DynamicArray;
import org.web3j.abi.datatypes.DynamicBytes;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.StaticStruct;
import org.web3j.abi.datatypes.generated.Bytes32;
import org.web3j.abi.datatypes.generated.Uint256;

import java.util.List;

/** ABI codec for Eip7702PayoutDelegate.payoutBatch. */
public class Evm7702PayoutCodec {
    public String encode(Evm7702PayoutRequest request, byte[] signature) {        if (signature == null || signature.length != 65) {
            throw new IllegalArgumentException("payout authority signature must contain 65 bytes");
        }
        List<PayoutItemStruct> items = request.items().stream().map(PayoutItemStruct::new).toList();
        Function function = new Function(
                "payoutBatch",
                List.of(new Bytes32(request.batchId()),
                        new DynamicArray<>(PayoutItemStruct.class, items),
                        new Uint256(request.operationNonce()), new Uint256(request.deadline()),
                        new DynamicBytes(signature.clone())),
                List.of());
        return FunctionEncoder.encode(function);
    }
    public static final class PayoutItemStruct extends StaticStruct {
        PayoutItemStruct(Evm7702PayoutItem item) {
            super(new Bytes32(item.withdrawalId()), new Uint256(item.itemIndex()),
                    new Address(item.token()), new Address(item.recipient()),
                    new Uint256(item.amount()), new Uint256(item.callGasLimit()));
        }
    }
}
