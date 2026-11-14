package com.daica.carhud;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;

/** Đồng hồ tốc độ vòng cung + khối "còn bao nhiêu km tới điểm đến". Hệ toạ độ 340x110. */
public class GaugeView extends AnimView {
    private static final float W = 340, H = 110, CX = 170, CY = 98, R = 80, MAX = 120;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arc = new RectF(CX - R, CY - R, CX + R, CY + R);
    private final RectF r = new RectF();
    private final Path flag = new Path();

    private float target = 0, shown = 0;
    // điểm đến
    private boolean hasDest = false;
    private String destKm = "", destEta = "";
    private float destProgress = 0;

    public GaugeView(Context c, AttributeSet a) {
        super(c, a);
        flag.moveTo(-4, 7); flag.lineTo(-4, -8); flag.lineTo(6, -5); flag.lineTo(-2, -1); flag.lineTo(-2, 7); flag.close();
    }

    public void setSpeed(float kmh) {
        target = Math.max(0, kmh);
        invalidate();
    }

    public void setDest(boolean has, String km, String eta, float progress) {
        hasDest = has; destKm = km; destEta = eta; destProgress = progress;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float s = getWidth() / W;
        c.save();
        c.scale(s, getHeight() / H);

        // ---- cung ----
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(8);
        p.setColor(Theme.panel2());
        c.drawArc(arc, 180, 180, false, p);
        float frac = Math.min(1, shown / MAX);
        if (frac > 0.002f) {
            p.setColor(Theme.ACCENT);
            c.drawArc(arc, 180, 180 * frac, false, p);
        }
        // vạch
        p.setStrokeCap(Paint.Cap.BUTT);
        for (int v = 0; v <= MAX; v += 10) {
            double ang = Math.PI * (1 - v / MAX);
            boolean major = v % 40 == 0;
            float r0 = R - 10, r1 = R - (major ? 20 : 15);
            p.setStrokeWidth(major ? 2.2f : 1.2f);
            p.setColor(major ? Theme.text() : Theme.sub());
            float cs = (float) Math.cos(ang), sn = (float) Math.sin(ang);
            c.drawLine(CX + cs * r0, CY - sn * r0, CX + cs * r1, CY - sn * r1, p);
            if (major) {
                tp.setTextSize(11); tp.setColor(Theme.sub()); tp.setTextAlign(Paint.Align.CENTER);
                float rl = R - 30;
                c.drawText(String.valueOf(v), CX + cs * rl, CY - sn * rl + 4, tp);
            }
        }
        // kim
        double ang = Math.PI * (1 - frac);
        float cs = (float) Math.cos(ang), sn = (float) Math.sin(ang);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(3);
        p.setColor(0xFFFF6B5A);
        // kim chỉ vẽ phần ngoài để không che số
        c.drawLine(CX + cs * 40, CY - sn * 40, CX + cs * (R - 6), CY - sn * (R - 6), p);
        p.setStyle(Paint.Style.FILL);

        // số
        tp.setTextAlign(Paint.Align.CENTER);
        tp.setTextSize(30); tp.setFakeBoldText(true); tp.setColor(Theme.text());
        String sp = String.valueOf(Math.round(shown));
        c.drawText(sp, CX, CY - 4, tp);
        tp.setFakeBoldText(false);
        tp.setTextAlign(Paint.Align.LEFT);
        tp.setTextSize(13); tp.setColor(Theme.sub());
        c.drawText("km/h", CX + R + 14, CY - 2, tp);

        // ---- điểm đến ----
        if (hasDest) {
            p.setColor(Theme.ACCENT);
            c.drawCircle(26, 40, 15, p);
            c.save(); c.translate(26, 40); p.setColor(0xFF0E1116); c.drawPath(flag, p); c.restore();
            tp.setTextSize(20); tp.setFakeBoldText(true); tp.setColor(Theme.text());
            c.drawText(destKm, 8, 82, tp);
            float w = tp.measureText(destKm);
            tp.setFakeBoldText(false); tp.setTextSize(12); tp.setColor(Theme.sub());
            c.drawText("km", 11 + w, 82, tp);
            c.drawText(destEta, 46, 44, tp);
            r.set(8, 92, 84, 96);
            p.setColor(Theme.panel2()); c.drawRoundRect(r, 2, 2, p);
            r.set(8, 92, 8 + 76 * Math.max(0, Math.min(1, destProgress)), 96);
            p.setColor(Theme.ACCENT); c.drawRoundRect(r, 2, 2, p);
        }
        c.restore();

        // kim chạy mượt tới giá trị mới
        float d = target - shown;
        if (Math.abs(d) > 0.2f) {
            shown += d * 0.18f;
            next(33);
        } else shown = target;
    }
}
