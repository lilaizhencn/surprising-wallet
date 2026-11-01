package com.surprising.wallet.common.signing;

import com.surprising.wallet.common.signing.NearOperation;

import com.surprising.wallet.sdk.signing.near.MpcNearTransactionCodec;

import com.surprising.wallet.common.signing.NearPayment;
import java.math.BigInteger;

/** One fixed tenant Gas registration bound to the exact original outgoing business. */
public record NearRegistrationPayload(NearOperation parent,NearPayment payment,long parentAddressId,String parentSender,int tokenDecimals,
        BigInteger maximumFeeYocto,MpcNearTransactionCodec.Registration unsigned) { }
