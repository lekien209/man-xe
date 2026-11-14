package com.daica.carhud;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Bật nguồn "Nhạc Bluetooth" của màn bằng cách mở nhanh màn hình nhạc trong app Bluetooth gốc.
 * Bluetooth (ghép, nối, truyền âm thanh) vẫn do app gốc làm; app mình chỉ "bấm hộ" để màn chuyển nguồn.
 */
public final class BtAudio {

    /** Một màn hình (activity) có thể mở được trong app Bluetooth. */
    public static class Target {
        public String pkg, cls, label;
        @Override public String toString() { return label; }
    }

    /** Tìm app Bluetooth gốc: tên hiển thị có chữ "Bluetooth"/"BT", hoặc tên gói có bt/bluetooth. */
    public static List<ResolveInfo> findBtApps(Context c) {
        PackageManager pm = c.getPackageManager();
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> out = new ArrayList<>();
        for (ResolveInfo r : pm.queryIntentActivities(i, 0)) {
            String pkg = r.activityInfo.packageName;
            if (pkg.equals(c.getPackageName()) || pkg.equals("com.android.settings")) continue;
            String label = String.valueOf(r.loadLabel(pm)).toLowerCase(Locale.ROOT);
            String p = pkg.toLowerCase(Locale.ROOT);
            boolean byLabel = label.contains("bluetooth") || label.equals("bt") || label.contains("蓝牙");
            boolean byPkg = p.contains("bluetooth") || p.matches(".*[._]bt([._].*|$)") || p.contains(".btphone") || p.contains(".bt.");
            if (byLabel || byPkg) {
                if (byLabel) out.add(0, r); else out.add(r);
            }
        }
        return out;
    }

    /** Liệt kê các màn hình mở được trong 1 gói, màn hình nhạc lên đầu. */
    public static List<Target> listActivities(Context c, String pkg) {
        List<Target> music = new ArrayList<>(), other = new ArrayList<>();
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(pkg, PackageManager.GET_ACTIVITIES);
            if (pi.activities != null) for (ActivityInfo a : pi.activities) {
                if (!a.exported || !a.enabled) continue;
                Target t = new Target();
                t.pkg = pkg;
                t.cls = a.name;
                String shortName = a.name.substring(a.name.lastIndexOf('.') + 1);
                t.label = shortName;
                if (isMusicName(a.name)) { t.label = "♪ " + shortName; music.add(t); } else other.add(t);
            }
        } catch (Exception ignored) {
        }
        music.addAll(other);
        return music;
    }

    private static boolean isMusicName(String n) {
        n = n.toLowerCase(Locale.ROOT);
        return n.contains("music") || n.contains("a2dp") || n.contains("audio") || n.contains("media") || n.contains("player");
    }

    /** Màn hình sẽ mở để chuyển nguồn: đã chọn tay > màn hình nhạc tự tìm > màn hình chính app Bluetooth. */
    public static Intent targetIntent(Context c, SharedPreferences prefs) {
        String pkg = prefs.getString("btPkg", null), cls = prefs.getString("btCls", null);
        if (pkg != null && cls != null) {
            return new Intent().setComponent(new ComponentName(pkg, cls));
        }
        List<ResolveInfo> apps = findBtApps(c);
        if (apps.isEmpty()) return null;
        String p = apps.get(0).activityInfo.packageName;
        for (Target t : listActivities(c, p)) {
            if (t.label.startsWith("♪")) return new Intent().setComponent(new ComponentName(t.pkg, t.cls));
        }
        Intent li = c.getPackageManager().getLaunchIntentForPackage(p);
        return li;
    }

    public static String describe(Context c, SharedPreferences prefs) {
        Intent i = targetIntent(c, prefs);
        if (i == null || i.getComponent() == null) return "chưa tìm thấy app Bluetooth";
        ComponentName cn = i.getComponent();
        return cn.getPackageName() + " / " + cn.getClassName().substring(cn.getClassName().lastIndexOf('.') + 1);
    }

    private BtAudio() {}
}
