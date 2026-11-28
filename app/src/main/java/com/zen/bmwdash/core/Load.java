package com.zen.bmwdash.core;

/**
 * Watches whether the road check keeps up, and decides when the extras must step aside.
 * Safety first: when the check falls far behind for a few seconds the extras pause on their own,
 * and they come back only after the check has been healthy for a good while.
 */
public final class Load {
    public static final int OK = 0, SLIPPING = 1, BEHIND = 2;
    /** far behind this long before the extras pause, ms */
    public static final long PAUSE_AFTER = 3000;
    /** healthy this long before the extras return, ms */
    public static final long RESUME_AFTER = 8000;

    private long behindSince = -1, healthySince = -1;
    private boolean paused;

    /**
     * How well the road check is keeping up while driving.
     * @param gapMs     smoothed time between looks
     * @param wantMs    the time between looks the pace asks for
     * @param silentMs  time since the last look began (a stalled check shows here)
     * @param driving   false when parked: the pace is slow on purpose then
     */
    public static int health(long gapMs, long wantMs, long silentMs, boolean driving) {
        if (!driving) return OK;
        if (silentMs > 700) return BEHIND;
        long lag = gapMs - wantMs;
        return lag > 260 ? BEHIND : lag > 110 ? SLIPPING : OK;
    }

    /** feed the latest health; returns true while the extras are paused */
    public boolean update(long now, int health) {
        if (health == BEHIND) {
            healthySince = -1;
            if (behindSince < 0) behindSince = now;
            if (!paused && now - behindSince >= PAUSE_AFTER) paused = true;
        } else {
            behindSince = -1;
            if (paused) {
                if (health == OK) {
                    if (healthySince < 0) healthySince = now;
                    if (now - healthySince >= RESUME_AFTER) { paused = false; healthySince = -1; }
                } else healthySince = -1;
            }
        }
        return paused;
    }

    public boolean paused() { return paused; }
}
