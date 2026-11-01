package com.surprising.wallet.common.signing;

import java.util.UUID;

/** Public parent identity shared by an outgoing NEAR payment and its bounded storage registration. */
public record NearOperation(UUID tenantId,String reference,Kind kind) {
    public enum Kind { WITHDRAWAL,COLLECTION }
    public NearOperation {
        if(tenantId==null || kind==null || reference==null || reference.isBlank() || reference.length()>192)
            throw new IllegalArgumentException("NEAR original business identity required");
    }
}
