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
    /** people, animals and objects: seen long and surely enough to show and warn about */
    public boolean confirmed;
    float scoreSum;
    /** set when the picture is dark, which calls for more proof */
    boolean poor;
    /** lit colour for traffic lights, see SignalColor */
    public int signal;
    /** the traffic light judged to be for our lane */
    public boolean ours;
    public static final int UNKNOWN = 0, MOVING = 1, STOPPED = 2, PARKED = 3;
    public int motion;
    /** what this driver seems to be doing, empty when nothing notable */
    public String intent = "";
    /** metres right of our axis, NaN when unknown; for vehicles the middle of the back, not of the box */
    public float lateral = Float.NaN;
    /** metres right of where our own car will be at that distance, allowing for the bend we are taking */
    public float pathX = Float.NaN;
    /** in front of our car's own path, so it matters for braking */
    public boolean inPath;
    /** seconds to contact from how fast it grows in the picture, NaN when not closing */
    public float ttc = Float.NaN;
    /** growth of the picture per second, d ln(size)/dt */
    public float growth = Float.NaN;
    final ArrayDeque<float[]> history = new ArrayDeque<>();
    /** {seconds, ln height, centre x} of the raw boxes, for growth and sideways motion */
    final ArrayDeque<float[]> sizes = new ArrayDeque<>();
    /** only ever seen as a pair of tail lamps at night */
    public boolean lamps = true;
    float stillFor, goingFor;
    boolean moved;
    long judgedAt = -1;

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
