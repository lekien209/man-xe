package com.daica.carhud;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

/** Tự mở app khi màn khởi động hoặc khi bật chìa khoá (ACC ON). */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(final Context ctx, Intent intent) {
        if (!ctx.getSharedPreferences("carhud", 0).getBoolean("autostart", true)) return;
        final Context app = ctx.getApplicationContext();
        final PendingResult pr = goAsync();
        // đợi 3 giây cho hệ thống ổn định rồi mở
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            public void run() {
                try {
                    Intent i = new Intent(app, MainActivity.class);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                    app.startActivity(i);
                } catch (Exception ignored) {
                }
                pr.finish();
            }
        }, 3000);
    }
}
