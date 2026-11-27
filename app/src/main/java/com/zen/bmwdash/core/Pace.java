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

    /**
     * Shortest time between two looks at the road, ms. Safety comes first: a warm phone does not slow
     * the road check, only extras; only a severely hot phone backs off, and never below about 4 looks a second.
     */
    public static long detectMs(int heat, double speed, long stillMs) {
        long base = speed > 1 || stillMs < 4000 ? 90 : stillMs < 90_000 ? 300 : 600;
        long floor = heat >= 4 ? 260 : heat >= HOT ? 160 : 0;
        return Math.max(base, floor);
    }

    /** shortest time between two sign readings, ms; reading text is costly, so it is the first to give way */
    public static long signMs(int heat, double speed) {
        long base = speed < 1 ? 2500 : speed > HIGHWAY ? 1100 : 800;
        return heat >= HOT ? base * 3 : heat >= WARM ? base * 3 / 2 : base;
    }

    /** time between redraws of animated layers, ms */
    public static long frameMs(int heat) { return heat >= HOT ? 80 : heat >= WARM ? 50 : 33; }

    /**
     * Which part of the picture to look at next: 0 whole, 1 road ahead, 2 overhead signals,
     * 3 roadside, 4 far road at double detail. At highway speed the far road comes round most often.
     */
    public static int pass(long count, double speed) {
        int[] order = speed > HIGHWAY ? new int[]{1, 4, 0, 1, 4, 3} : new int[]{1, 2, 0, 1, 2, 3};
        return order[(int) (count % order.length)];
    }
}
