package com.surprising.wallet.model.mpc;

import com.surprising.wallet.common.chain.ChainType;
import java.util.Objects;
import java.util.UUID;

/** Immutable, recoverable unsigned payload and one signature slot of a business transaction. */
public record MpcSignCommand(UUID tenantId, UUID keyId, int keyEpoch, ChainType chain,
                             BusinessType businessType, String businessReference, String signingSlot,
                             Purpose purpose, String payloadEncoding, String payloadHex, String preimageHex) {
    public enum BusinessType { WITHDRAWAL, COLLECTION, RECOVERY, FUNDING, DEV_FAUCET }
    public enum Purpose { TRANSACTION, EIP7702_AUTHORIZATION, EIP7702_OPERATION }

    public MpcSignCommand {
        Objects.requireNonNull(tenantId, "MPC tenant is required");
        Objects.requireNonNull(keyId, "MPC key is required");
        Objects.requireNonNull(chain, "MPC chain is required");
        Objects.requireNonNull(businessType, "MPC business type is required");
        Objects.requireNonNull(purpose, "MPC signing purpose is required");
        if (keyEpoch < 1 || businessReference == null || businessReference.isBlank() || businessReference.length() > 192
                || signingSlot == null || signingSlot.isBlank() || signingSlot.length() > 96
                || payloadEncoding == null || !payloadEncoding.matches("[A-Z][A-Z0-9_]{0,47}")
                || payloadHex == null || payloadHex.isEmpty() || payloadHex.length() % 2 != 0
                || payloadHex.length() > 2 * 1024 * 1024 || !payloadHex.matches("[0-9a-f]+")
                || preimageHex == null || preimageHex.length() % 2 != 0 || preimageHex.length() > 512 * 1024
                || !preimageHex.matches("[0-9a-f]*")) {
            throw new IllegalArgumentException("invalid immutable MPC signing intent");
        }
        if (purpose != Purpose.TRANSACTION && !chain.isEvm()) {
            throw new IllegalArgumentException("EIP-7702 signing requires an EVM chain");
        }
    }
}
