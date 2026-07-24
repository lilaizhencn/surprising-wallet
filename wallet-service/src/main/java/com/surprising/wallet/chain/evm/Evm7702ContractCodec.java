package com.surprising.wallet.chain.evm;

import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.DynamicArray;
import org.web3j.abi.datatypes.DynamicBytes;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.StaticStruct;
import org.web3j.abi.datatypes.generated.Bytes32;
import org.web3j.abi.datatypes.generated.Uint256;

import java.util.ArrayList;
import java.util.List;

/** ABI codec for Eip7702BatchCollector.collectBatch. */
public class Evm7702ContractCodec {
    public String encodeCollectBatch(List<Evm7702CollectionRequest> requests, List<byte[]> signatures) {        if (requests == null || requests.isEmpty() || requests.size() > 100
                || signatures == null || signatures.size() != requests.size()) {
            throw new IllegalArgumentException("batch must contain 1..100 requests and matching signatures");
        }
        ArrayList<CollectionRequestStruct> requestValues = new ArrayList<>(requests.size());
        ArrayList<DynamicBytes> signatureValues = new ArrayList<>(signatures.size());
        for (int index = 0; index < requests.size(); index++) {
            Evm7702CollectionRequest request = requests.get(index);
            if (!request.itemIndex().equals(java.math.BigInteger.valueOf(index))) {
                throw new IllegalArgumentException("itemIndex must be contiguous from zero");
            }
            byte[] signature = signatures.get(index);
            if (signature == null || signature.length != 65) {
                throw new IllegalArgumentException("each authority signature must contain 65 bytes");
            }
            requestValues.add(new CollectionRequestStruct(request));
            signatureValues.add(new DynamicBytes(signature.clone()));
        }
        Function function = new Function(
                "collectBatch",
                List.of(
                        new DynamicArray<>(CollectionRequestStruct.class, requestValues),
                        new DynamicArray<>(DynamicBytes.class, signatureValues)),
                List.of());
        return FunctionEncoder.encode(function);
    }
    public static final class CollectionRequestStruct extends StaticStruct {
        CollectionRequestStruct(Evm7702CollectionRequest request) {
            super(
                    new Bytes32(request.batchId()),
                    new Uint256(request.itemIndex()),
                    new Address(request.authority()),
                    new Address(request.collector()),
                    new Address(request.token()),
                    new Address(request.recipient()),
                    new Uint256(request.amount()),
                    new Uint256(request.operationNonce()),
                    new Uint256(request.deadline()),
                    new Uint256(request.callGasLimit()));
        }
    }
}
