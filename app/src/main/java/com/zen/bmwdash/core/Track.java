package com.zen.bmwdash.core;

import java.util.ArrayDeque;

/** A road object followed across frames. */
public final class Track {
    public int id;
    public String label;
    public float score, left, top, right, bottom;
    public long firstSeen, lastSeen;
    public int hits;
    /** metres ahead, NaN when unknown */
    public float distance = Float.NaN;
    /** metres/second, positive = gap opening */
    public float rangeRate = Float.NaN;
    public boolean lead;
    final ArrayDeque<float[]> history = new ArrayDeque<>();

    public boolean isVehicle() {
        return label.equals("car") || label.equals("truck") || label.equals("bus") || label.equals("motorcycle");
    }
    public float cx() { return (left + right) / 2f; }
}
