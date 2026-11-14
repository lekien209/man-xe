package com.daica.carhud;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;

/** Bố cục cố định theo thiết kế 1024x600, tự co giãn theo màn thực tế. Vẽ nền các khung. */
public class DashLayout extends ViewGroup {
    public static final float DW = 1024, DH = 600;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final float[][] panels;

    public DashLayout(Context c, float[]... panels) {
        super(c);
        this.panels = panels;
        setWillNotDraw(false);
    }

    /** Thêm view vào khung (toạ độ thiết kế), bo góc nếu radius > 0. */
    public void add(View v, float l, float t, float rr, float b, final float radius) {
        v.setTag(R_TAG, new float[]{l, t, rr, b});
        if (radius > 0) {
            v.setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View view, Outline o) {
                    float s = view.getWidth() / Math.max(1f, ((float[]) view.getTag(R_TAG))[2] - ((float[]) view.getTag(R_TAG))[0]);
                    o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius * s);
                }
            });
            v.setClipToOutline(true);
        }
        addView(v);
    }

    static final int R_TAG = R.id.design_rect;

    @Override
    protected void onMeasure(int ws, int hs) {
        int w = MeasureSpec.getSize(ws), h = MeasureSpec.getSize(hs);
        setMeasuredDimension(w, h);
        float sx = w / DW, sy = h / DH;
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            float[] d = (float[]) v.getTag(R_TAG);
            if (d == null) continue;
            int cw = Math.round(d[2] * sx) - Math.round(d[0] * sx);
            int ch = Math.round(d[3] * sy) - Math.round(d[1] * sy);
            v.measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY));
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int rr, int b) {
        float sx = (rr - l) / DW, sy = (b - t) / DH;
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            float[] d = (float[]) v.getTag(R_TAG);
            if (d == null) continue;
            v.layout(Math.round(d[0] * sx), Math.round(d[1] * sy), Math.round(d[2] * sx), Math.round(d[3] * sy));
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        c.drawColor(Theme.bg());
        float sx = getWidth() / DW, sy = getHeight() / DH;
        p.setColor(Theme.panel());
        for (float[] pn : panels) {
            r.set(pn[0] * sx, pn[1] * sy, pn[2] * sx, pn[3] * sy);
            c.drawRoundRect(r, 16 * sx, 16 * sx, p);
        }
    }
}
