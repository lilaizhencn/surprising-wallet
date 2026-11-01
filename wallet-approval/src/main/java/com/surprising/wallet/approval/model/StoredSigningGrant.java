package com.surprising.wallet.approval.model;

import com.surprising.wallet.common.approval.SigningGrant;
import com.surprising.wallet.common.signing.MpcClient;

/** Original immutable execution and operator audit for each independently authorized native session. */
public record StoredSigningGrant(SigningGrant grant,MpcClient.Request execution,String replayActor,String replayReason) { }
