package com.surprising.wallet.common.approval;

import com.surprising.wallet.common.signing.MpcClient;
import com.surprising.wallet.common.signing.MpcSignCommand;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** A committed Wallet signing slot and its exact native execution descriptor. No caller asset metadata. */
public record SigningCandidate(UUID signingRequestId,List<UUID> approvalRequestIds,
        MpcSignCommand command,MpcClient.Request execution,String replayActor,String replayReason) {
    public SigningCandidate {
        Objects.requireNonNull(signingRequestId);Objects.requireNonNull(command);Objects.requireNonNull(execution);
        approvalRequestIds=List.copyOf(approvalRequestIds);
        if((command.businessType()==MpcSignCommand.BusinessType.WITHDRAWAL?approvalRequestIds.isEmpty():!approvalRequestIds.isEmpty()) || approvalRequestIds.size()>100
                || approvalRequestIds.stream().distinct().count()!=approvalRequestIds.size()
                || !command.tenantId().equals(execution.tenantId()) || !command.keyId().equals(execution.keyId())
                || command.keyEpoch()!=execution.epoch() || !command.preimageHex().equals(execution.messageHex()))
            throw new IllegalArgumentException("signing candidate scope differs");
        if((replayActor==null)!=(replayReason==null) || (replayActor!=null && (replayActor.isBlank() || replayActor.length()>192
                || !replayActor.equals(replayActor.trim()) || replayReason.trim().length()<5 || replayReason.length()>512
                || !replayReason.equals(replayReason.trim()) || replayActor.chars().anyMatch(Character::isISOControl) || replayReason.chars().anyMatch(Character::isISOControl))))
            throw new IllegalArgumentException("explicit replay actor and reason are required together");
        approvalRequestIds=approvalRequestIds.stream().sorted().toList();
    }
}
