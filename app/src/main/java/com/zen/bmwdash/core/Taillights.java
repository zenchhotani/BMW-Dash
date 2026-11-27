package com.zen.bmwdash.core;

import java.util.ArrayList;
import java.util.List;

/**
 * At night a car ahead is often just two red lights, which the object model misses.
 * This finds pairs of red lamps side by side, level with each other and at a sensible height
 * for their spacing, and reports each pair as a car box.
 */
public final class Taillights {
    private static final class Blob { int n, x0 = 1 << 30, x1 = -1, y0 = 1 << 30, y1 = -1; float cx, cy; boolean used; }

    /**
     * @param px     w*h ARGB pixels of a small copy of the frame
     * @param aspect frame height / width
     */
    public static List<Detection> find(int[] px, int w, int h, float horizon, float hood, float aspect) {
        List<Detection> out = new ArrayList<>();
        int y0 = Math.max(0, Math.round((horizon - 0.06f) * h)), y1 = Math.min(h - 1, Math.round(Math.min(hood, horizon + 0.32f) * h));
        if (y1 - y0 < 4) return out;
        boolean[] red = new boolean[w * h];
        for (int y = y0; y <= y1; y++)
            for (int x = 0; x < w; x++) {
                int c = px[y * w + x], r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
                red[y * w + x] = r > 110 && g * 100 < r * 58 && b * 100 < r * 85;
            }
        // a red wash from a traffic light or brake glare on a wet screen is not a pair of lamps
        int count = 0;
        for (int i = y0 * w; i <= y1 * w + w - 1 && i < red.length; i++) if (red[i]) count++;
        if (count > 0.025f * w * (y1 - y0 + 1)) return out;
        List<Blob> blobs = new ArrayList<>();
        int[] stack = new int[w * h];
        boolean[] seen = new boolean[w * h];
        for (int y = y0; y <= y1; y++)
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                if (!red[i] || seen[i]) continue;
                Blob b = new Blob(); int sp = 0; stack[sp++] = i; seen[i] = true; float sx = 0, sy = 0;
                while (sp > 0) {
                    int j = stack[--sp], jx = j % w, jy = j / w;
                    b.n++; sx += jx; sy += jy;
                    b.x0 = Math.min(b.x0, jx); b.x1 = Math.max(b.x1, jx); b.y0 = Math.min(b.y0, jy); b.y1 = Math.max(b.y1, jy);
                    if (b.n > 600) continue;
                    int[] nb = {j - 1, j + 1, j - w, j + w};
                    for (int k = 0; k < 4; k++) {
                        int q = nb[k];
                        if (q < 0 || q >= w * h || seen[q] || !red[q]) continue;
                        if ((k < 2) && q / w != jy) continue;
                        seen[q] = true; stack[sp++] = q;
                    }
                }
                int bw = b.x1 - b.x0 + 1, bh = b.y1 - b.y0 + 1;
                // a lamp is small and roughly as wide as tall, never a long streak
                if (b.n < 2 || b.n > 400 || bw > 4 * bh + 2 || bh > 3 * bw + 2) continue;
                b.cx = sx / b.n; b.cy = sy / b.n; blobs.add(b);
            }
        if (blobs.size() < 2 || blobs.size() > 60) return out;
        while (true) {
            Blob ba = null, bb = null; float best = Float.MAX_VALUE;
            for (int i = 0; i < blobs.size(); i++) for (int j = i + 1; j < blobs.size(); j++) {
                Blob a = blobs.get(i), b = blobs.get(j);
                if (a.used || b.used) continue;
                float size = (float) Math.sqrt((a.n + b.n) / 2f), sep = Math.abs(a.cx - b.cx);
                float ratio = Math.max(a.n, b.n) / (float) Math.min(a.n, b.n);
                if (ratio > 3.5f || Math.abs(a.cy - b.cy) > Math.max(1.5f, 0.5f * size) || sep < 3.2f * size || sep > 14 * size || sep < 4
                        || sep > 0.2f * w) continue;
                // two separate lamps of a sensible size for their spacing, with no red between them
                float wa = a.x1 - a.x0 + 1, wb = b.x1 - b.x0 + 1;
                if (Math.max(wa, wb) > 0.45f * sep || Math.min(wa, wb) < 0.05f * sep) continue;
                int mx = Math.round((a.cx + b.cx) / 2), my = Math.round((a.cy + b.cy) / 2);
                if (red[my * w + mx]) continue;
                // the lamps sit a little below the horizon, by about a third of their spacing
                float below = ((a.cy + b.cy) / 2f / h - horizon) * aspect / (sep / w);
                if (below < -0.12f || below > 0.9f) continue;
                float cost = Math.abs(a.cy - b.cy) / size + ratio * 0.3f + Math.abs(below - 0.3f);
                if (cost < best) { best = cost; ba = a; bb = b; }
            }
            if (ba == null) break;
            ba.used = bb.used = true;
            Blob l = ba.cx < bb.cx ? ba : bb, r = l == ba ? bb : ba;
            float sep = (r.cx - l.cx) / w, cy = (l.cy + r.cy) / 2f / h;
            float left = (l.cx / w) - 0.16f * sep, right = (r.cx / w) + 0.16f * sep;
            float bottom = cy + 0.57f * sep / aspect, top = cy - 0.5f * sep / aspect;
            if (left < 0 || right > 1 || top < 0 || bottom > 1) continue;
            Detection d = new Detection("car", 0.5f, left, top, right, bottom);
            d.lamps = true;
            out.add(d);
        }
        return out;
    }
}
