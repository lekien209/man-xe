package com.daica.carhud;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;

/**
 * View có hiệu ứng. Tất cả dùng chung MỘT nhịp ~30 khung/giây nên các lần vẽ lại được gộp
 * vào cùng một khung hình. Dừng hẳn khi app chạy nền.
 */
public abstract class AnimView extends View {
    public static boolean running = true;
    private static final long FRAME = 33;
    private static final ArrayList<AnimView> all = new ArrayList<>();
    private static final Handler H = new Handler(Looper.getMainLooper());
    private static boolean scheduled = false;

    private static final Runnable TICK = new Runnable() {
        public void run() {
            scheduled = false;
            if (!running) return;
            long now = SystemClock.uptimeMillis();
            boolean pending = false;
            for (int i = 0; i < all.size(); i++) {
                AnimView v = all.get(i);
                if (v.due == 0) continue;
                if (now >= v.due - 8) { v.due = 0; v.invalidate(); }
                else pending = true;
            }
            if (pending) schedule();
        }
    };

    private static void schedule() {
        if (scheduled) return;
        scheduled = true;
        long now = SystemClock.uptimeMillis();
        H.postAtTime(TICK, now + FRAME - (now % FRAME));
    }

    /** Gọi khi app trở lại màn hình: vẽ lại tất cả. */
    public static void resumeAll() {
        running = true;
        for (AnimView v : all) v.invalidate();
    }

    private long due = 0;

    public AnimView(Context c, AttributeSet a) {
        super(c, a);
        all.add(this);
    }

    /** Hẹn vẽ lại sau ms mili-giây (gộp theo nhịp chung). */
    protected void next(long ms) {
        if (!running) return;
        due = SystemClock.uptimeMillis() + ms;
        schedule();
    }

    @Override
    protected void onDetachedFromWindow() {
        due = 0;
        super.onDetachedFromWindow();
    }
}
