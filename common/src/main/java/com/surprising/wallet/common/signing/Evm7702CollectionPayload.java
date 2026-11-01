package com.surprising.wallet.common.signing;

import com.surprising.wallet.sdk.signing.evm7702.Evm7702CollectionRequest;
import com.surprising.wallet.sdk.signing.evm7702.MpcEvm7702Codec;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import java.math.BigInteger;
import java.util.List;

/** Public RPC preparation selected once before any native signature; no private signing material. */
public record Evm7702CollectionPayload(BigInteger chainId, BigInteger rpcPendingNonce,
        BigInteger maxPriorityFeePerGas, BigInteger maxFeePerGas, BigInteger blockGasLimit, List<Item> items) {
    public Evm7702CollectionPayload { items = List.copyOf(items); }
    public record Item(ChainAddressRecord authority, MpcEvm7702Codec.Authorization authorization,
                       Evm7702CollectionRequest request) { }
}
