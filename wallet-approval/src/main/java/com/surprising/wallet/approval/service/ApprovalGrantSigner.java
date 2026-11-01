package com.surprising.wallet.approval.service;

import com.surprising.wallet.approval.config.ApprovalSigningProperties;
import com.surprising.wallet.common.approval.SigningAuthorization;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Arrays;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Cryptographic issuer only. The grant service must durably authorize a verified plan before returning its proof. */
@Component
public class ApprovalGrantSigner {
    private final PrivateKey key;
    private final String publicKeyHex;
    public ApprovalGrantSigner(ApprovalSigningProperties properties) {
        publicKeyHex=properties.publicKeyHex();
        if(properties.privateKeyFile()==null) { key=null;return; }
        byte[] encoded=null;
        try {
            var file=properties.privateKeyFile();
            if(Files.isSymbolicLink(file) || !Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)
                    || !Files.getOwner(file,LinkOption.NOFOLLOW_LINKS).getName().equals(System.getProperty("user.name"))
                    || !Files.getPosixFilePermissions(file,LinkOption.NOFOLLOW_LINKS).equals(Set.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE))
                    || Files.size(file)<32 || Files.size(file)>1024)
                throw new IllegalArgumentException("authorization key requires an owner-only 0600 regular PKCS8 DER file");
            encoded=Files.readAllBytes(file);
            key=KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(encoded));
            byte[] challenge="SW-APPROVAL-KEY-CHECK-V1".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            var probe=Signature.getInstance("Ed25519");probe.initSign(key);probe.update(challenge);byte[] signature=probe.sign();
            probe.initVerify(SigningAuthorization.publicKey(publicKeyHex));probe.update(challenge);
            if(!probe.verify(signature)) throw new IllegalArgumentException("authorization signing and verification keys differ");
        } catch(Exception failure) {
            // Never include encoded private material or provider messages in logs/HTTP errors.
            throw new IllegalArgumentException("independent authorization signing key is invalid or not owner-protected");
        } finally { if(encoded!=null) Arrays.fill(encoded,(byte)0); }
    }
    public SigningAuthorization.Proof sign(SigningAuthorization.Claims claims) {
        if(key==null) throw new IllegalStateException("independent authorization signing key is not configured");
        return SigningAuthorization.sign(claims,key);
    }
    public String publicKeyHex() { return publicKeyHex; }
}
