package com.surprising.wallet.model.mpc;

import com.surprising.wallet.chain.evm.Evm7702CollectionRequest;
import com.surprising.wallet.chain.evm.MpcEvm7702Codec;
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
