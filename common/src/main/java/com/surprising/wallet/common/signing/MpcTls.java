package com.surprising.wallet.common.signing;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Set;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;

/** TLS credentials only; no wallet seeds or MPC shares enter this class. */
final class MpcTls {
    private MpcTls() { }

    static SSLContext context(String partyId, Path ca, Path certificate, Path privateKey) {
        try {
            if (!Files.isRegularFile(privateKey, LinkOption.NOFOLLOW_LINKS)
                    || !Files.getPosixFilePermissions(privateKey, LinkOption.NOFOLLOW_LINKS)
                    .equals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))) {
                throw new IllegalArgumentException("MPC TLS private key must be an owner-only regular file");
            }
            var certificates = CertificateFactory.getInstance("X.509");
            var trust = KeyStore.getInstance("PKCS12");
            trust.load(null, null);
            try (InputStream input = Files.newInputStream(ca)) {
                int index = 0;
                for (var cert : certificates.generateCertificates(input)) trust.setCertificateEntry("ca-" + index++, cert);
                if (index == 0) throw new IllegalArgumentException("MPC CA file is empty");
            }
            X509Certificate[] chain;
            try (InputStream input = Files.newInputStream(certificate)) {
                chain = certificates.generateCertificates(input).toArray(X509Certificate[]::new);
            }
            if (chain.length == 0) throw new IllegalArgumentException("MPC client certificate is empty");
            String pem = Files.readString(privateKey, StandardCharsets.US_ASCII);
            if (!pem.startsWith("-----BEGIN PRIVATE KEY-----")) throw new IllegalArgumentException("MPC TLS key must be PKCS8 PEM");
            byte[] decoded = Base64.getMimeDecoder().decode(pem.replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", ""));
            var keys = KeyStore.getInstance("PKCS12");
            keys.load(null, null);
            char[] password = new char[0];
            try {
                var key = KeyFactory.getInstance(chain[0].getPublicKey().getAlgorithm()).generatePrivate(new PKCS8EncodedKeySpec(decoded));
                keys.setKeyEntry("mpc-client", key, password, chain);
            } finally {
                Arrays.fill(decoded, (byte) 0);
            }
            var keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keyManagers.init(keys, password);
            var trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustManagers.init(trust);
            X509ExtendedTrustManager delegate = Arrays.stream(trustManagers.getTrustManagers())
                    .filter(X509ExtendedTrustManager.class::isInstance).map(X509ExtendedTrustManager.class::cast)
                    .findFirst().orElseThrow(() -> new IllegalStateException("extended X509 trust manager is unavailable"));
            var context = SSLContext.getInstance("TLSv1.3");
            context.init(keyManagers.getKeyManagers(), new TrustManager[]{new PinnedTrust(delegate, HexFormat.of().parseHex(partyId))}, null);
            return context;
        } catch (GeneralSecurityException | IOException failure) {
            throw new IllegalArgumentException("cannot initialize MPC client TLS credentials", failure);
        }
    }

    private static final class PinnedTrust extends X509ExtendedTrustManager {
        private final X509ExtendedTrustManager delegate;
        private final byte[] expected;
        private PinnedTrust(X509ExtendedTrustManager delegate, byte[] expected) { this.delegate = delegate; this.expected = expected; }

        private void pin(X509Certificate[] chain) throws CertificateException {
            try {
                if (chain.length == 0 || !MessageDigest.isEqual(expected,
                        MessageDigest.getInstance("SHA-256").digest(chain[0].getPublicKey().getEncoded()))) {
                    throw new CertificateException("MPC server SPKI identity differs");
                }
            } catch (GeneralSecurityException failure) {
                throw new CertificateException("MPC server identity could not be verified", failure);
            }
        }

        @Override public X509Certificate[] getAcceptedIssuers() { return delegate.getAcceptedIssuers(); }
        @Override public void checkClientTrusted(X509Certificate[] chain, String auth) throws CertificateException { delegate.checkClientTrusted(chain, auth); }
        @Override public void checkClientTrusted(X509Certificate[] chain, String auth, Socket socket) throws CertificateException { delegate.checkClientTrusted(chain, auth, socket); }
        @Override public void checkClientTrusted(X509Certificate[] chain, String auth, SSLEngine engine) throws CertificateException { delegate.checkClientTrusted(chain, auth, engine); }
        @Override public void checkServerTrusted(X509Certificate[] chain, String auth) throws CertificateException { delegate.checkServerTrusted(chain, auth); pin(chain); }
        @Override public void checkServerTrusted(X509Certificate[] chain, String auth, Socket socket) throws CertificateException { delegate.checkServerTrusted(chain, auth, socket); pin(chain); }
        @Override public void checkServerTrusted(X509Certificate[] chain, String auth, SSLEngine engine) throws CertificateException { delegate.checkServerTrusted(chain, auth, engine); pin(chain); }
    }
}
