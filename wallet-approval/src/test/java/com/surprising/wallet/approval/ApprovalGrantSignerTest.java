package com.surprising.wallet.approval;

import com.surprising.wallet.approval.config.ApprovalSigningProperties;
import com.surprising.wallet.approval.service.ApprovalGrantSigner;
import com.surprising.wallet.common.approval.SigningAuthorization;
import com.surprising.wallet.common.signing.MpcClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ApprovalGrantSignerTest {
    @TempDir Path directory;
    @Test void protectedIndependentIssuerProducesOnlyThePinnedVerificationIdentity() throws Exception {
        var pair=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();var file=directory.resolve("issuer.der");
        Files.write(file,pair.getPrivate().getEncoded());Files.setPosixFilePermissions(file,Set.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE));
        var encoded=pair.getPublic().getEncoded();String pub=HexFormat.of().formatHex(encoded,encoded.length-32,encoded.length);
        var signer=new ApprovalGrantSigner(new ApprovalSigningProperties(file,pub));
        var now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        var request=new MpcClient.Request(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),1,MpcClient.Operation.SIGN,MpcClient.Algorithm.ECDSA_SECP256K1,
                List.of("a".repeat(64),"b".repeat(64)),"12".repeat(32));
        var claims=new SigningAuthorization.Claims(UUID.randomUUID(),UUID.randomUUID(),request.tenantId(),request.keyId(),1,request.sessionId(),"SIGN",request.algorithm().name(),request.online(),
                SigningAuthorization.hash(HexFormat.of().parseHex(request.messageHex())),"ETH","eip155:11155111","c".repeat(64),"WITHDRAWAL",now,now.plusSeconds(120));
        assertEquals(claims,SigningAuthorization.verify(signer.sign(claims),pair.getPublic(),request,now));
        assertThrows(IllegalArgumentException.class,()->new ApprovalGrantSigner(new ApprovalSigningProperties(file,"00".repeat(32))));
        Files.setPosixFilePermissions(file,Set.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE,PosixFilePermission.GROUP_READ,PosixFilePermission.OTHERS_READ));
        assertThrows(IllegalArgumentException.class,()->new ApprovalGrantSigner(new ApprovalSigningProperties(file,pub)));
    }
    @Test void absentOrIncompleteIssuerNeverReturnsASigningProof() {
        var absent=new ApprovalGrantSigner(new ApprovalSigningProperties(null,null));
        assertThrows(IllegalStateException.class,()->absent.sign(null));
        assertThrows(IllegalArgumentException.class,()->new ApprovalSigningProperties(directory.resolve("missing.der"),null));
    }
}
