package com.zen.bmwdash.core;

/** Reads the lit lamp inside a traffic-light box from its colour and, on upright lights, its position. */
public final class SignalColor {
    public static final int NONE = 0, RED = 1, YELLOW = 2, GREEN = 3;

    public static int classify(int[] argb, int w, int h) {
        if (w <= 0 || h <= 0 || argb.length < w * h) return NONE;
        boolean upright = h > w * 1.6f;
        float[] score = new float[4];
        int[] count = new int[4];
        for (int i = 0; i < w * h; i++) {
            int p = argb[i], r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
            int max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
            // lamps are bright and strongly coloured; housings, sky and signs mostly are not
            if (max < 150 || max - min < max * 0.4f) continue;
            float d = max - min, hue;
            if (max == r) hue = 60 * ((g - b) / d);
            else if (max == g) hue = 60 * ((b - r) / d + 2);
            else hue = 60 * ((r - g) / d + 4);
            if (hue < 0) hue += 360;
            int c;
            if (hue < 20 || hue > 320) c = RED;
            else if (hue < 62) c = YELLOW;
            else if (hue > 80 && hue < 200) c = GREEN; // LED green leans towards cyan
            else continue;
            float weight = (max - 120) / 135f;
            if (upright) {
                float y = (i / w + 0.5f) / h;
                boolean inPlace = c == RED ? y < 0.42f : c == GREEN ? y > 0.58f : y > 0.3f && y < 0.7f;
                weight *= inPlace ? 2f : 0.35f;
            }
            score[c] += weight;
            count[c]++;
        }
        int best = RED;
        if (score[YELLOW] > score[best]) best = YELLOW;
        if (score[GREEN] > score[best]) best = GREEN;
        float others = score[RED] + score[YELLOW] + score[GREEN] - score[best];
        if (count[best] < Math.max(3, w * h / 80) || score[best] < others * 1.5f) return NONE;
        return best;
    }
}
