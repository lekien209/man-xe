package com.daica.carhud;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Tự cập nhật app từ trang Releases của kho GitHub lekien209/man-xe (kho công khai). */
public final class Updater {
    public static final String REPO = "lekien209/man-xe";
    public static final String APK_URL = "https://github.com/" + REPO + "/releases/latest/download/ManXe.apk";

    public interface Progress { void on(int percent); }

    /** Trả về số bản mới nhất trên GitHub (vd tag "b12" -> 112), -1 nếu không lấy được. */
    public static int latestVersionCode() throws Exception {
        JSONObject j = new JSONObject(Net.get("https://api.github.com/repos/" + REPO + "/releases/latest"));
        String tag = j.optString("tag_name", "");
        if (!tag.startsWith("b")) return -1;
        return Integer.parseInt(tag.substring(1)) + 100;
    }

    public static int currentVersionCode(Context c) {
        try { return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionCode; } catch (Exception e) { return 0; }
    }

    /** Tải APK về bộ nhớ ngoài (trình cài đặt của Android đọc được) và trả về file. */
    public static File download(Context c, Progress pr) throws Exception {
        File dir = c.getExternalCacheDir();
        if (dir == null) dir = c.getExternalFilesDir(null);
        File f = new File(dir, "ManXe-update.apk");
        String url = APK_URL;
        HttpURLConnection con = null;
        for (int hop = 0; hop < 5; hop++) { // tự theo chuyển hướng (github.com -> máy chủ file)
            con = (HttpURLConnection) new URL(url).openConnection();
            con.setInstanceFollowRedirects(false);
            con.setConnectTimeout(10000);
            con.setReadTimeout(20000);
            con.setRequestProperty("User-Agent", Net.UA);
            int code = con.getResponseCode();
            if (code / 100 == 3) { url = con.getHeaderField("Location"); con.disconnect(); continue; }
            if (code != 200) throw new Exception("HTTP " + code);
            break;
        }
        int total = con.getContentLength();
        InputStream in = con.getInputStream();
        FileOutputStream out = new FileOutputStream(f);
        byte[] b = new byte[16384];
        int r, done = 0, last = -1;
        while ((r = in.read(b)) > 0) {
            out.write(b, 0, r);
            done += r;
            if (total > 0 && pr != null) {
                int p = (int) (100L * done / total);
                if (p != last) { last = p; pr.on(p); }
            }
        }
        out.close();
        in.close();
        con.disconnect();
        if (done < 100000) throw new Exception("file tải về bị lỗi");
        f.setReadable(true, false);
        return f;
    }

    public static void install(Context c, File f) {
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(Uri.fromFile(f), "application/vnd.android.package-archive");
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        c.startActivity(i);
    }

    private Updater() {}
}
