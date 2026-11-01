package com.surprising.wallet.common.approval;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/** One-way Wallet subaccount revocation. Contains no credential or permission to enable an approval identity. */
public record ApprovalMemberRevocation(UUID tenantId,UUID memberId,long sequence,UUID actorId,Instant revokedAt) {
    public ApprovalMemberRevocation {
        if(tenantId==null || memberId==null || actorId==null || revokedAt==null || sequence<1 || sequence>1_000_000_000_000_000L)
            throw new IllegalArgumentException("bounded owned member revocation required");
    }
    public String hash() {
        return SigningAuthorization.hash(("SW-MEMBER-REVOCATION-V1:"+tenantId+":"+memberId+":"+sequence+":"+actorId+":"+revokedAt).getBytes(StandardCharsets.US_ASCII));
    }
    public record Receipt(UUID tenantId,UUID memberId,long sequence,String descriptorHash) {
        public Receipt {if(tenantId==null || memberId==null || sequence<1 || sequence>1_000_000_000_000_000L || descriptorHash==null || !descriptorHash.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("invalid member revocation receipt");}
    }
}
