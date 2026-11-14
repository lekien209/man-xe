package com.daica.carhud;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.MotionEvent;

/** Ô nhạc Bluetooth. Hệ toạ độ 318x264. */
public class MusicView extends AnimView {
    public interface Actions { void onPrev(); void onPlayPause(); void onNext(); void onNeedPermission(); }

    private static final float W = 318, H = 264;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final Path tri = new Path();

    public MusicController mc;
    public Actions actions;
    public boolean btConnected = false;
    public boolean perm = false; // đã cấp quyền đọc thông báo (MainActivity cập nhật khi onResume)
    private int pressed = -1;

    public MusicView(Context c, AttributeSet a) { super(c, a); }

    @Override
    protected void onDraw(Canvas c) {
        c.save();
        c.scale(getWidth() / W, getHeight() / H);
        p.setStyle(Paint.Style.FILL);
        r.set(0, 0, W, H);
        p.setColor(Theme.panel());
        c.drawRoundRect(r, 16, 16, p);

        // tiêu đề
        btIcon(c, 26, 26, btConnected ? Theme.ACCENT : Theme.sub());
        tp.setTextSize(14); tp.setColor(Theme.sub()); tp.setFakeBoldText(false);
        c.drawText(btConnected ? "Bluetooth từ iPhone" : "Bluetooth chưa nối", 38, 31, tp);

        // ảnh bìa
        r.set(20, 46, 130, 156);
        p.setColor(Theme.panel2());
        c.drawRoundRect(r, 12, 12, p);
        Bitmap art = mc == null ? null : mc.art;
        if (art != null && !art.isRecycled()) {
            c.save();
            c.clipRect(r);
            c.drawBitmap(art, null, r, p);
            c.restore();
        } else {
            noteIcon(c, 75, 101, Theme.ACCENT);
        }

        // tên bài
        String title = mc == null || mc.title.length() == 0 ? "Chưa phát nhạc" : mc.title;
        String artist = mc == null ? "" : mc.artist;
        if (!perm && (mc == null || mc.title.length() == 0)) artist = "Chạm để cho phép đọc tên bài";
        float tx = 146, avail = W - tx - 18;
        tp.setTextSize(20); tp.setFakeBoldText(true); tp.setColor(Theme.text());
        c.drawText(TextUtils.ellipsize(title, tp, avail, TextUtils.TruncateAt.END).toString(), tx, 86, tp);
        tp.setTextSize(15); tp.setFakeBoldText(false); tp.setColor(perm ? Theme.sub() : Theme.ACCENT);
        c.drawText(TextUtils.ellipsize(artist, tp, avail, TextUtils.TruncateAt.END).toString(), tx, 112, tp);

        // tiến trình
        long dur = mc == null ? 0 : mc.duration, pos = mc == null ? 0 : mc.currentPos();
        r.set(20, 178, W - 20, 182);
        p.setColor(Theme.panel2()); c.drawRoundRect(r, 2, 2, p);
        if (dur > 0) {
            float f = pos / (float) dur;
            r.set(20, 178, 20 + (W - 40) * f, 182);
            p.setColor(Theme.ACCENT); c.drawRoundRect(r, 2, 2, p);
            c.drawCircle(20 + (W - 40) * f, 180, 5, p);
        }
        tp.setTextSize(12); tp.setColor(Theme.sub());
        c.drawText(dur > 0 ? fmt(pos) : "--:--", 20, 200, tp);
        String d = dur > 0 ? fmt(dur) : "--:--";
        c.drawText(d, W - 20 - tp.measureText(d), 200, tp);

        // nút
        boolean playing = mc != null && mc.playing;
        int ic = Theme.text();
        if (pressed == 0) { p.setColor(Theme.panel2()); c.drawCircle(99, 232, 22, p); }
        if (pressed == 2) { p.setColor(Theme.panel2()); c.drawCircle(219, 232, 22, p); }
        skip(c, 99, 232, true, ic);
        skip(c, 219, 232, false, ic);
        p.setColor(pressed == 1 ? 0xFF24998B : Theme.ACCENT);
        c.drawCircle(159, 232, 24, p);
        p.setColor(0xFF0E1116);
        if (playing) {
            r.set(151, 222, 156, 242); c.drawRoundRect(r, 1.5f, 1.5f, p);
            r.set(162, 222, 167, 242); c.drawRoundRect(r, 1.5f, 1.5f, p);
        } else {
            tri.reset(); tri.moveTo(153, 220); tri.lineTo(171, 232); tri.lineTo(153, 244); tri.close();
            c.drawPath(tri, p);
        }
        c.restore();
        next(playing ? 1000 : 5000);
    }

    private void skip(Canvas c, float x, float y, boolean back, int col) {
        p.setColor(col);
        float d = back ? -1 : 1;
        tri.reset();
        tri.moveTo(x - 8 * d, y - 9); tri.lineTo(x + 4 * d, y); tri.lineTo(x - 8 * d, y + 9); tri.close();
        c.drawPath(tri, p);
        r.set(Math.min(x + 4 * d, x + 7 * d), y - 9, Math.max(x + 4 * d, x + 7 * d), y + 9);
        c.drawRect(r, p);
    }

    private void btIcon(Canvas c, float x, float y, int col) {
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1.6f); p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND);
        p.setColor(col);
        tri.reset();
        tri.moveTo(x - 4, y - 3); tri.lineTo(x + 4, y + 4); tri.lineTo(x, y + 8); tri.lineTo(x, y - 8);
        tri.lineTo(x + 4, y - 4); tri.lineTo(x - 4, y + 3);
        c.drawPath(tri, p);
        p.setStyle(Paint.Style.FILL);
    }

    private void noteIcon(Canvas c, float x, float y, int col) {
        p.setColor(col);
        c.drawCircle(x - 10, y + 14, 8, p);
        c.drawCircle(x + 14, y + 8, 8, p);
        r.set(x - 4, y - 18, x, y + 14); c.drawRect(r, p);
        r.set(x + 20, y - 24, x + 24, y + 8); c.drawRect(r, p);
        tri.reset(); tri.moveTo(x - 4, y - 18); tri.lineTo(x + 24, y - 24); tri.lineTo(x + 24, y - 16); tri.lineTo(x - 4, y - 10); tri.close();
        c.drawPath(tri, p);
    }

    private static String fmt(long ms) {
        long s = ms / 1000;
        long m = s / 60;
        s %= 60;
        return m + ":" + (s < 10 ? "0" + s : String.valueOf(s));
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX() * W / getWidth(), y = e.getY() * H / getHeight();
        int hit = -1;
        if (y > 200) {
            if (Math.abs(x - 99) < 34) hit = 0;
            else if (Math.abs(x - 159) < 30) hit = 1;
            else if (Math.abs(x - 219) < 34) hit = 2;
        } else hit = 3;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pressed = hit < 3 ? hit : -1;
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
                pressed = -1;
                invalidate();
                if (actions != null) {
                    if (hit == 0) actions.onPrev();
                    else if (hit == 1) actions.onPlayPause();
                    else if (hit == 2) actions.onNext();
                    else if (hit == 3 && !perm) actions.onNeedPermission();
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                pressed = -1;
                invalidate();
                return true;
        }
        return true;
    }
}
