package com.surprising.wallet.common.signing;

import com.surprising.wallet.sdk.signing.evm7702.Evm7702PayoutRequest;
import com.surprising.wallet.sdk.signing.evm7702.MpcEvm7702Codec;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import java.math.BigInteger;

/** Original hot-wallet request, authorization nonce, deadline and fee quote. */
public record Evm7702PayoutPayload(BigInteger chainId, BigInteger rpcPendingNonce,
        BigInteger maxPriorityFeePerGas, BigInteger maxFeePerGas, BigInteger blockGasLimit,
        ChainAddressRecord authority, MpcEvm7702Codec.Authorization authorization, Evm7702PayoutRequest request) { }
