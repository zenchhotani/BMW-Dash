package com.zen.bmwdash.core;

/** Smooths raw lane lines over time and derives position in lane, departure and lane type. */
public final class LaneState {
    public enum Position { UNKNOWN, CENTERED, DRIFT_LEFT, DRIFT_RIGHT }
    public enum LaneType { UNKNOWN, LEFTMOST, MIDDLE, RIGHTMOST, YELLOW_ON_RIGHT }

    private static final class Side {
        float a, b, support, yellow;
        long seen = -10000, since = -1;
        void update(LaneDetector.Line l, long t, float yRef) {
            if (l == null) return;
            if (t - seen > 800 || Math.abs((a + b * yRef) - l.x(yRef)) > 0.18f) {
                a = l.a; b = l.b; support = l.support; yellow = l.yellowFraction; since = t;
            } else {
                float k = 0.3f;
                a += k * (l.a - a); b += k * (l.b - b);
                support += 0.08f * (l.support - support);
                yellow += 0.12f * (l.yellowFraction - yellow);
            }
            seen = t;
        }
        boolean live(long t) { return t - seen < 700; }
        boolean stable(long t) { return live(t) && t - since > 1500; }
        boolean isYellow() { return yellow > 0.45f; }
        boolean solid() { return support > 0.72f; }
    }

    private final Side l = new Side(), r = new Side();
    private long driftSince = -1, typeSince = 0;
    private LaneType candidate = LaneType.UNKNOWN;

    public boolean hasLeft, hasRight, leftSolid, rightSolid, leftYellow, rightYellow;
    public float la, lb, ra, rb;
    /** Car offset from lane centre in lane widths; negative = left of centre. */
    public float offset;
    public Position position = Position.UNKNOWN;
    public LaneType type = LaneType.UNKNOWN;
    public boolean departing;

    public void update(LaneDetector.Result res, long t, float center, float yBottom) {
        l.update(res.left, t, yBottom);
        r.update(res.right, t, yBottom);
        hasLeft = l.live(t); hasRight = r.live(t);
        la = l.a; lb = l.b; ra = r.a; rb = r.b;
        leftSolid = l.solid(); rightSolid = r.solid(); leftYellow = l.isYellow(); rightYellow = r.isYellow();

        if (hasLeft && hasRight) {
            float xl = la + lb * yBottom, xr = ra + rb * yBottom, width = Math.max(0.1f, xr - xl);
            offset = (center - (xl + xr) / 2f) / width;
            if (offset < -0.22f) position = Position.DRIFT_LEFT;
            else if (offset > 0.22f) position = Position.DRIFT_RIGHT;
            else position = Position.CENTERED;
        } else {
            offset = 0; position = Position.UNKNOWN;
        }
        boolean out = Math.abs(offset) > 0.28f && position != Position.UNKNOWN;
        if (!out) driftSince = -1; else if (driftSince < 0) driftSince = t;
        departing = out && t - driftSince > 600;

        LaneType now = LaneType.UNKNOWN;
        if (r.stable(t) && r.isYellow() && r.solid()) now = LaneType.YELLOW_ON_RIGHT;
        else if (l.stable(t) && l.isYellow()) now = LaneType.LEFTMOST;
        else if (r.stable(t) && r.solid() && !r.isYellow() && l.stable(t) && !l.solid()) now = LaneType.RIGHTMOST;
        else if (l.stable(t) && r.stable(t) && !l.solid() && !r.solid()) now = LaneType.MIDDLE;
        if (now != candidate) { candidate = now; typeSince = t; }
        if (t - typeSince > 1500) type = candidate;
    }
}
