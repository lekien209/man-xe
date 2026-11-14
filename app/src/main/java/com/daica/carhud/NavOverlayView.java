package com.daica.carhud;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/** Lớp phủ trên bản đồ: thẻ chỉ đường, thẻ ETA, nút tìm, về vị trí, phóng to/thu nhỏ. Hệ toạ độ rộng 318. */
public class NavOverlayView extends View {
    public interface Actions { void onSearch(); void onCancelRoute(); void onRecenter(); void onZoom(boolean in); }

    private static final float W = 318;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final Path path = new Path();

    public Actions actions;
    public boolean hasRoute = false, following = true;
    public String instr = "", road = "", etaMain = "", etaSub = "", msg = null, attrib = "© OpenStreetMap";
    public NavHelper.Step step;
    private RectF pressed = null; // nút đang được chạm (vẽ sáng lên)

    // vùng nút (toạ độ thiết kế) để bắt chạm
    private final RectF bSearch = new RectF(), bCancel = new RectF(), bCenter = new RectF(), bZin = new RectF(), bZout = new RectF();

    public NavOverlayView(Context c, AttributeSet a) { super(c, a); }

    private float s() { return getWidth() / W; }

    @Override
    protected void onDraw(Canvas c) {
        float s = s(), H = getHeight() / s;
        c.save();
        c.scale(s, s);
        if (!hasRoute) { /* bSearch đặt lại bên dưới */ } else bSearch.setEmpty();
        bCancel.setEmpty(); bCenter.setEmpty();

        if (hasRoute) {
            // thẻ chỉ dẫn
            r.set(10, 10, W - 10, 82);
            card(c, r);
            r.set(18, 18, 74, 74);
            p.setColor(Theme.ACCENT); c.drawRoundRect(r, 12, 12, p);
            arrow(c, 46, 46);
            tp.setColor(Theme.text()); tp.setTextSize(20); tp.setFakeBoldText(true);
            c.drawText(TextUtils.ellipsize(instr, tp, W - 104, TextUtils.TruncateAt.END).toString(), 86, 42, tp);
            tp.setFakeBoldText(false); tp.setTextSize(14); tp.setColor(Theme.sub());
            c.drawText(TextUtils.ellipsize(road, tp, W - 104, TextUtils.TruncateAt.END).toString(), 86, 66, tp);

            // thẻ ETA
            r.set(10, H - 76, W - 10, H - 10);
            card(c, r);
            tp.setColor(Theme.ACCENT); tp.setTextSize(24); tp.setFakeBoldText(true);
            c.drawText(etaMain, 24, H - 46, tp);
            tp.setFakeBoldText(false); tp.setTextSize(14); tp.setColor(Theme.sub());
            c.drawText(etaSub, 24, H - 22, tp);
            bCancel.set(W - 62, H - 66, W - 18, H - 20);
            p.setColor(Theme.panel2()); c.drawCircle(bCancel.centerX(), bCancel.centerY(), 20, p);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2.4f); p.setStrokeCap(Paint.Cap.ROUND); p.setColor(Theme.RED);
            float cx = bCancel.centerX(), cy = bCancel.centerY();
            c.drawLine(cx - 7, cy - 7, cx + 7, cy + 7, p); c.drawLine(cx + 7, cy - 7, cx - 7, cy + 7, p);
            p.setStyle(Paint.Style.FILL);
        } else {
            // nút tìm đường
            r.set(10, 10, W - 10, 58);
            card(c, r);
            bSearch.set(r);
            if (pressed == bSearch) { p.setColor(Theme.alpha(Theme.ACCENT, 0.25f)); c.drawRoundRect(r, 14, 14, p); }
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2.4f); p.setColor(Theme.ACCENT);
            c.drawCircle(34, 32, 8, p); c.drawLine(40, 38, 46, 44, p);
            p.setStyle(Paint.Style.FILL);
            tp.setColor(Theme.sub()); tp.setTextSize(16); tp.setFakeBoldText(false);
            c.drawText("Tìm điểm đến…", 58, 39, tp);
        }

        // nút phóng to / thu nhỏ / về vị trí
        float bx = W - 52, by = hasRoute ? H - 210 : H - 160;
        bZin.set(bx, by, bx + 42, by + 42);
        bZout.set(bx, by + 48, bx + 42, by + 90);
        roundBtn(c, bZin); roundBtn(c, bZout);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2.6f); p.setStrokeCap(Paint.Cap.ROUND); p.setColor(Theme.text());
        c.drawLine(bZin.centerX() - 8, bZin.centerY(), bZin.centerX() + 8, bZin.centerY(), p);
        c.drawLine(bZin.centerX(), bZin.centerY() - 8, bZin.centerX(), bZin.centerY() + 8, p);
        c.drawLine(bZout.centerX() - 8, bZout.centerY(), bZout.centerX() + 8, bZout.centerY(), p);
        p.setStyle(Paint.Style.FILL);
        if (!following) {
            bCenter.set(bx, by + 96, bx + 42, by + 138);
            roundBtn(c, bCenter);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2.2f); p.setColor(Theme.ACCENT);
            c.drawCircle(bCenter.centerX(), bCenter.centerY(), 8, p);
            p.setStyle(Paint.Style.FILL);
            c.drawCircle(bCenter.centerX(), bCenter.centerY(), 3.5f, p);
        }

        tp.setTextSize(9); tp.setFakeBoldText(false); tp.setColor(Theme.alpha(Theme.text(), 0.55f));
        c.drawText(attrib, 12, hasRoute ? H - 82 : H - 8, tp);

        if (msg != null) {
            tp.setTextSize(14);
            float w = Math.min(W - 40, tp.measureText(msg) + 28);
            r.set((W - w) / 2, hasRoute ? 92 : 68, (W + w) / 2, (hasRoute ? 92 : 68) + 34);
            p.setColor(0xE6000000); c.drawRoundRect(r, 17, 17, p);
            tp.setColor(0xFFFFFFFF);
            c.drawText(TextUtils.ellipsize(msg, tp, w - 20, TextUtils.TruncateAt.END).toString(), r.left + 14, r.top + 22, tp);
        }
        c.restore();
    }

    private void card(Canvas c, RectF rr) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(0x33000000);
        r.set(rr.left, rr.top + 2, rr.right, rr.bottom + 2);
        c.drawRoundRect(r, 14, 14, p);
        p.setColor(Theme.alpha(Theme.panel(), 0.96f));
        c.drawRoundRect(rr, 14, 14, p);
    }

    private void roundBtn(Canvas c, RectF b) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(0x33000000); c.drawCircle(b.centerX(), b.centerY() + 2, 21, p);
        p.setColor(Theme.alpha(Theme.panel(), 0.96f)); c.drawCircle(b.centerX(), b.centerY(), 21, p);
        if (pressed != null && pressed.equals(b)) { p.setColor(Theme.alpha(Theme.ACCENT, 0.35f)); c.drawCircle(b.centerX(), b.centerY(), 21, p); }
    }

    /** Mũi tên rẽ trong ô 56x56 tâm (x,y), màu tối trên nền xanh. */
    private void arrow(Canvas c, float x, float y) {
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(5); p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND);
        p.setColor(0xFF0E1116);
        String t = step == null ? "" : step.type, m = step == null ? "" : step.modifier;
        path.reset();
        if ("arrive".equals(t)) {
            p.setStrokeWidth(3);
            c.drawLine(x - 8, y + 14, x - 8, y - 14, p);
            p.setStyle(Paint.Style.FILL);
            path.moveTo(x - 8, y - 14); path.lineTo(x + 12, y - 8); path.lineTo(x - 8, y - 2); path.close();
            c.drawPath(path, p);
            p.setStyle(Paint.Style.FILL);
            return;
        }
        if (t.startsWith("roundabout") || t.startsWith("rotary")) {
            c.drawCircle(x, y - 2, 9, p);
            c.drawLine(x, y + 7, x, y + 16, p);
            c.drawLine(x + 6, y - 9, x + 12, y - 16, p);
            head(c, x + 12, y - 16, -45);
            p.setStyle(Paint.Style.FILL);
            return;
        }
        int dir = 0; // -2 gắt trái .. 2 gắt phải, 9 quay đầu
        if (m.equals("uturn")) dir = 9;
        else if (m.equals("sharp right")) dir = 2;
        else if (m.equals("right")) dir = 2;
        else if (m.equals("slight right")) dir = 1;
        else if (m.equals("sharp left")) dir = -2;
        else if (m.equals("left")) dir = -2;
        else if (m.equals("slight left")) dir = -1;
        if (dir == 0) {
            c.drawLine(x, y + 16, x, y - 14, p);
            head(c, x, y - 14, -90);
        } else if (dir == 9) {
            path.moveTo(x + 8, y + 16); path.lineTo(x + 8, y - 4);
            path.quadTo(x + 8, y - 14, x - 1, y - 14); path.quadTo(x - 10, y - 14, x - 10, y - 4); path.lineTo(x - 10, y + 6);
            c.drawPath(path, p);
            head(c, x - 10, y + 6, 90);
        } else if (Math.abs(dir) == 2) {
            float sg = Math.signum(dir);
            path.moveTo(x - 6 * sg, y + 16); path.lineTo(x - 6 * sg, y - 2); path.quadTo(x - 6 * sg, y - 8, x, y - 8); path.lineTo(x + 12 * sg, y - 8);
            c.drawPath(path, p);
            head(c, x + 12 * sg, y - 8, sg > 0 ? 0 : 180);
        } else {
            float sg = Math.signum(dir);
            path.moveTo(x - 4 * sg, y + 16); path.lineTo(x - 4 * sg, y + 2); path.lineTo(x + 8 * sg, y - 12);
            c.drawPath(path, p);
            head(c, x + 8 * sg, y - 12, sg > 0 ? -50 : -130);
        }
        p.setStyle(Paint.Style.FILL);
    }

    private void head(Canvas c, float x, float y, float deg) {
        c.save();
        c.rotate(deg, x, y);
        p.setStyle(Paint.Style.FILL);
        path.reset();
        path.moveTo(x + 6, y); path.lineTo(x - 5, y - 8); path.lineTo(x - 5, y + 8); path.close();
        c.drawPath(path, p);
        p.setStyle(Paint.Style.STROKE);
        c.restore();
    }

    private RectF downHit = null;

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float s = s();
        float x = e.getX() / s, y = e.getY() / s;
        int act = e.getActionMasked();
        if (act == MotionEvent.ACTION_DOWN) {
            downHit = null;
            // ô tìm kiếm: nới rộng vùng chạm cho màn cảm ứng kém nhạy
            if (!hasRoute && x > 4 && x < W - 4 && y > 0 && y < 70) downHit = bSearch;
            else if (hit(bCancel, x, y)) downHit = bCancel;
            else if (hit(bCenter, x, y)) downHit = bCenter;
            else if (hit(bZin, x, y)) downHit = bZin;
            else if (hit(bZout, x, y)) downHit = bZout;
            if (downHit == null) return false; // để bản đồ nhận kéo/chụm
            pressed = downHit;
            invalidate();
            return true;
        }
        if (downHit == null) return false;
        if (act == MotionEvent.ACTION_UP) {
            RectF h = downHit;
            downHit = null;
            pressed = null;
            invalidate();
            playSoundEffect(android.view.SoundEffectConstants.CLICK);
            if (actions != null) {
                if (h == bSearch) actions.onSearch();
                else if (h == bCancel) actions.onCancelRoute();
                else if (h == bCenter) actions.onRecenter();
                else if (h == bZin) actions.onZoom(true);
                else if (h == bZout) actions.onZoom(false);
            }
        } else if (act == MotionEvent.ACTION_CANCEL) {
            downHit = null;
            pressed = null;
            invalidate();
        }
        return true;
    }

    private static boolean hit(RectF b, float x, float y) {
        if (b.isEmpty()) return false;
        return x > b.left - 6 && x < b.right + 6 && y > b.top - 6 && y < b.bottom + 6;
    }
}
