package com.daica.carhud;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.util.AttributeSet;

/**
 * Khung giữa: đường, cây, cỏ, xe con bọ, 4 ô áp suất lốp.
 * Vẽ trong hệ toạ độ thiết kế 320x404, co giãn theo kích thước view. Tối đa ~30 khung hình/giây.
 */
public class CarSceneView extends AnimView {
    public static class Tire {
        public float bar = Float.NaN;
        public int temp = Integer.MIN_VALUE;
        public boolean lowBatt, noSignal, leak;
        public long lastUpdate;
    }

    public final Tire[] tires = {new Tire(), new Tire(), new Tire(), new Tire()}; // TT, TP, ST, SP
    public static final String[] TIRE_NAMES = {"Trước trái", "Trước phải", "Sau trái", "Sau phải"};
    public float threshold = 2.0f;

    private float speedKmh = 0;
    private boolean braking = false;

    private static final float W = 320, H = 404;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();

    private final Path road = new Path(), vergeL = new Path(), vergeR = new Path(), verge2L = new Path(), verge2R = new Path();
    private final Path[] beams = new Path[3];
    private final Path hood, body, bodyShade, roof, finL, finR, finShL, finShR, pineA, pineB, pineC, pineAL, pineBL, pineCL, grass1, grass2, grass3;

    // cây/cỏ vẽ sẵn thành ảnh (tránh GPU vẽ lại Path ở mọi tỉ lệ -> rất giật trên Mali)
    private static final float SK = 3f;
    private static final float[][] SPR_BOX = {{-26, -66, 26, 6}, {-20, -66, 20, 6}, {-12, -16, 12, 2}};
    private final Bitmap[] spr = new Bitmap[3];
    private int sprDay = -1;
    private final Paint bp = new Paint(Paint.FILTER_BITMAP_FLAG);

    private long lastFrame = 0;
    private float worldT = 0; // thời gian "đường chạy", tăng theo tốc độ
    private float bobT = 0;

    public CarSceneView(Context c, AttributeSet a) {
        super(c, a);
        tp.setTypeface(Typeface.DEFAULT);
        road.moveTo(132, 24); road.lineTo(188, 24); road.lineTo(312, 404); road.lineTo(8, 404); road.close();
        vergeL.moveTo(0, 24); vergeL.lineTo(132, 24); vergeL.lineTo(8, 404); vergeL.lineTo(0, 404); vergeL.close();
        vergeR.moveTo(188, 24); vergeR.lineTo(320, 24); vergeR.lineTo(320, 404); vergeR.lineTo(312, 404); vergeR.close();
        verge2L.moveTo(120, 24); verge2L.lineTo(132, 24); verge2L.lineTo(8, 404); verge2L.lineTo(-30, 404); verge2L.close();
        verge2R.moveTo(188, 24); verge2R.lineTo(200, 24); verge2R.lineTo(350, 404); verge2R.lineTo(312, 404); verge2R.close();
        beams[0] = poly(134, 184, 186, 184, 240, 30, 80, 30);
        beams[1] = poly(142, 184, 178, 184, 220, 36, 100, 36);
        beams[2] = poly(148, 184, 172, 184, 200, 46, 120, 46);

        hood = SvgPath.parse("M132 190 Q136 176 160 174 Q184 176 188 190 Q160 186 132 190 Z");
        body = SvgPath.parse("M110 292 Q104 204 160 180 Q216 204 210 292 Z");
        bodyShade = SvgPath.parse("M160 180 Q216 204 210 292 L160 292 Z");
        roof = SvgPath.parse("M126 218 Q130 188 160 182 Q190 188 194 218 Q160 209 126 218 Z");
        finL = SvgPath.parse("M88 300 Q82 262 106 248 Q126 242 134 268 L134 300 Z");
        finR = SvgPath.parse("M232 300 Q238 262 214 248 Q194 242 186 268 L186 300 Z");
        finShL = SvgPath.parse("M88 300 Q86 284 92 274 Q112 282 134 284 L134 300 Z");
        finShR = SvgPath.parse("M232 300 Q234 284 228 274 Q208 282 186 284 L186 300 Z");
        pineA = SvgPath.parse("M-17 -12 Q0 -6 17 -12 L4 -34 Q0 -37 -4 -34 Z");
        pineAL = SvgPath.parse("M-17 -12 Q-8 -9 0 -9 L0 -36 Q-2 -36 -4 -34 Z");
        pineB = SvgPath.parse("M-13 -28 Q0 -23 13 -28 L3 -48 Q0 -51 -3 -48 Z");
        pineBL = SvgPath.parse("M-13 -28 Q-6 -25 0 -25 L0 -50 Q-2 -50 -3 -48 Z");
        pineC = SvgPath.parse("M-9 -43 Q0 -39 9 -43 L2 -60 Q0 -63 -2 -60 Z");
        pineCL = SvgPath.parse("M-9 -43 Q-4 -41 0 -41 L0 -62 Q-1 -62 -2 -60 Z");
        grass1 = SvgPath.parse("M-7 0 Q-8 -6 -10 -9 Q-5 -6 -4 0 Z");
        grass2 = SvgPath.parse("M-4 0 Q-3 -9 -4 -14 Q0 -8 0 0 Z");
        grass3 = SvgPath.parse("M2 0 Q5 -5 9 -7 Q6 -3 6 0 Z");
    }

    private static Path poly(float... xy) {
        Path p = new Path();
        p.moveTo(xy[0], xy[1]);
        for (int i = 2; i < xy.length; i += 2) p.lineTo(xy[i], xy[i + 1]);
        p.close();
        return p;
    }

    public void setSpeed(float kmh, boolean brake) {
        boolean wake = speedKmh <= 1 && kmh > 1;
        speedKmh = kmh;
        braking = brake;
        if (wake) invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        long now = SystemClock.uptimeMillis();
        float dt = lastFrame == 0 ? 0 : Math.min(0.1f, (now - lastFrame) / 1000f);
        lastFrame = now;
        float k = Math.min(2.5f, speedKmh / 50f);
        worldT += dt * k;
        if (speedKmh > 2) bobT += dt;

        float sx = getWidth() / W, sy = getHeight() / H;
        float s = Math.min(sx, sy);
        c.save();
        c.translate((getWidth() - W * s) / 2f, 0);
        c.scale(s, sy);

        drawWorld(c);
        c.save();
        c.translate(0, 14);
        drawCar(c);
        c.restore();
        drawCards(c, now);
        c.restore();

        // xe đứng yên: giảm còn 4 khung/giây cho nhẹ máy
        next(speedKmh > 1 ? 33 : 250);
    }

    // ---------- cảnh nền ----------
    private void drawWorld(Canvas c) {
        boolean d = Theme.day;
        p.setStyle(Paint.Style.FILL);
        p.setColor(Theme.ground()); c.drawRect(0, 0, W, H, p);
        p.setColor(Theme.sky()); c.drawRect(0, 0, W, 24, p);
        if (d) { p.setColor(0xFFFFD84A); c.drawCircle(282, 12, 6, p); p.setColor(0xD9FFFFFF); r.set(54, 7, 86, 17); c.drawOval(r, p); }
        else { p.setColor(0xFFF3E7C4); c.drawCircle(282, 12, 6, p); p.setColor(Theme.sky()); c.drawCircle(285, 10, 5, p); }
        p.setColor(Theme.verge()); c.drawPath(vergeL, p); c.drawPath(vergeR, p);
        p.setColor(Theme.verge2()); c.drawPath(verge2L, p); c.drawPath(verge2R, p);

        buildSprites();
        // cây: 4 mỗi bên, chu kỳ 2.6s; cỏ: 4 mỗi bên, chu kỳ 1.8s
        for (int i = 0; i < 4; i++) {
            drawMover(c, worldT / 2.6f + i * 0.25f, 112, 24, -60, 470, 0.12f, 1.9f, i % 2 == 0 ? 0 : 1);
            drawMover(c, worldT / 2.6f + i * 0.25f + 0.125f, 208, 24, 380, 470, 0.12f, 1.9f, i % 2 == 0 ? 1 : 0);
            drawMover(c, worldT / 1.8f + i * 0.25f, 127, 26, -8, 450, 0.1f, 1.8f, 2);
            drawMover(c, worldT / 1.8f + i * 0.25f + 0.125f, 193, 26, 328, 450, 0.1f, 1.8f, 2);
        }

        p.setColor(Theme.road()); c.drawPath(road, p);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(3); p.setStrokeCap(Paint.Cap.BUTT);
        p.setColor(Theme.edge()); c.drawLine(132, 24, 8, 404, p); c.drawLine(188, 24, 312, 404, p);
        p.setColor(Theme.lane());
        float phase = (worldT * 88f) % 44f;
        dashed(c, 149, 24, 92, 404, phase);
        dashed(c, 171, 24, 228, 404, phase);
        p.setStyle(Paint.Style.FILL);

        if (!d) {
            int[] a = {6, 8, 9};
            for (int i = 0; i < 3; i++) { p.setColor(Theme.alpha(0xFFFFE2A8, a[i] / 255f)); c.drawPath(beams[i], p); }
            p.setColor(0x0FFFE2A8); r.set(120, 138, 200, 162); c.drawOval(r, p);
            p.setColor(0x0DFFE2A8); r.set(102, 102, 218, 134); c.drawOval(r, p);
            p.setColor(0x09FFE2A8); r.set(98, 65, 222, 95); c.drawOval(r, p);
        }
    }

    private void dashed(Canvas c, float x0, float y0, float x1, float y1, float phase) {
        float dx = x1 - x0, dy = y1 - y0, len = (float) Math.hypot(dx, dy);
        for (float o = -44 + phase; o < len; o += 44) {
            float a = Math.max(0, o), b = Math.min(len, o + 22);
            if (b <= a) continue;
            c.drawLine(x0 + dx * a / len, y0 + dy * a / len, x0 + dx * b / len, y0 + dy * b / len, p);
        }
    }

    private void buildSprites() {
        int d = Theme.day ? 1 : 0;
        if (sprDay == d && spr[0] != null) return;
        sprDay = d;
        for (int k = 0; k < 3; k++) {
            float[] b = SPR_BOX[k];
            int w = Math.round((b[2] - b[0]) * SK), h = Math.round((b[3] - b[1]) * SK);
            if (spr[k] == null) spr[k] = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            else spr[k].eraseColor(0);
            Canvas c = new Canvas(spr[k]);
            c.scale(SK, SK);
            c.translate(-b[0], -b[1]);
            if (k == 0) treeRound(c, 255); else if (k == 1) treePine(c, 255); else grassTuft(c, 255);
        }
    }

    private void drawMover(Canvas c, float t, float x0, float y0, float x1, float y1, float s0, float s1, int kind) {
        t = t - (float) Math.floor(t);
        float e = (float) Math.pow(t, 1.8);
        float x = x0 + (x1 - x0) * e, y = y0 + (y1 - y0) * e, s = s0 + (s1 - s0) * e;
        float[] b = SPR_BOX[kind];
        bp.setAlpha(t < 0.15f ? Math.round(255 * t / 0.15f) : 255);
        r.set(x + b[0] * s, y + b[1] * s, x + b[2] * s, y + b[3] * s);
        c.drawBitmap(spr[kind], null, r, bp);
    }

    private int tc(int night, int day, int alpha) {
        int col = Theme.day ? day : night;
        return (col & 0x00FFFFFF) | (alpha << 24);
    }

    private void treeRound(Canvas c, int a) {
        p.setColor((Math.round(0.3f * a) << 24)); r.set(-16, -4, 16, 4); c.drawOval(r, p);
        p.setColor(tc(0xFF5A3E2B, 0xFF8A6446, a)); r.set(-3, -20, 3, 0); c.drawRoundRect(r, 2, 2, p);
        p.setColor(tc(0xFF1F4A32, 0xFF3C8C5A, a)); c.drawCircle(0, -38, 18, p);
        p.setColor(tc(0xFF25583B, 0xFF46A066, a)); c.drawCircle(-11, -30, 12, p);
        p.setColor(tc(0xFF1B4029, 0xFF347A4E, a)); c.drawCircle(11, -29, 11, p);
        p.setColor(tc(0xFF2E6B45, 0xFF52B575, a)); c.drawCircle(-4, -46, 12, p);
        p.setColor(tc(0xFF296240, 0xFF4AA86C, a)); c.drawCircle(6, -42, 10, p);
        p.setColor(tc(0xFF3F8A57, 0xFF6CCB8A, a)); c.drawCircle(-9, -49, 6, p);
    }

    private void treePine(Canvas c, int a) {
        p.setColor((Math.round(0.3f * a) << 24)); r.set(-12, -3.5f, 12, 3.5f); c.drawOval(r, p);
        p.setColor(tc(0xFF5A3E2B, 0xFF8A6446, a)); r.set(-2.5f, -12, 2.5f, 0); c.drawRoundRect(r, 1.5f, 1.5f, p);
        p.setColor(tc(0xFF1A4A33, 0xFF33865A, a)); c.drawPath(pineA, p);
        p.setColor(tc(0xFF24603F, 0xFF44A06C, a)); c.drawPath(pineAL, p);
        p.setColor(tc(0xFF1F5539, 0xFF3A9264, a)); c.drawPath(pineB, p);
        p.setColor(tc(0xFF2C7049, 0xFF50B57A, a)); c.drawPath(pineBL, p);
        p.setColor(tc(0xFF246042, 0xFF42A06C, a)); c.drawPath(pineC, p);
        p.setColor(tc(0xFF378553, 0xFF62C48A, a)); c.drawPath(pineCL, p);
    }

    private void grassTuft(Canvas c, int a) {
        p.setColor(tc(0xFF2E6B45, 0xFF4E9E5A, a)); c.drawPath(grass1, p);
        p.setColor(tc(0xFF3A7F50, 0xFF5DB06A, a)); c.drawPath(grass2, p);
        p.setColor(tc(0xFF3F8A57, 0xFF68BB72, a)); c.drawPath(grass3, p);
    }

    // ---------- xe ----------
    private void drawCar(Canvas c) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(0x33000000); r.set(68, 294, 252, 330); c.drawOval(r, p);
        p.setColor(0x59000000); r.set(84, 299, 236, 321); c.drawOval(r, p);
        if (braking) {
            p.setColor(0x24FF3340); r.set(76, 319, 144, 337); c.drawOval(r, p); r.set(176, 319, 244, 337); c.drawOval(r, p);
        }
        // phát sáng đỏ quanh bánh khi lốp non
        int[][] wg = {{124, 220, 14, 12}, {196, 220, 14, 12}, {105, 296, 22, 20}, {215, 296, 22, 20}};
        boolean blink = (SystemClock.uptimeMillis() / 500) % 2 == 0;
        for (int i = 0; i < 4; i++) {
            if (isLow(i)) {
                p.setColor(blink ? 0x59FF4D57 : 0x26FF4D57);
                r.set(wg[i][0] - wg[i][2], wg[i][1] - wg[i][3], wg[i][0] + wg[i][2], wg[i][1] + wg[i][3]);
                c.drawOval(r, p);
            }
        }

        float bob = speedKmh > 2 ? (float) (Math.sin(bobT * 2 * Math.PI / 0.9) * 0.7 + 0.7) : 0;
        c.save();
        c.translate(0, bob);

        tire(c, 94, 274, 22, 34, 10, 99);
        tire(c, 204, 274, 22, 34, 10, 209);
        tire(c, 117, 212, 12, 20, 5, 117);
        tire(c, 191, 212, 12, 20, 5, 191);

        p.setStyle(Paint.Style.FILL);
        p.setColor(Theme.b1); c.drawPath(hood, p);
        p.setColor(Theme.b2); c.drawCircle(125, 212, 11, p); c.drawCircle(195, 212, 11, p);
        p.setColor(Theme.b1); c.drawPath(body, p);
        p.setColor(Theme.alpha(Theme.b2, 0.55f)); c.drawPath(bodyShade, p);
        p.setColor(Theme.b3); c.drawPath(roof, p);
        c.save(); c.rotate(-20, 146, 194); p.setColor(0x73FFFFFF); r.set(128, 188, 164, 200); c.drawOval(r, p); c.restore();
        p.setColor(0xFF11151B); r.set(135, 205, 185, 231); c.drawOval(r, p);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1.5f); p.setColor(0xB3C9D1DB); c.drawOval(r, p);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0x4D000000);
        for (int i = 0; i < 3; i++) { r.set(148, 245 + i * 6, 172, 247.5f + i * 6); c.drawRoundRect(r, 1.2f, 1.2f, p); }
        p.setColor(0xFFEAEEF3); r.set(146, 268, 174, 279); c.drawRoundRect(r, 3, 3, p);
        p.setColor(Theme.b1); c.drawPath(finL, p); c.drawPath(finR, p);
        p.setColor(0x24000000); c.drawPath(finShL, p);
        p.setColor(0x33000000); c.drawPath(finShR, p);
        // đèn hậu
        p.setColor(braking ? 0xFFFF3340 : 0xFFB81E28);
        r.set(99, 254, 111, 274); c.drawOval(r, p); r.set(209, 254, 221, 274); c.drawOval(r, p);
        if (braking) {
            p.setColor(0x40FF3340); r.set(90, 244, 120, 284); c.drawOval(r, p); r.set(200, 244, 230, 284); c.drawOval(r, p);
        }
        // cản crôm
        p.setColor(0xFFC9D1DB); r.set(90, 288, 230, 296); c.drawRoundRect(r, 4, 4, p);
        p.setColor(0xB3FFFFFF); r.set(92, 289, 228, 291); c.drawRoundRect(r, 1, 1, p);
        p.setColor(0xFFAEB5BF); r.set(124, 283, 130, 299); c.drawRoundRect(r, 3, 3, p); r.set(190, 283, 196, 299); c.drawRoundRect(r, 3, 3, p);
        p.setColor(Theme.b2); c.drawCircle(104, 198, 5, p); c.drawCircle(216, 198, 5, p);
        c.restore();
    }

    private void tire(Canvas c, float x, float y, float w, float h, float rad, float tx) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFF15181D);
        r.set(x, y, x + w, y + h);
        c.drawRoundRect(r, rad, rad, p);
        c.save();
        c.clipRect(x + 1, y + 2, x + w - 1, y + h - 2);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1.6f); p.setStrokeCap(Paint.Cap.ROUND);
        p.setColor(0xFF4A5260);
        float off = (worldT * 43.75f) % 7f;
        float cxm = x + w / 2f, half = Math.min(6, w / 2f - 1);
        for (float yy = y - 7 + off; yy < y + h + 7; yy += 7) {
            c.drawLine(cxm - half, yy, cxm, yy - 3, p);
            c.drawLine(cxm, yy - 3, cxm + half, yy, p);
        }
        c.restore();
        p.setStyle(Paint.Style.FILL);
    }

    // ---------- ô áp suất ----------
    private boolean isLow(int i) {
        Tire t = tires[i];
        return (!Float.isNaN(t.bar) && t.bar < threshold) || t.leak;
    }

    private void drawCards(Canvas c, long now) {
        float[][] pos = {{6, 140}, {230, 140}, {6, 326}, {230, 326}};
        boolean blink = (now / 500) % 2 == 0;
        String banner = null;
        for (int i = 0; i < 4; i++) {
            Tire t = tires[i];
            boolean low = isLow(i);
            if (low && banner == null) banner = TIRE_NAMES[i] + (t.leak ? " xì hơi" : " non hơi");
            float x = pos[i][0], y = pos[i][1];
            r.set(x, y, x + 84, y + 62);
            p.setStyle(Paint.Style.FILL);
            p.setColor(low ? Theme.cardLow() : Theme.card());
            c.drawRoundRect(r, 11, 11, p);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1.4f);
            p.setColor(low ? (blink ? Theme.RED : Theme.alpha(Theme.RED, 0.3f)) : Theme.ACCENT);
            c.drawRoundRect(r, 11, 11, p);
            p.setStyle(Paint.Style.FILL);

            // pin
            int bc = t.lowBatt ? Theme.RED : Theme.ACCENT;
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1.3f); p.setColor(bc);
            r.set(x + 60, y + 7, x + 76, y + 16); c.drawRoundRect(r, 2, 2, p);
            p.setStyle(Paint.Style.FILL);
            r.set(x + 76.5f, y + 9.5f, x + 78.5f, y + 13.5f); c.drawRect(r, p);
            r.set(x + 62, y + 9, x + 62 + (t.lowBatt ? 3.5f : 12), y + 14); c.drawRect(r, p);

            // áp suất
            tp.setTextSize(21); tp.setFakeBoldText(true);
            tp.setColor(low ? Theme.RED : Theme.text());
            String v = Float.isNaN(t.bar) ? "--" : String.format(java.util.Locale.US, "%.2f", t.bar).replace('.', ',');
            c.drawText(v, x + 8, y + 31, tp);
            float vw = tp.measureText(v);
            tp.setFakeBoldText(false); tp.setTextSize(14); tp.setColor(Theme.sub());
            c.drawText("bar", x + 10 + vw, y + 31, tp);

            // nhiệt kế
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1.2f); p.setColor(Theme.sub());
            r.set(x + 10, y + 38, x + 14.5f, y + 50); c.drawRoundRect(r, 2.2f, 2.2f, p);
            p.setStyle(Paint.Style.FILL); p.setColor(0xFFFF7A6B);
            c.drawCircle(x + 12.2f, y + 51.5f, 3.6f, p);
            r.set(x + 11.2f, y + 43, x + 13.2f, y + 51); c.drawRect(r, p);
            tp.setTextSize(17); tp.setColor(Theme.sub());
            String tt = t.temp == Integer.MIN_VALUE ? "--°C" : (t.temp + "°C");
            if (t.noSignal) tt = "mất sóng";
            c.drawText(tt, x + 22, y + 54, tp);
        }
        if (banner != null) {
            r.set(30, 30, 290, 64);
            p.setColor(0xFF3A1216); c.drawRoundRect(r, 17, 17, p);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1.5f); p.setColor(Theme.RED); c.drawRoundRect(r, 17, 17, p);
            p.setStyle(Paint.Style.FILL); c.drawCircle(50, 47, 9, p);
            tp.setColor(0xFF3A1216); tp.setTextSize(14); tp.setFakeBoldText(true); tp.setTextAlign(Paint.Align.CENTER);
            c.drawText("!", 50, 52, tp); tp.setTextAlign(Paint.Align.LEFT);
            tp.setColor(0xFFFFD3D6); tp.setTextSize(17);
            c.drawText(banner, 66, 53, tp);
            tp.setFakeBoldText(false);
        }
    }

    public boolean anyLow() {
        for (int i = 0; i < 4; i++) if (isLow(i)) return true;
        return false;
    }
}
