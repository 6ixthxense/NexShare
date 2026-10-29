package com.nexshare;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.Socket;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSocketFactory;

/**
 * HTTPS for the LAN and Tailscale: a self-signed certificate (stored as PKCS#12 in the app's
 * private files) covering nexshare.local, localhost, the phone's LAN IP, and its Tailscale IP
 * (100.64.0.0/10). Automatically regenerates when the LAN or Tailscale IP changes.
 */
public final class TlsManager {

    private static TlsManager instance;
    private final Context ctx;
    private final File file;
    private KeyStore ks;
    private SSLSocketFactory cachedFactory;
    private long lastIpCheckMs;

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

    /** Current IPv4 addresses that should appear in the certificate's SubjectAlternativeName. */
    public static List<String> currentIps() {
        List<String> ips = new ArrayList<>();
        ips.add("127.0.0.1");
        Inet4Address lan = MdnsResponder.lanAddress();
        if (lan != null && !ips.contains(lan.getHostAddress())) ips.add(lan.getHostAddress());
        Inet4Address ts = MdnsResponder.tailscaleAddress();
        if (ts != null && !ips.contains(ts.getHostAddress())) ips.add(ts.getHostAddress());
        return ips;
    }

    private synchronized KeyStore keyStore() throws Exception {
        long now = System.currentTimeMillis();
        if (ks != null) {
            if (now - lastIpCheckMs > 5000) {
                lastIpCheckMs = now;
                X509Certificate c = (X509Certificate) ks.getCertificate(CertGen.ALIAS);
                if (!CertGen.hasIps(c, currentIps())) {
                    return regenerate();
                }
            }
            return ks;
        }
        if (file.isFile()) {
            try (InputStream in = new FileInputStream(file)) {
                KeyStore k = KeyStore.getInstance("PKCS12");
                k.load(in, pw());
                X509Certificate c = (X509Certificate) k.getCertificate(CertGen.ALIAS);
                c.checkValidity(new java.util.Date(now + 7L * 24 * 3600 * 1000));
                if (CertGen.hasIps(c, currentIps())) {
                    ks = k;
                    cachedFactory = null;
                    lastIpCheckMs = now;
                    return ks;
                }
            } catch (Exception expiredOrBroken) {
                // fall through and regenerate
            }
        }
        return regenerate();
    }

    /** Creates a new key + certificate covering current LAN and Tailscale IPs. */
    public synchronized KeyStore regenerate() throws Exception {
        List<String> ips = currentIps();
        KeyStore k = CertGen.createKeyStore(MdnsResponder.HOST,
            Arrays.asList(MdnsResponder.HOST, "localhost"), ips, pw());
        File tmp = new File(file.getPath() + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            k.store(out, pw());
        }
        if (file.exists()) file.delete();
        if (!tmp.renameTo(file)) throw new IOException("cannot save certificate");
        ks = k;
        cachedFactory = null;
        lastIpCheckMs = System.currentTimeMillis();
        return k;
    }

    private synchronized SSLSocketFactory currentSocketFactory() throws Exception {
        KeyStore current = keyStore();
        if (cachedFactory != null && ks == current) return cachedFactory;
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(current, pw());
        SSLContext sc = SSLContext.getInstance("TLS");
        sc.init(kmf.getKeyManagers(), null, null);
        cachedFactory = sc.getSocketFactory();
        return cachedFactory;
    }

    /** Dynamic SSLSocketFactory that automatically picks up regenerated certificates on IP changes. */
    public SSLSocketFactory socketFactory() throws Exception {
        currentSocketFactory(); // validate/generate immediately on startup
        return new SSLSocketFactory() {
            private SSLSocketFactory delegate() throws IOException {
                try {
                    return currentSocketFactory();
                } catch (IOException e) {
                    throw e;
                } catch (Exception e) {
                    throw new IOException(e);
                }
            }
            @Override public String[] getDefaultCipherSuites() {
                try { return delegate().getDefaultCipherSuites(); } catch (IOException e) { return new String[0]; }
            }
            @Override public String[] getSupportedCipherSuites() {
                try { return delegate().getSupportedCipherSuites(); } catch (IOException e) { return new String[0]; }
            }
            @Override public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
                return delegate().createSocket(s, host, port, autoClose);
            }
            @Override public Socket createSocket(String host, int port) throws IOException {
                return delegate().createSocket(host, port);
            }
            @Override public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
                return delegate().createSocket(host, port, localHost, localPort);
            }
            @Override public Socket createSocket(InetAddress host, int port) throws IOException {
                return delegate().createSocket(host, port);
            }
            @Override public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
                return delegate().createSocket(address, port, localAddress, localPort);
            }
        };
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
