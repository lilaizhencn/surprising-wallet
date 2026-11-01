package com.surprising.wallet.model.mpc;

import com.surprising.wallet.chain.evm.Evm7702PayoutRequest;
import com.surprising.wallet.chain.evm.MpcEvm7702Codec;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import java.math.BigInteger;

/** Original hot-wallet request, authorization nonce, deadline and fee quote. */
public record Evm7702PayoutPayload(BigInteger chainId, BigInteger rpcPendingNonce,
        BigInteger maxPriorityFeePerGas, BigInteger maxFeePerGas, BigInteger blockGasLimit,
        ChainAddressRecord authority, MpcEvm7702Codec.Authorization authorization, Evm7702PayoutRequest request) { }
