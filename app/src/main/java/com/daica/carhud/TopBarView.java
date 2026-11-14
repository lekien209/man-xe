package com.daica.carhud;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;

import java.util.Calendar;

/** Thanh trên cùng (cao 36, rộng 1024 theo thiết kế): ngày, các chip trạng thái, nút ứng dụng và cài đặt. */
public class TopBarView extends AnimView {
    public interface Actions { void onSettings(); void onApps(); }

    private static final float W = 1024;
    private static final String[] DOW = {"", "Chủ nhật", "Thứ Hai", "Thứ Ba", "Thứ Tư", "Thứ Năm", "Thứ Sáu", "Thứ Bảy"};
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final RectF bGear = new RectF(), bApps = new RectF();

    public Actions actions;
    public boolean bt, gps, net, tpms;
    public String netName = "4G";

    public TopBarView(Context c, AttributeSet a) { super(c, a); }

    @Override
    protected void onDraw(Canvas c) {
        float s = getWidth() / W;
        float H = getHeight() / s;
        c.save();
        c.scale(s, s);
        Calendar cal = Calendar.getInstance();
        tp.setTextSize(15); tp.setColor(Theme.sub()); tp.setTextAlign(Paint.Align.LEFT);
        String date = DOW[cal.get(Calendar.DAY_OF_WEEK)] + ", " + cal.get(Calendar.DAY_OF_MONTH) + "/" + (cal.get(Calendar.MONTH) + 1) + "/" + cal.get(Calendar.YEAR);
        float cy = H / 2;
        c.drawText(date, 16, cy + 5, tp);

        // nút từ phải sang trái
        float x = W - 14;
        bGear.set(x - 30, cy - 15, x, cy + 15);
        gear(c, bGear.centerX(), cy);
        x -= 40;
        bApps.set(x - 30, cy - 15, x, cy + 15);
        apps(c, bApps.centerX(), cy);
        x -= 44;
        x = chip(c, x, cy, "Lốp", tpms);
        x = chip(c, x, cy, net ? netName : "Mất mạng", net);
        x = chip(c, x, cy, "GPS", gps);
        chip(c, x, cy, bt ? "iPhone đã nối" : "Bluetooth", bt);
        c.restore();
        // cập nhật ngày mỗi phút
        next(60000 - System.currentTimeMillis() % 60000 + 50);
    }

    private float chip(Canvas c, float right, float cy, String label, boolean on) {
        tp.setTextSize(13);
        float w = tp.measureText(label) + 30;
        r.set(right - w, cy - 12, right, cy + 12);
        p.setStyle(Paint.Style.FILL);
        p.setColor(Theme.panel());
        c.drawRoundRect(r, 12, 12, p);
        p.setColor(on ? Theme.ACCENT : Theme.sub());
        c.drawCircle(r.left + 12, cy, 4, p);
        tp.setColor(on ? Theme.text() : Theme.sub());
        c.drawText(label, r.left + 22, cy + 4.5f, tp);
        return right - w - 8;
    }

    private void gear(Canvas c, float x, float y) {
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(3.2f); p.setStrokeCap(Paint.Cap.ROUND);
        p.setColor(Theme.sub());
        for (int i = 0; i < 8; i++) {
            double a = Math.PI * i / 4;
            c.drawLine(x + (float) Math.cos(a) * 7, y + (float) Math.sin(a) * 7, x + (float) Math.cos(a) * 10, y + (float) Math.sin(a) * 10, p);
        }
        p.setStrokeWidth(2.2f);
        c.drawCircle(x, y, 6.5f, p);
        c.drawCircle(x, y, 2.5f, p);
        p.setStyle(Paint.Style.FILL);
    }

    private void apps(Canvas c, float x, float y) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(Theme.sub());
        for (int i = -1; i <= 1; i++) for (int j = -1; j <= 1; j++) c.drawCircle(x + i * 7, y + j * 7, 2.4f, p);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float s = getWidth() / W;
        float x = e.getX() / s, y = e.getY() / s;
        if (e.getActionMasked() == MotionEvent.ACTION_UP && actions != null) {
            if (x > bGear.left - 10) actions.onSettings();
            else if (x > bApps.left - 10 && x < bApps.right + 5) actions.onApps();
        }
        return x > bApps.left - 10;
    }
}
