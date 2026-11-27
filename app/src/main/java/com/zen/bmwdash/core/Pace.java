package com.zen.bmwdash.core;

/**
 * How hard the phone should work. Driving at normal temperature is never slowed in a way that matters;
 * the savings come from standing still, and from backing off only when the phone itself reports heat.
 */
public final class Pace {
    /** matches the system's thermal levels: 0 none, 1 light, 2 moderate, 3 severe, 4 and up critical */
    public static final int WARM = 2, HOT = 3;
    /** faster than this (about 50 mph) the far road needs the attention */
    public static final double HIGHWAY = 22;

    /** shortest time between two looks at the road, ms */
    public static long detectMs(int heat, double speed, long stillMs) {
        long base = speed > 1 || stillMs < 4000 ? 140 : stillMs < 90_000 ? 330 : 600;
        long floor = heat >= 4 ? 450 : heat >= HOT ? 330 : heat >= WARM ? 220 : 0;
        return Math.max(base, floor);
    }

    /** shortest time between two sign readings, ms */
    public static long signMs(int heat, double speed) {
        if (heat >= HOT) return speed > HIGHWAY ? 700 : 1400;
        if (speed > HIGHWAY) return heat >= WARM ? 500 : 350;
        if (speed < 1) return 1500;
        return heat >= WARM ? 900 : 600;
    }

    /** time between redraws of animated layers, ms */
    public static long frameMs(int heat) { return heat >= HOT ? 80 : heat >= WARM ? 50 : 33; }

    /**
     * Which part of the picture to look at next: 0 whole, 1 road ahead, 2 overhead signals,
     * 3 roadside, 4 far road at double detail. At highway speed the far road comes round most often.
     */
    public static int pass(long count, double speed) {
        int[] order = speed > HIGHWAY ? new int[]{1, 4, 0, 4, 1, 3, 0, 4, 2} : new int[]{0, 1, 2, 0, 1, 3};
        return order[(int) (count % order.length)];
    }
}
