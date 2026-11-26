package com.zen.bmwdash.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Lane-marking finder: contrast-adaptive bright/yellow ridge points below the horizon,
 * a Hough vote over (x at bottom, x at horizon), least-squares refinement, and
 * tracking of the previous frame's lines through gaps. Keeps state between frames.
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

    private Line lastLeft, lastRight;
    private int missLeft, missRight;
    /** learned difference between the calibrated horizon and where the lines really meet */
    private float tilt;
    /** boxes {left, top, right, bottom} whose edges must not be read as paint, e.g. vehicles */
    public List<float[]> ignore = new ArrayList<>();

    /**
     * @param gray    w*h luminance 0..255
     * @param yellow  w*h, true where the pixel looks like yellow paint
     * @param horizon horizon row, 0..1
     * @param bottom  lowest usable row (above the hood), 0..1
     * @param center  x of the car's own axis in the frame, 0..1
     */
    public Result detect(int[] gray, boolean[] yellow, int w, int h, float horizon, float bottom, float center) {
        float calibrated = horizon;
        horizon += tilt;
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
            // threshold follows this row's own noise, so faded paint in shade or at night still shows
            long rough = 0;
            for (int x = 2; x < w; x++) rough += Math.abs(gray[base + x] - gray[base + x - 2]);
            int th = Math.max(13, Math.min(30, Math.round(2.4f * rough / (w - 2))));
            int bestR = 0;
            long sumX = 0, sumR = 0;
            for (int x = mw; x < w - mw; x++) {
                int v = value(gray, yellow, base + x);
                int dl = v - value(gray, yellow, base + x - mw);
                int dr = v - value(gray, yellow, base + x + mw);
                int r = (dl > th && dr > th) ? dl + dr - Math.abs(dl - dr) : 0;
                if (r > 0) {
                    bestR = Math.max(bestR, r); sumX += (long) x * r; sumR += r;
                } else if (bestR > 0) {
                    add(pts, (int) (sumX / sumR), y, w, h, yellow);
                    bestR = 0; sumX = sumR = 0;
                }
            }
            if (bestR > 0) add(pts, (int) (sumX / sumR), y, w, h, yellow);
        }
        if (pts.size() < 6) { forget(); return res; }

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

        int need = Math.max(8, Math.round(rows * 0.18f) * 2);
        res.left = pick(acc, pts, w, h, horizon, yb, center, rows, need, true);
        res.right = pick(acc, pts, w, h, horizon, yb, center, rows, need, false);
        // prefer continuing the line we already had: it survives dash gaps and stray marks
        Line tl = follow(lastLeft, pts, w, h, horizon, yb, center, rows);
        Line tr = follow(lastRight, pts, w, h, horizon, yb, center, rows);
        res.left = choose(res.left, tl, yb, center, true);
        res.right = choose(res.right, tr, yb, center, false);
        // seen in perspective, our left line leans one way and our right line the other
        if (res.left != null && res.left.x(yb) - res.left.x(horizon) > -0.04f) res.left = null;
        if (res.right != null && res.right.x(yb) - res.right.x(horizon) < 0.04f) res.right = null;
        if (res.left != null && res.right != null) {
            float width = res.right.x(yb) - res.left.x(yb);
            if (width < 0.4f || width > 1.6f) {
                if (res.left.support >= res.right.support) res.right = null; else res.left = null;
            }
        }
        if (res.left != null) { lastLeft = res.left; missLeft = 0; } else if (++missLeft > 8) lastLeft = null;
        if (res.right != null) { lastRight = res.right; missRight = 0; } else if (++missRight > 8) lastRight = null;
        // learn the real horizon (road slope, mount angle) from where the two lines meet
        if (res.left != null && res.right != null && Math.abs(res.left.b - res.right.b) > 0.4f) {
            float meet = (res.right.a - res.left.a) / (res.left.b - res.right.b);
            float want = Math.max(-0.07f, Math.min(0.07f, meet - calibrated));
            tilt += 0.04f * (want - tilt);
        }
        return res;
    }

    private void forget() {
        if (++missLeft > 8) lastLeft = null;
        if (++missRight > 8) lastRight = null;
    }

    private void add(List<int[]> pts, int x, int y, int w, int h, boolean[] yellow) {
        float fx = (float) x / w, fy = (float) y / h;
        for (float[] b : ignore) if (fx > b[0] && fx < b[2] && fy > b[1] && fy < b[3]) return;
        pts.add(new int[]{x, y, yellow[y * w + x] ? 1 : 0});
    }

    private Line follow(Line last, List<int[]> pts, int w, int h, float horizon, float yb, float center, int rows) {
        if (last == null) return null;
        float xv = last.x(horizon);
        return refine(pts, w, h, horizon, yb, last.x(yb), xv, center, rows, 0.12f);
    }

    private static Line choose(Line fresh, Line tracked, float yb, float center, boolean left) {
        if (tracked != null && (left ? tracked.x(yb) > center - 0.03f : tracked.x(yb) < center + 0.03f)) tracked = null;
        if (tracked == null) return fresh;
        if (fresh == null) return tracked;
        // a clearly stronger line closer to the car wins, for example after a lane change
        boolean closer = Math.abs(fresh.x(yb) - center) < Math.abs(tracked.x(yb) - center) - 0.08f;
        return closer && fresh.support > 0.3f || fresh.support > tracked.support + 0.2f ? fresh : tracked;
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
            Line line = refine(pts, w, h, horizon, yb, pxb, xv, center, rows, 0.17f);
            if (line != null) return line;
            j = peak + step * 3;
        }
        return null;
    }

    private Line refine(List<int[]> pts, int w, int h, float horizon, float yb, float xb, float xv, float center,
                        int rows, float minSupport) {
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
        // real lane lines head towards the middle of the road ahead
        if (support < minSupport || Math.abs(a + b * horizon - center) > 0.22f) return null;
        return new Line(a, b, Math.min(1f, support), (float) yel / used);
    }
}
