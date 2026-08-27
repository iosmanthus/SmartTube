package com.liskovsoft.smartyoutubetv2.tv.net;

import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Pins a connection to one exact server certificate, by sha-256 of its bytes.
 *
 * The services this talks to are self-signed boxes on a lan with no dns name,
 * so chain-to-a-ca validation has nothing to validate against. Pinning the
 * exact certificate is the stronger statement anyway: "some ca vouched for this
 * name" is a weaker claim than "this is the key I was built to trust".
 *
 * There is deliberately no way to disable this and no fallback to plain http.
 * What travels over these connections -- a live Google session one way,
 * diagnostics the other -- is worse to hand to a stranger than to not send.
 */
public final class PinnedHttps {
    private PinnedHttps() {
    }

    /** Normalises the shapes openssl prints: colons, whitespace, lower case. */
    public static String normalizePin(String pin) {
        return pin == null ? "" : pin.replace(":", "").replace(" ", "").toUpperCase();
    }

    /**
     * Restrict this connection to the pinned certificate.
     *
     * @throws IllegalArgumentException if the pin is empty, so a missing pin
     *         fails the call rather than silently trusting anything.
     */
    public static void apply(HttpsURLConnection conn, String pin) throws Exception {
        final String expected = normalizePin(pin);
        if (expected.isEmpty()) {
            throw new IllegalArgumentException("no certificate pin configured");
        }

        TrustManager[] trust = new TrustManager[] {
            new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType)
                        throws CertificateException {
                    throw new CertificateException("client auth not supported");
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType)
                        throws CertificateException {
                    if (chain == null || chain.length == 0) {
                        throw new CertificateException("empty certificate chain");
                    }
                    // chain[0] is the leaf. For a self-signed server that is the
                    // whole story; anything above it is not ours to trust.
                    if (!expected.equals(sha256Hex(chain[0]))) {
                        throw new CertificateException("certificate pin mismatch");
                    }
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }
        };

        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, trust, null);
        conn.setSSLSocketFactory(ctx.getSocketFactory());

        // The peer is identified by its exact bytes, so name matching adds
        // nothing -- and an ip-only SAN is the case the default verifier
        // handles worst.
        conn.setHostnameVerifier(new HostnameVerifier() {
            @Override
            public boolean verify(String hostname, SSLSession session) {
                return true;
            }
        });
    }

    private static String sha256Hex(X509Certificate cert) throws CertificateException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(cert.getEncoded());
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                out.append(Character.forDigit((b >> 4) & 0xf, 16));
                out.append(Character.forDigit(b & 0xf, 16));
            }
            return out.toString().toUpperCase();
        } catch (Exception e) {
            throw new CertificateException("cannot digest certificate: " + e);
        }
    }
}
