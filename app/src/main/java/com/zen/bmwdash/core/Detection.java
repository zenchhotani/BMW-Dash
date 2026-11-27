package com.zen.bmwdash.core;

/** One detector hit. Coordinates are normalised 0..1 in the upright camera frame. */
public final class Detection {
    public final String label;
    public final float score, left, top, right, bottom;
    /** found as a pair of red tail lamps at night, not by the object model */
    public boolean lamps;
    public Detection(String label, float score, float left, float top, float right, float bottom) {
        this.label = label; this.score = score;
        this.left = left; this.top = top; this.right = right; this.bottom = bottom;
    }
}
