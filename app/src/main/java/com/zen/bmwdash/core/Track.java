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
    /** lit colour for traffic lights, see SignalColor */
    public int signal;
    /** the traffic light judged to be for our lane */
    public boolean ours;
    public static final int UNKNOWN = 0, MOVING = 1, STOPPED = 2, PARKED = 3;
    public int motion;
    /** what this driver seems to be doing, empty when nothing notable */
    public String intent = "";
    /** metres right of our axis, NaN when unknown */
    public float lateral = Float.NaN;
    final ArrayDeque<float[]> history = new ArrayDeque<>();

    public boolean isVehicle() {
        return label.equals("car") || label.equals("truck") || label.equals("bus") || label.equals("motorcycle");
    }
    public boolean isAnimal() {
        return label.equals("dog") || label.equals("cat") || label.equals("horse") || label.equals("cow")
                || label.equals("sheep") || label.equals("bear");
    }
    public boolean isObstacle() {
        return label.equals("suitcase") || label.equals("chair") || label.equals("backpack") || label.equals("bench");
    }
    public float cx() { return (left + right) / 2f; }
}
