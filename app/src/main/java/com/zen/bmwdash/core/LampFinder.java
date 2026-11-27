package com.zen.bmwdash.core;

import java.util.List;

/**
 * Finds a lit red or green traffic lamp straight from the pixels, every frame, without the AI model.
 * The model only sees the overhead part of the road every few looks; this scan costs a fraction of a
 * millisecond, so a change of light is noticed within a few frames. A lamp must be small and round,
 * sit inside a dark housing, stay put for three looks, and not be part of a vehicle.
 */
public final class LampFinder {
    public static final class Lamp {
        public final int color;
        public final float cx, cy;
        public final boolean confirmed;
        Lamp(int color, float cx, float cy, boolean confirmed) { this.color = color; this.cx = cx; this.cy = cy; this.confirmed = confirmed; }
    }

    private int lastColor = SignalColor.NONE, seen, missed;
    private float lastX, lastY;

    /**
     * @param px      the small frame, ARGB
     * @param horizon where the road meets the sky, as a fraction of height; lamps are above it
     * @param center  the middle of our lane across the frame, as a fraction of width
     * @param cars    vehicles and people, as {left, top, right, bottom} fractions: lamps inside them are not signals
     * @return the lamp, or null when none is lit; check {@link Lamp#confirmed} before trusting it
     */
    public Lamp find(int[] px, int w, int h, float horizon, float center, List<float[]> cars) {
        int x0 = Math.max(1, Math.round((center - 0.22f) * w)), x1 = Math.min(w - 2, Math.round((center + 0.22f) * w));
        int y0 = Math.max(1, Math.round(0.03f * h)), y1 = Math.min(h - 2, Math.round((horizon - 0.06f) * h));
        if (x1 <= x0 || y1 <= y0 || px.length < w * h) return remember(null);
        int bw = x1 - x0 + 1, bh = y1 - y0 + 1;
        byte[] mark = new byte[bw * bh];
        for (int y = y0; y <= y1; y++)
            for (int x = x0; x <= x1; x++) mark[(y - y0) * bw + (x - x0)] = (byte) lampColor(px[y * w + x]);

        Lamp best = null;
        float bestScore = 0;
        int[] stack = new int[256];
        for (int i = 0; i < mark.length; i++) {
            int color = mark[i];
            if (color == 0) continue;
            // flood the blob, giving up on anything too big to be a lamp
            int sp = 0, area = 0, minX = bw, maxX = -1, minY = bh, maxY = -1;
            long sx = 0, sy = 0;
            boolean tooBig = false;
            stack[sp++] = i; mark[i] = 0;
            while (sp > 0) {
                int p = stack[--sp];
                int px_ = p % bw, py_ = p / bw;
                area++; sx += px_; sy += py_;
                if (px_ < minX) minX = px_; if (px_ > maxX) maxX = px_;
                if (py_ < minY) minY = py_; if (py_ > maxY) maxY = py_;
                if (area > 400) tooBig = true;
                int[] near = {px_ > 0 ? p - 1 : -1, px_ < bw - 1 ? p + 1 : -1, py_ > 0 ? p - bw : -1, py_ < bh - 1 ? p + bw : -1};
                for (int q : near) {
                    if (q < 0 || mark[q] != color) continue;
                    mark[q] = 0;
                    if (sp < stack.length) stack[sp++] = q;
                    else tooBig = true;
                }
            }
            if (tooBig || area < 2) continue;
            int blobW = maxX - minX + 1, blobH = maxY - minY + 1;
            float round = blobW / (float) blobH, fill = area / (float) (blobW * blobH);
            if (round < 0.6f || round > 1.7f || fill < 0.55f) continue;
            float cx = x0 + sx / (float) area, cy = y0 + sy / (float) area;
            if (insideCar(cars, cx / w, cy / h)) continue;
            if (!darkAround(px, w, h, x0 + minX, y0 + minY, x0 + maxX, y0 + maxY)) continue;
            if (letteredFace(px, w, h, x0 + minX, y0 + minY, x0 + maxX, y0 + maxY)) continue;
            float score = area * (1f - 2.5f * Math.abs(cx / w - center));
            if (score > bestScore) { bestScore = score; best = new Lamp(color, cx / w, cy / h, false); }
        }
        return remember(best);
    }

    /** a lamp counts once it has stayed in place and colour for three looks (five for green) */
    private Lamp remember(Lamp now) {
        if (now == null) {
            if (++missed > 2) { seen = 0; lastColor = SignalColor.NONE; }
            return null;
        }
        missed = 0;
        // a lamp that changes colour moves a little as the other lamp of the housing lights
        boolean same = now.color == lastColor && Math.abs(now.cx - lastX) < 0.03f && Math.abs(now.cy - lastY) < 0.05f;
        seen = same ? seen + 1 : 1;
        lastColor = now.color; lastX = now.cx; lastY = now.cy;
        // wrongly saying green is the dangerous mistake, so green must hold longer than red
        return new Lamp(now.color, now.cx, now.cy, seen >= (now.color == SignalColor.GREEN ? 5 : 3));
    }

    private static boolean insideCar(List<float[]> cars, float x, float y) {
        if (cars == null) return false;
        for (float[] c : cars) if (x >= c[0] - 0.01f && x <= c[2] + 0.01f && y >= c[1] - 0.01f && y <= c[3] + 0.01f) return true;
        return false;
    }

    /** a lit lamp sits in a dark housing; sky, signs and paint do not */
    private static boolean darkAround(int[] px, int w, int h, int l, int t, int r, int b) {
        int pad = Math.max(2, Math.max(r - l, b - t) + 1);
        int dark = 0, total = 0;
        for (int y = t - pad; y <= b + pad; y++)
            for (int x = l - pad; x <= r + pad; x++) {
                if (x >= l && x <= r && y >= t && y <= b) continue;
                if (x < 0 || y < 0 || x >= w || y >= h) continue;
                int p = px[y * w + x];
                int lum = (((p >> 16) & 255) * 77 + ((p >> 8) & 255) * 150 + (p & 255) * 29) >> 8;
                total++;
                if (lum < 100) dark++;
            }
        return total > 0 && dark * 10 >= total * 6;
    }

    /** white lettering or a white border beside the red: that is a stop sign or a road sign, not a lamp */
    private static boolean letteredFace(int[] px, int w, int h, int l, int t, int r, int b) {
        int pad = Math.max(2, (r - l + b - t) / 4);
        int white = 0, total = 0;
        for (int y = t - pad; y <= b + pad; y++)
            for (int x = l - pad; x <= r + pad; x++) {
                if (x >= l && x <= r && y >= t && y <= b) continue;
                if (x < 0 || y < 0 || x >= w || y >= h) continue;
                int p = px[y * w + x];
                int rr = (p >> 16) & 255, gg = (p >> 8) & 255, bb = p & 255;
                int mn = Math.min(rr, Math.min(gg, bb)), mx = Math.max(rr, Math.max(gg, bb));
                total++;
                if (mn >= 130 && mx - mn < mx * 0.4f) white++;
            }
        return total > 0 && white * 10 >= total;
    }

    /** red or green only: amber is too easily a turn signal or a street lamp to trust without the model */
    private static int lampColor(int p) {
        int r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
        int max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        if (max < 170 || max - min < max * 0.45f) return 0;
        float d = max - min, hue;
        if (max == r) hue = 60 * ((g - b) / d);
        else if (max == g) hue = 60 * ((b - r) / d + 2);
        else hue = 60 * ((r - g) / d + 4);
        if (hue < 0) hue += 360;
        if (hue < 18 || hue > 330) return SignalColor.RED;
        if (hue > 85 && hue < 200) return SignalColor.GREEN;
        return 0;
    }
}
