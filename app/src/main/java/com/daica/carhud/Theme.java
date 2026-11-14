package com.daica.carhud;

import android.graphics.Color;
import java.util.Calendar;

/** Bảng màu ngày/đêm và màu xe. */
public final class Theme {
    public static boolean day = false;

    // màu xe: thân, mặt tối, mặt sáng
    public static int b1 = 0xFF9CCFC2, b2 = 0xFF7DB3A6, b3 = 0xFFBFE3D9;

    public static final String[] CAR_NAMES = {"Xanh ngọc", "Trắng", "Bạc", "Vàng", "Cam", "Đỏ", "Hồng", "Xanh dương", "Đen"};
    public static final int[][] CAR_COLORS = {
            {0xFF9CCFC2, 0xFF7DB3A6, 0xFFBFE3D9},
            {0xFFE3E7EC, 0xFFC3CAD3, 0xFFF4F6F8},
            {0xFFAEB5BF, 0xFF8C949F, 0xFFC8CED6},
            {0xFFEBC860, 0xFFC9A640, 0xFFF6DE92},
            {0xFFE9895A, 0xFFC66C40, 0xFFF4AE88},
            {0xFFC8323A, 0xFF9E2329, 0xFFE0535A},
            {0xFFE7A3B5, 0xFFC98396, 0xFFF3C6D2},
            {0xFF2F5FA8, 0xFF234A85, 0xFF4A79C2},
            {0xFF3A3F47, 0xFF2A2E34, 0xFF565D68}};

    public static void setCar(int idx) {
        if (idx < 0 || idx >= CAR_COLORS.length) idx = 0;
        b1 = CAR_COLORS[idx][0]; b2 = CAR_COLORS[idx][1]; b3 = CAR_COLORS[idx][2];
    }

    public static final int ACCENT = 0xFF2BB3A2;
    public static final int RED = 0xFFFF4D57;
    public static final int AMBER = 0xFFFFB547;

    public static int bg() { return day ? 0xFFE9EDF1 : 0xFF0E1116; }
    public static int panel() { return day ? 0xFFFFFFFF : 0xFF171B22; }
    public static int panel2() { return day ? 0xFFEEF1F5 : 0xFF232A34; }
    public static int text() { return day ? 0xFF1E242D : 0xFFEAEEF3; }
    public static int sub() { return day ? 0xFF5F6670 : 0xFF9AA4B2; }
    public static int card() { return day ? 0xFFFFFFFF : 0xFF232A34; }
    public static int cardLow() { return day ? 0xFFFFE3E5 : 0xFF2A1418; }
    public static int ground() { return day ? 0xFF79B060 : 0xFF132017; }
    public static int verge() { return day ? 0xFF6CA556 : 0xFF16281C; }
    public static int verge2() { return day ? 0xFF8CC46F : 0xFF1C3323; }
    public static int sky() { return day ? 0xFF9ED3F0 : 0xFF10141A; }
    public static int road() { return day ? 0xFF7D848E : 0xFF1E242D; }
    public static int edge() { return day ? 0xFFEEF1F4 : 0xFF3A4250; }
    public static int lane() { return day ? 0xFFFFFFFF : 0xFF59626F; }

    public static int alpha(int color, float a) {
        return Color.argb(Math.round(255 * a), Color.red(color), Color.green(color), Color.blue(color));
    }

    /** Tính giờ mặt trời mọc/lặn (giờ địa phương, dạng số thập phân). Thuật toán NOAA rút gọn. */
    public static double[] sunTimes(double lat, double lon, Calendar c) {
        int n = c.get(Calendar.DAY_OF_YEAR);
        double tz = c.getTimeZone().getOffset(c.getTimeInMillis()) / 3600000.0;
        double g = 2 * Math.PI / 365 * (n - 1);
        double eqt = 229.18 * (0.000075 + 0.001868 * Math.cos(g) - 0.032077 * Math.sin(g) - 0.014615 * Math.cos(2 * g) - 0.040849 * Math.sin(2 * g));
        double decl = 0.006918 - 0.399912 * Math.cos(g) + 0.070257 * Math.sin(g) - 0.006758 * Math.cos(2 * g)
                + 0.000907 * Math.sin(2 * g) - 0.002697 * Math.cos(3 * g) + 0.00148 * Math.sin(3 * g);
        double latR = Math.toRadians(lat);
        double cosH = (Math.cos(Math.toRadians(90.833)) / (Math.cos(latR) * Math.cos(decl))) - Math.tan(latR) * Math.tan(decl);
        cosH = Math.max(-1, Math.min(1, cosH));
        double ha = Math.toDegrees(Math.acos(cosH));
        double rise = (720 - 4 * (lon + ha) - eqt) / 60.0 + tz;
        double set = (720 - 4 * (lon - ha) - eqt) / 60.0 + tz;
        return new double[]{rise, set};
    }

    public static boolean isDayNow(double lat, double lon) {
        Calendar c = Calendar.getInstance();
        double h = c.get(Calendar.HOUR_OF_DAY) + c.get(Calendar.MINUTE) / 60.0;
        if (Double.isNaN(lat)) return h >= 6 && h < 18;
        double[] t = sunTimes(lat, lon, c);
        return h >= t[0] && h < t[1];
    }

    private Theme() {}
}
