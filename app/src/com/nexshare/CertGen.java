package com.nexshare;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Generates a self-signed X.509 v3 server certificate with a hand-rolled DER encoder,
 * because Android has no public certificate-building API and we don't bundle BouncyCastle.
 * Pure Java: unit-tested on the desktop JVM by serving TLS with the result.
 */
public final class CertGen {

    private CertGen() { }

    public static final String ALIAS = "nexshare";
    /** Apple caps trusted TLS certs at 825 days; stay under so users can install it as trusted. */
    public static final int VALID_DAYS = 820;

    /** Builds a PKCS#12 key store holding a fresh RSA-2048 key and its self-signed certificate. */
    public static KeyStore createKeyStore(String cn, List<String> dnsNames, List<String> ips, char[] password) throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair kp = g.generateKeyPair();
        X509Certificate cert = selfSigned(kp, cn, dnsNames, ips, VALID_DAYS);
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, password);
        ks.setKeyEntry(ALIAS, kp.getPrivate(), password, new Certificate[]{cert});
        return ks;
    }

    public static X509Certificate selfSigned(KeyPair kp, String cn, List<String> dnsNames, List<String> ips, int days) throws Exception {
        byte[] algId = seq(oid("1.2.840.113549.1.1.11"), new byte[]{0x05, 0x00}); // sha256WithRSAEncryption, NULL
        byte[] name = seq(set(seq(oid("2.5.4.3"), tlv(0x0c, cn.getBytes(StandardCharsets.UTF_8)))));
        long now = System.currentTimeMillis();
        byte[] validity = seq(time(now - 24L * 3600 * 1000), time(now + days * 24L * 3600 * 1000));

        ByteArrayOutputStream san = new ByteArrayOutputStream();
        for (String d : dnsNames) san.write(tlv(0x82, d.getBytes(StandardCharsets.US_ASCII)));
        for (String ip : ips) san.write(tlv(0x87, InetAddress.getByName(ip).getAddress()));
        byte[] extensions = seq(
            seq(oid("2.5.29.17"), octets(seq(san.toByteArray()))),                                   // subjectAltName
            seq(oid("2.5.29.19"), octets(seq())),                                                    // basicConstraints CA:false
            seq(oid("2.5.29.15"), new byte[]{0x01, 0x01, (byte) 0xff}, octets(new byte[]{0x03, 0x02, 0x05, (byte) 0xa0})), // keyUsage
            seq(oid("2.5.29.37"), octets(seq(oid("1.3.6.1.5.5.7.3.1")))));                         // extKeyUsage serverAuth

        byte[] serial = new BigInteger(1, Crypto.random(16)).setBit(126).toByteArray();
        byte[] tbs = seq(
            tlv(0xa0, tlv(0x02, new byte[]{2})),           // [0] version v3
            tlv(0x02, serial),
            algId,
            name,
            validity,
            name,
            kp.getPublic().getEncoded(),                   // SubjectPublicKeyInfo (already DER)
            tlv(0xa3, extensions));                        // [3] extensions

        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(kp.getPrivate());
        sig.update(tbs);
        byte[] s = sig.sign();
        byte[] bits = new byte[s.length + 1];
        System.arraycopy(s, 0, bits, 1, s.length);
        byte[] der = seq(tbs, algId, tlv(0x03, bits));
        return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(der));
    }

    public static String fingerprint(X509Certificate cert) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(cert.getEncoded());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < d.length; i++) {
            if (i > 0) sb.append(':');
            sb.append(String.format(Locale.ROOT, "%02X", d[i]));
        }
        return sb.toString();
    }

    public static String pem(X509Certificate cert) throws Exception {
        String b64 = Crypto.base64Encode(cert.getEncoded());
        StringBuilder sb = new StringBuilder("-----BEGIN CERTIFICATE-----\n");
        for (int i = 0; i < b64.length(); i += 64) sb.append(b64, i, Math.min(b64.length(), i + 64)).append('\n');
        return sb.append("-----END CERTIFICATE-----\n").toString();
    }

    /** Returns true if the certificate's SubjectAlternativeName extension includes all given IPv4/IPv6 strings. */
    public static boolean hasIps(X509Certificate cert, List<String> wantIps) {
        if (cert == null || wantIps == null) return false;
        try {
            java.util.Collection<List<?>> sans = cert.getSubjectAlternativeNames();
            if (sans == null) return wantIps.isEmpty();
            java.util.Set<String> present = new java.util.HashSet<>();
            for (List<?> entry : sans) {
                if (entry != null && entry.size() >= 2 && Integer.valueOf(7).equals(entry.get(0)) && entry.get(1) != null) {
                    present.add(String.valueOf(entry.get(1)));
                }
            }
            for (String ip : wantIps) {
                if (ip != null && !ip.isEmpty() && !present.contains(ip)) return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ---------------------------------------------------------------- DER

    static byte[] tlv(int tag, byte[] value) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(tag);
        int len = value.length;
        if (len < 128) {
            o.write(len);
        } else if (len < 256) {
            o.write(0x81);
            o.write(len);
        } else if (len < 65536) {
            o.write(0x82);
            o.write(len >> 8);
            o.write(len);
        } else {
            o.write(0x83);
            o.write(len >> 16);
            o.write(len >> 8);
            o.write(len);
        }
        o.write(value, 0, value.length);
        return o.toByteArray();
    }

    static byte[] seq(byte[]... parts) {
        return tlv(0x30, concat(parts));
    }

    static byte[] set(byte[]... parts) {
        return tlv(0x31, concat(parts));
    }

    static byte[] octets(byte[] v) {
        return tlv(0x04, v);
    }

    static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) o.write(p, 0, p.length);
        return o.toByteArray();
    }

    static byte[] oid(String dotted) {
        String[] p = dotted.split("\\.");
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(Integer.parseInt(p[0]) * 40 + Integer.parseInt(p[1]));
        for (int i = 2; i < p.length; i++) {
            long v = Long.parseLong(p[i]);
            byte[] tmp = new byte[10];
            int n = 0;
            do {
                tmp[n++] = (byte) (v & 0x7f);
                v >>= 7;
            } while (v > 0);
            for (int j = n - 1; j >= 0; j--) o.write(tmp[j] | (j > 0 ? 0x80 : 0));
        }
        return tlv(0x06, o.toByteArray());
    }

    /** UTCTime before 2050, GeneralizedTime after (RFC 5280). */
    static byte[] time(long ms) {
        Date d = new Date(ms);
        SimpleDateFormat year = new SimpleDateFormat("yyyy", Locale.ROOT);
        year.setTimeZone(TimeZone.getTimeZone("UTC"));
        boolean utc = Integer.parseInt(year.format(d)) < 2050;
        SimpleDateFormat f = new SimpleDateFormat(utc ? "yyMMddHHmmss'Z'" : "yyyyMMddHHmmss'Z'", Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return tlv(utc ? 0x17 : 0x18, f.format(d).getBytes(StandardCharsets.US_ASCII));
    }
}
