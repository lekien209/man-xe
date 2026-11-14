package com.daica.carhud;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.AttributeSet;

/** Ô thời tiết động: mặt trời xoay / trăng, mây trôi, mưa rơi. Hệ toạ độ 318x130. */
public class WeatherView extends AnimView {
    private static final float W = 318, H = 130;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();

    private String temp = "--°";
    private String cond = "Đang tải thời tiết…";
    private int kind = 1; // 0 quang, 1 ít mây, 2 nhiều mây, 3 sương mù, 4 mưa, 5 dông

    public WeatherView(Context c, AttributeSet a) { super(c, a); }

    public void set(double t, int wmo) {
        temp = Math.round(t) + "°";
        if (wmo == 0) { kind = 0; cond = "Trời quang"; }
        else if (wmo == 1) { kind = 1; cond = "Ít mây"; }
        else if (wmo == 2) { kind = 1; cond = "Có mây"; }
        else if (wmo == 3) { kind = 2; cond = "Nhiều mây"; }
        else if (wmo == 45 || wmo == 48) { kind = 3; cond = "Sương mù"; }
        else if (wmo >= 51 && wmo <= 57) { kind = 4; cond = "Mưa phùn"; }
        else if (wmo >= 61 && wmo <= 67) { kind = 4; cond = wmo >= 65 ? "Mưa to" : "Mưa"; }
        else if (wmo >= 80 && wmo <= 82) { kind = 4; cond = "Mưa rào"; }
        else if (wmo >= 95) { kind = 5; cond = "Dông"; }
        else { kind = 2; cond = "Nhiều mây"; }
        invalidate();
    }

    public void setError(String msg) { cond = msg; invalidate(); }

    @Override
    protected void onDraw(Canvas c) {
        float t = (SystemClock.uptimeMillis() % 600000) / 1000f;
        c.save();
        c.scale(getWidth() / W, getHeight() / H);
        p.setStyle(Paint.Style.FILL);
        r.set(0, 0, W, H);
        p.setColor(Theme.panel());
        c.drawRoundRect(r, 16, 16, p);

        float cx = 62, cy = 62;
        boolean showSun = kind <= 1;
        if (showSun || kind == 2) {
            if (Theme.day) {
                // tia nắng xoay
                c.save();
                c.rotate(t * 12, cx, cy);
                p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(3.2f); p.setStrokeCap(Paint.Cap.ROUND);
                p.setColor(0xFFFFC23D);
                for (int i = 0; i < 10; i++) {
                    double a = Math.PI * 2 * i / 10;
                    float cs = (float) Math.cos(a), sn = (float) Math.sin(a);
                    c.drawLine(cx + cs * 30, cy + sn * 30, cx + cs * 38, cy + sn * 38, p);
                }
                c.restore();
                p.setStyle(Paint.Style.FILL);
                float pulse = (float) Math.sin(t * 2) * 1.2f;
                p.setColor(0x33FFC23D); c.drawCircle(cx, cy, 27 + pulse, p);
                p.setColor(0xFFFFC23D); c.drawCircle(cx, cy, 22, p);
                p.setColor(0xFFFFD86B); c.drawCircle(cx - 5, cy - 5, 11, p);
            } else {
                p.setColor(0x22F3E7C4); c.drawCircle(cx, cy, 30, p);
                p.setColor(0xFFF3E7C4); c.drawCircle(cx, cy, 22, p);
                p.setColor(Theme.panel()); c.drawCircle(cx + 10, cy - 7, 19, p);
                // sao lấp lánh
                for (int i = 0; i < 3; i++) {
                    float a = 0.4f + 0.6f * (float) Math.abs(Math.sin(t * 1.3 + i * 2.1));
                    p.setColor(Theme.alpha(0xFFF3E7C4, a));
                    float[][] st = {{100, 30}, {28, 26}, {108, 84}};
                    c.drawCircle(st[i][0], st[i][1], 1.8f, p);
                }
            }
        }
        if (kind >= 1) {
            float drift = (float) Math.sin(t * 0.5) * 8;
            int cc = kind >= 4 ? (Theme.day ? 0xFF9AA3AE : 0xFF6B7482) : (Theme.day ? 0xFFF4F6F8 : 0xFFAEB7C4);
            cloud(c, 72 + drift, 80, kind == 1 ? 0.8f : 1f, cc);
            if (kind >= 2) cloud(c, 42 - drift * 0.6f, 66, 0.65f, Theme.alpha(cc, 0.75f));
        }
        if (kind == 3) {
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(3); p.setStrokeCap(Paint.Cap.ROUND);
            p.setColor(Theme.alpha(Theme.sub(), 0.7f));
            for (int i = 0; i < 3; i++) {
                float o = (float) Math.sin(t + i) * 6;
                c.drawLine(28 + o, 96 + i * 8, 100 + o, 96 + i * 8, p);
            }
            p.setStyle(Paint.Style.FILL);
        }
        if (kind >= 4) {
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2.2f); p.setStrokeCap(Paint.Cap.ROUND);
            p.setColor(0xFF5DB4FF);
            for (int i = 0; i < 6; i++) {
                float ph = ((t * 1.6f + i * 0.37f) % 1f);
                float x = 48 + i * 9, y = 96 + ph * 26;
                p.setAlpha(Math.round(255 * (1 - ph)));
                c.drawLine(x, y, x - 3, y + 7, p);
            }
            p.setAlpha(255);
            p.setStyle(Paint.Style.FILL);
            if (kind == 5 && (t % 4f) < 0.15f) {
                p.setColor(0xFFFFD84A);
                c.drawRect(68, 92, 72, 112, p);
            }
        }

        tp.setTextAlign(Paint.Align.RIGHT);
        tp.setTextSize(54); tp.setFakeBoldText(true); tp.setColor(Theme.text());
        c.drawText(temp, W - 22, 70, tp);
        tp.setFakeBoldText(false); tp.setTextSize(18); tp.setColor(Theme.sub());
        c.drawText(cond, W - 22, 102, tp);
        c.restore();
        next(66);
    }

    private void cloud(Canvas c, float x, float y, float s, int col) {
        p.setColor(col);
        c.save();
        c.translate(x, y);
        c.scale(s, s);
        r.set(-30, -8, 30, 12); c.drawRoundRect(r, 10, 10, p);
        c.drawCircle(-12, -8, 13, p);
        c.drawCircle(8, -14, 17, p);
        c.restore();
    }
}
