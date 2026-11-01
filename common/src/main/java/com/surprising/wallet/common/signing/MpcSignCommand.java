package com.surprising.wallet.common.signing;

import com.surprising.wallet.common.chain.ChainType;
import java.util.Objects;
import java.util.UUID;

/** Immutable, recoverable unsigned payload and one signature slot of a business transaction. */
public record MpcSignCommand(UUID tenantId, UUID keyId, int keyEpoch, ChainType chain,
                             BusinessType businessType, String businessReference, String signingSlot,
                             Purpose purpose, String payloadEncoding, String payloadHex, String preimageHex) {
    public enum BusinessType { WITHDRAWAL, COLLECTION, RECOVERY, FUNDING, ADDRESS_PREPARATION, DEV_FAUCET }
    public enum Purpose { TRANSACTION, EIP7702_AUTHORIZATION, EIP7702_OPERATION }
    public String reservationOwner(){return reservationOwner(tenantId,businessType,businessReference);}
    public static String reservationOwner(UUID tenant,BusinessType type,String reference) {
        Objects.requireNonNull(tenant);Objects.requireNonNull(type);
        if(reference==null || reference.isBlank() || reference.length()>192)throw new IllegalArgumentException("fixed nonce business owner required");
        return tenant+":"+type+":"+reference;
    }

    /** Durable public membership for cancellation/audit lookup; actual payment authorization still decodes the full wire. */
    public java.util.List<String> businessReferences(tools.jackson.databind.ObjectMapper json) {
        var references=chain.isBitcoinLike() && payloadEncoding.equals("UTXO_MPC_JSON_V1")?UtxoSigningPayload.decode(this,json).task().request().payments().stream().map(UtxoSigningRequest.Payment::reference).toList()
                :payloadEncoding.equals(Evm7702SigningPayload.ENCODING)?Evm7702SigningPayload.decode(this,json).plan().references():java.util.List.of(businessReference);
        if(references.isEmpty() || references.size()>100 || references.stream().anyMatch(r->r==null || r.isBlank() || r.length()>192)
                || references.stream().distinct().count()!=references.size())throw new IllegalArgumentException("one distinct owned business reference required per payment");
        return references.stream().sorted().toList();
    }

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
