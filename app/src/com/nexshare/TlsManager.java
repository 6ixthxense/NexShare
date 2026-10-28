package com.nexshare;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocketFactory;

/**
 * HTTPS for the LAN: a self-signed certificate (generated once, stored as PKCS#12 in the app's
 * private files) covering nexshare.local, localhost and the phone's LAN IP. Browsers warn on
 * first visit; users can install the certificate (downloadable from Control Panel) to trust it.
 */
public final class TlsManager {

    private static TlsManager instance;
    private final Context ctx;
    private final File file;
    private KeyStore ks;

    public static synchronized TlsManager get(Context c) {
        if (instance == null) instance = new TlsManager(c.getApplicationContext());
        return instance;
    }

    private TlsManager(Context ctx) {
        this.ctx = ctx;
        this.file = new File(ctx.getFilesDir(), "tls.p12");
    }

    private char[] pw() {
        return Settings.tlsKeystorePassword(ctx).toCharArray();
    }

    private synchronized KeyStore keyStore() throws Exception {
        if (ks != null) return ks;
        if (file.isFile()) {
            try (InputStream in = new FileInputStream(file)) {
                KeyStore k = KeyStore.getInstance("PKCS12");
                k.load(in, pw());
                X509Certificate c = (X509Certificate) k.getCertificate(CertGen.ALIAS);
                c.checkValidity(new java.util.Date(System.currentTimeMillis() + 7L * 24 * 3600 * 1000));
                ks = k;
                return ks;
            } catch (Exception expiredOrBroken) {
                // fall through and regenerate
            }
        }
        return regenerate();
    }

    /** Creates a new key + certificate (e.g. after the LAN IP changed). */
    public synchronized KeyStore regenerate() throws Exception {
        List<String> ips = new ArrayList<>(Arrays.asList("127.0.0.1"));
        Inet4Address lan = MdnsResponder.lanAddress();
        if (lan != null) ips.add(lan.getHostAddress());
        KeyStore k = CertGen.createKeyStore(MdnsResponder.HOST,
            Arrays.asList(MdnsResponder.HOST, "localhost"), ips, pw());
        File tmp = new File(file.getPath() + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            k.store(out, pw());
        }
        if (!tmp.renameTo(file)) throw new java.io.IOException("cannot save certificate");
        ks = k;
        return k;
    }

    public SSLServerSocketFactory serverSocketFactory() throws Exception {
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore(), pw());
        SSLContext sc = SSLContext.getInstance("TLS");
        sc.init(kmf.getKeyManagers(), null, null);
        return sc.getServerSocketFactory();
    }

    public X509Certificate certificate() throws Exception {
        return (X509Certificate) keyStore().getCertificate(CertGen.ALIAS);
    }

    public String fingerprint() {
        try {
            return CertGen.fingerprint(certificate());
        } catch (Exception e) {
            return "";
        }
    }

    public String pem() throws Exception {
        return CertGen.pem(certificate());
    }
}
