package com.zen.bmwdash.core;

/** Guesses the lit colour inside a traffic-light box. */
public final class SignalColor {
    public static final int NONE = 0, RED = 1, YELLOW = 2, GREEN = 3;

    public static int classify(int[] argb) {
        int r = 0, y = 0, g = 0;
        for (int p : argb) {
            int cr = (p >> 16) & 255, cg = (p >> 8) & 255, cb = p & 255;
            int max = Math.max(cr, Math.max(cg, cb)), min = Math.min(cr, Math.min(cg, cb));
            if (max < 170 || max - min < max * 0.45) continue;
            float d = max - min, h;
            if (max == cr) h = 60 * ((cg - cb) / d);
            else if (max == cg) h = 60 * ((cb - cr) / d + 2);
            else h = 60 * ((cr - cg) / d + 4);
            if (h < 0) h += 360;
            if (h < 14 || h > 335) r++;
            else if (h > 28 && h < 55) y++;
            else if (h > 90 && h < 195) g++;
        }
        int need = Math.max(3, argb.length / 60);
        if (r >= need && r > 2 * g && r >= y) return RED;
        if (g >= need && g > 2 * r && g >= y) return GREEN;
        if (y >= need && y > r && y > g) return YELLOW;
        return NONE;
    }
}
