package com.surprising.wallet.model.mpc;

import com.surprising.wallet.chain.near.MpcNearTransactionCodec;
import com.surprising.wallet.service.NearSubmissionService.Operation;
import com.surprising.wallet.service.NearSubmissionService.Payment;
import java.math.BigInteger;

/** One fixed tenant Gas registration bound to the exact original outgoing business. */
public record NearRegistrationPayload(Operation parent,Payment payment,long parentAddressId,String parentSender,int tokenDecimals,
        BigInteger maximumFeeYocto,MpcNearTransactionCodec.Registration unsigned) { }
