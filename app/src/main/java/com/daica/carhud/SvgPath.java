package com.daica.carhud;

import android.graphics.Path;

/** Đọc chuỗi path SVG đơn giản (M L Q H V Z, toạ độ tuyệt đối) thành Path. */
public final class SvgPath {
    public static Path parse(String d) {
        Path p = new Path();
        String s = d.replaceAll("([MLQHVZmlqhvz])", " $1 ").replace(',', ' ').trim();
        String[] t = s.split("\\s+");
        int i = 0;
        char cmd = 'M';
        float cx = 0, cy = 0;
        while (i < t.length) {
            String tok = t[i];
            if (tok.length() == 1 && Character.isLetter(tok.charAt(0))) {
                cmd = tok.charAt(0);
                i++;
                if (cmd == 'Z' || cmd == 'z') { p.close(); continue; }
            }
            switch (cmd) {
                case 'M': cx = f(t[i++]); cy = f(t[i++]); p.moveTo(cx, cy); cmd = 'L'; break;
                case 'L': cx = f(t[i++]); cy = f(t[i++]); p.lineTo(cx, cy); break;
                case 'H': cx = f(t[i++]); p.lineTo(cx, cy); break;
                case 'V': cy = f(t[i++]); p.lineTo(cx, cy); break;
                case 'Q': {
                    float x1 = f(t[i++]), y1 = f(t[i++]);
                    cx = f(t[i++]); cy = f(t[i++]);
                    p.quadTo(x1, y1, cx, cy);
                    break;
                }
                default: i++;
            }
        }
        return p;
    }

    private static float f(String s) { return Float.parseFloat(s); }

    private SvgPath() {}
}
