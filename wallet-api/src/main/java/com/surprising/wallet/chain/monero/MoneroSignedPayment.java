package com.surprising.wallet.chain.monero;

import com.surprising.wallet.common.signing.MpcClient;
import com.surprising.wallet.common.signing.MoneroMpcResultVerifier;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;

/** Public selection identity; the opaque native transaction set stays in the encrypted MPC result. */
public record MoneroSignedPayment(UUID signSessionId,String signIntentHash,List<String> originalHolders,
                                  String transactionHash,BigInteger feeAtomic,String artifactHash) {
    public MoneroSignedPayment {originalHolders=List.copyOf(originalHolders);}
    public static MoneroSignedPayment verify(MpcClient.Request request,String publicKey,byte[] originalArtifact) {
        MoneroMpcResultVerifier.artifact(request,publicKey,HexFormat.of().formatHex(originalArtifact));
        var artifact=new ObjectMapper().readTree(originalArtifact);
        try {
            return new MoneroSignedPayment(request.sessionId(),artifact.path("intentHash").asText(),request.online(),
                    artifact.path("transactionHash").asText(),new BigInteger(artifact.path("feeAtomic").asText()),
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(originalArtifact)));
        } catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
}
