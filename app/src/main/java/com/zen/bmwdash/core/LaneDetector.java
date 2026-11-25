package com.zen.bmwdash.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Lightweight lane-marking finder: bright/yellow ridge points below the horizon,
 * a Hough vote over (x at bottom, x at horizon), then least-squares refinement.
 */
public final class LaneDetector {
    public static final class Line {
        /** x = a + b*y, in normalised frame coordinates. */
        public final float a, b, support, yellowFraction;
        Line(float a, float b, float support, float yellow) {
            this.a = a; this.b = b; this.support = support; this.yellowFraction = yellow;
        }
        public float x(float y) { return a + b * y; }
    }
    public static final class Result {
        public Line left, right;
    }

    private static final int XV = 17, XB = 120;
    private static final float XB_MIN = -0.7f, XB_MAX = 1.7f;

    /**
     * @param gray    w*h luminance 0..255
     * @param yellow  w*h, true where the pixel looks like yellow paint
     * @param horizon horizon row, 0..1
     * @param bottom  lowest usable row (above the hood), 0..1
     * @param center  x of the car's own axis in the frame, 0..1
     */
    public Result detect(int[] gray, boolean[] yellow, int w, int h, float horizon, float bottom, float center) {
        int hy = Math.round(horizon * h);
        int y0 = Math.min(h - 2, hy + Math.max(4, Math.round(0.07f * h)));
        int y1 = Math.min(h - 1, Math.round(bottom * h));
        Result res = new Result();
        if (y1 - y0 < 10) return res;

        List<int[]> pts = new ArrayList<>();
        int rows = 0;
        for (int y = y0; y <= y1; y += 2) {
            rows++;
            int mw = Math.max(3, Math.round(0.00032f * w * (y - hy) + 2));
            int base = y * w;
            int bestX = -1, bestR = 0;
            for (int x = mw; x < w - mw; x++) {
                int v = value(gray, yellow, base + x);
                int dl = v - value(gray, yellow, base + x - mw);
                int dr = v - value(gray, yellow, base + x + mw);
                int r = (dl > 22 && dr > 22) ? dl + dr - Math.abs(dl - dr) : 0;
                if (r > 0) {
                    if (r > bestR) { bestR = r; bestX = x; }
                } else if (bestX >= 0) {
                    pts.add(new int[]{bestX, y, yellow[base + bestX] ? 1 : 0});
                    bestX = -1; bestR = 0;
                }
            }
            if (bestX >= 0) pts.add(new int[]{bestX, y, yellow[base + bestX] ? 1 : 0});
        }
        if (pts.size() < 6) return res;

        float yb = (float) y1 / h;
        int[][] acc = new int[XV][XB];
        int[][] lastRow = new int[XV][XB];
        for (int[] p : pts) {
            float x = (float) p[0] / w, y = (float) p[1] / h;
            float depth = y - horizon;
            for (int i = 0; i < XV; i++) {
                float xv = center - 0.16f + 0.02f * i;
                float xb = xv + (x - xv) * (yb - horizon) / depth;
                int j = (int) ((xb - XB_MIN) / (XB_MAX - XB_MIN) * XB);
                for (int k = j - 1; k <= j + 1; k++) {
                    if (k < 0 || k >= XB || lastRow[i][k] == p[1] + 1) continue;
                    // the exact bin counts double so peaks stay sharp
                    acc[i][k] += (k == j) ? 2 : 1;
                    lastRow[i][k] = p[1] + 1;
                }
            }
        }

        int need = Math.max(8, Math.round(rows * 0.22f) * 2);
        res.left = pick(acc, pts, w, h, horizon, yb, center, rows, need, true);
        res.right = pick(acc, pts, w, h, horizon, yb, center, rows, need, false);
        if (res.left != null && res.right != null) {
            float width = res.right.x(yb) - res.left.x(yb);
            if (width < 0.28f || width > 1.6f) {
                if (res.left.support >= res.right.support) res.right = null; else res.left = null;
            }
        }
        return res;
    }

    private static int value(int[] gray, boolean[] yellow, int i) {
        return yellow[i] ? Math.min(255, gray[i] + 45) : gray[i];
    }

    private Line pick(int[][] acc, List<int[]> pts, int w, int h, float horizon, float yb,
                      float center, int rows, int need, boolean left) {
        // best vanishing-x per bottom position
        int[] best = new int[XB], bestV = new int[XB];
        for (int j = 0; j < XB; j++) {
            for (int i = 0; i < XV; i++) {
                // mild preference for a vanishing point near the middle
                if (acc[i][j] * 100 - Math.abs(i - XV / 2) > best[j] * 100 - Math.abs(bestV[j] - XV / 2)) {
                    best[j] = acc[i][j]; bestV[j] = i;
                }
            }
        }
        float binW = (XB_MAX - XB_MIN) / XB;
        int mid = (int) ((center - XB_MIN) / binW);
        int step = left ? -1 : 1;
        // walk outward from the car: the first solid peak is the edge of our own lane
        for (int j = mid + step * 3; j >= 0 && j < XB; j += step) {
            float xb = XB_MIN + (j + 0.5f) * binW;
            float dist = Math.abs(xb - center);
            if (dist > 1.0f) break;
            if (best[j] < need) continue;
            int peak = j;
            for (int k = j; k >= 0 && k < XB && Math.abs(k - j) <= 5; k += step) {
                if (best[k] > best[peak]) peak = k;
            }
            float pxb = XB_MIN + (peak + 0.5f) * binW;
            float xv = center - 0.16f + 0.02f * bestV[peak];
            Line line = refine(pts, w, h, horizon, yb, pxb, xv, rows);
            if (line != null) return line;
            j = peak + step * 3;
        }
        return null;
    }

    private Line refine(List<int[]> pts, int w, int h, float horizon, float yb, float xb, float xv, int rows) {
        float b = (xb - xv) / (yb - horizon), a = xv - b * horizon;
        int used = 0, yel = 0;
        for (int pass = 0; pass < 2; pass++) {
            double n = 0, sy = 0, sx = 0, syy = 0, sxy = 0;
            used = 0; yel = 0;
            int lastY = -1;
            for (int[] p : pts) {
                float x = (float) p[0] / w, y = (float) p[1] / h;
                float tol = 0.012f + 0.045f * Math.max(0, y - horizon) * (pass == 0 ? 1f : 0.6f);
                if (Math.abs(x - (a + b * y)) > tol || p[1] == lastY) continue;
                lastY = p[1];
                n++; sy += y; sx += x; syy += y * y; sxy += x * y;
                used++; yel += p[2];
            }
            double den = n * syy - sy * sy;
            if (n < 4 || den < 1e-9) return null;
            b = (float) ((n * sxy - sx * sy) / den);
            a = (float) ((sx - b * sy) / n);
        }
        float support = (float) used / rows;
        if (support < 0.2f) return null;
        return new Line(a, b, Math.min(1f, support), (float) yel / used);
    }
}
