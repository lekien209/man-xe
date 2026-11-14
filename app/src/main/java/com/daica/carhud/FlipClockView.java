package com.daica.carhud;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.AttributeSet;

import java.util.Calendar;
import java.util.HashMap;

/** Đồng hồ lật kiểu HTC: chỉ giờ và phút. Hệ toạ độ 318x130. */
public class FlipClockView extends AnimView {
    private static final float W = 318, H = 130, TW = 132, TH = 102, TY = 14;
    private static final float[] TX = {22, 164};
    private static final long FLIP_MS = 450;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();

    private final String[] cur = {"", ""}, old = {"", ""};
    private final long[] flipStart = {0, 0};
    // mỗi số vẽ sẵn thành ảnh: lúc lật chỉ co giãn ảnh, không vẽ lại chữ to (rất nặng trên GPU yếu)
    private final HashMap<String, Bitmap> cache = new HashMap<>();
    private final Paint bp = new Paint(Paint.FILTER_BITMAP_FLAG);
    private int cacheW = 0, cacheDay = -1;

    public FlipClockView(Context c, AttributeSet a) {
        super(c, a);
        tp.setTextAlign(Paint.Align.CENTER);
        tp.setFakeBoldText(true);
    }

    private void tick() {
        Calendar cal = Calendar.getInstance();
        String[] v = {two(cal.get(Calendar.HOUR_OF_DAY)), two(cal.get(Calendar.MINUTE))};
        long now = SystemClock.uptimeMillis();
        for (int i = 0; i < 2; i++) {
            if (!v[i].equals(cur[i])) {
                old[i] = cur[i].length() == 0 ? v[i] : cur[i];
                cur[i] = v[i];
                flipStart[i] = cur[i].equals(old[i]) ? 0 : now;
            }
        }
    }

    private static String two(int n) { return n < 10 ? "0" + n : String.valueOf(n); }

    @Override
    protected void onDraw(Canvas c) {
        tick();
        long now = SystemClock.uptimeMillis();
        float s = getWidth() / W;
        c.save();
        c.scale(s, getHeight() / H);
        r.set(0, 0, W, H);
        p.setStyle(Paint.Style.FILL);
        p.setColor(Theme.panel());
        c.drawRoundRect(r, 16, 16, p);

        boolean animating = false;
        for (int i = 0; i < 2; i++) {
            float t = flipStart[i] == 0 ? 1 : Math.min(1, (now - flipStart[i]) / (float) FLIP_MS);
            if (t >= 1) flipStart[i] = 0; else animating = true;
            drawTile(c, TX[i], TY, cur[i], old[i], t);
        }
        // dấu hai chấm
        p.setColor(Theme.sub());
        c.drawCircle(W / 2, TY + TH / 2 - 14, 3.5f, p);
        c.drawCircle(W / 2, TY + TH / 2 + 14, 3.5f, p);
        c.restore();

        if (animating) next(33);
        else {
            // lần vẽ tiếp theo ngay đầu phút sau
            long ms = 60000 - (System.currentTimeMillis() % 60000) + 30;
            next(Math.min(ms, 60000));
        }
    }

    private int topCol() { return Theme.day ? 0xFF3A4350 : 0xFF2A313C; }
    private int botCol() { return Theme.day ? 0xFF323A46 : 0xFF232A34; }

    private void drawTile(Canvas c, float x, float y, String now, String prev, float t) {
        float mid = y + TH / 2;
        // nửa trên tĩnh: số mới; nửa dưới tĩnh: số cũ cho đến khi lật xong
        half(c, x, y, now, true, 1);
        half(c, x, y, t >= 1 ? now : prev, false, 1);
        if (t < 1) {
            if (t < 0.5f) {
                // nắp trên (số cũ) gập xuống
                float k = 1 - t * 2;
                c.save();
                c.scale(1, k, x + TW / 2, mid);
                half(c, x, y, prev, true, 0.75f + 0.25f * k);
                c.restore();
            } else {
                // nắp dưới (số mới) mở ra
                float k = (t - 0.5f) * 2;
                c.save();
                c.scale(1, k, x + TW / 2, mid);
                half(c, x, y, now, false, 0.75f + 0.25f * k);
                c.restore();
            }
        }
        // đường chia + bản lề
        p.setColor(0xFF0E1116);
        c.drawRect(x, mid - 1, x + TW, mid + 1, p);
        p.setColor(0xFF5A6472);
        r.set(x - 2, mid - 6, x + 3, mid + 6); c.drawRoundRect(r, 2, 2, p);
        r.set(x + TW - 3, mid - 6, x + TW + 2, mid + 6); c.drawRoundRect(r, 2, 2, p);
    }

    private Bitmap tileBmp(String txt) {
        int pw = Math.round(TW * getWidth() / W), ph = Math.round(TH * getHeight() / H);
        int d = Theme.day ? 1 : 0;
        if (pw != cacheW || d != cacheDay) {
            for (Bitmap bm : cache.values()) bm.recycle();
            cache.clear();
            cacheW = pw; cacheDay = d;
        }
        Bitmap bm = cache.get(txt);
        if (bm != null) return bm;
        if (cache.size() > 8) { for (Bitmap x : cache.values()) x.recycle(); cache.clear(); }
        bm = Bitmap.createBitmap(Math.max(1, pw), Math.max(1, ph), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bm);
        c.scale(pw / TW, ph / TH);
        c.save(); c.clipRect(0, 0, TW, TH / 2);
        p.setColor(topCol()); r.set(0, 0, TW, TH); c.drawRoundRect(r, 12, 12, p); c.restore();
        c.save(); c.clipRect(0, TH / 2, TW, TH);
        p.setColor(botCol()); r.set(0, 0, TW, TH); c.drawRoundRect(r, 12, 12, p); c.restore();
        tp.setTextSize(84);
        tp.setColor(0xFFF2F4F7);
        c.drawText(txt, TW / 2, TH / 2 + 30, tp);
        cache.put(txt, bm);
        return bm;
    }

    private void half(Canvas c, float x, float y, String txt, boolean top, float light) {
        if (txt.length() == 0) return;
        float mid = y + TH / 2;
        c.save();
        if (top) c.clipRect(x, y, x + TW, mid); else c.clipRect(x, mid, x + TW, y + TH);
        r.set(x, y, x + TW, y + TH);
        c.drawBitmap(tileBmp(txt), null, r, bp);
        if (light < 1) {
            p.setColor(Theme.alpha(0xFF000000, (1 - light) * 0.9f));
            if (top) c.drawRect(x, y, x + TW, mid, p); else c.drawRect(x, mid, x + TW, y + TH, p);
        }
        c.restore();
    }
}
