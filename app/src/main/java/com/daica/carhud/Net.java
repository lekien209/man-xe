package com.daica.carhud;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/** Mạng: Android 6 không tin chứng chỉ gốc Let's Encrypt (ISRG X1) -> nạp thêm từ assets. */
public final class Net {
    public static final String UA = "CarHud/1.0 (Kovar T1; personal car display)";

    public static void installTrust(Context ctx) {
        try {
            List<X509TrustManager> tms = new ArrayList<>();
            TrustManagerFactory sys = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            sys.init((KeyStore) null);
            for (TrustManager t : sys.getTrustManagers()) if (t instanceof X509TrustManager) tms.add((X509TrustManager) t);

            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(null, null);
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            String[] files = ctx.getAssets().list("certs");
            int n = 0;
            if (files != null) for (String f : files) {
                InputStream in = ctx.getAssets().open("certs/" + f);
                try { ks.setCertificateEntry("extra" + (n++), cf.generateCertificate(in)); } catch (Exception ignored) {}
                in.close();
            }
            if (n > 0) {
                TrustManagerFactory ex = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                ex.init(ks);
                for (TrustManager t : ex.getTrustManagers()) if (t instanceof X509TrustManager) tms.add((X509TrustManager) t);
            }
            final X509TrustManager[] all = tms.toArray(new X509TrustManager[0]);
            X509TrustManager combo = new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] chain, String auth) throws CertificateException {
                    all[0].checkClientTrusted(chain, auth);
                }
                public void checkServerTrusted(X509Certificate[] chain, String auth) throws CertificateException {
                    CertificateException last = null;
                    for (X509TrustManager t : all) {
                        try { t.checkServerTrusted(chain, auth); return; } catch (CertificateException e) { last = e; }
                    }
                    if (last != null) throw last;
                    throw new CertificateException("no trust manager");
                }
                public X509Certificate[] getAcceptedIssuers() {
                    List<X509Certificate> l = new ArrayList<>();
                    for (X509TrustManager t : all) for (X509Certificate c : t.getAcceptedIssuers()) l.add(c);
                    return l.toArray(new X509Certificate[0]);
                }
            };
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, new TrustManager[]{combo}, null);
            HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
        } catch (Exception ignored) {
        }
    }

    public static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(12000);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept-Language", "vi,en");
        try {
            int code = c.getResponseCode();
            if (code / 100 != 2) throw new Exception("HTTP " + code);
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int r;
            while ((r = in.read(b)) > 0) bo.write(b, 0, r);
            in.close();
            return bo.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    private Net() {}
}
