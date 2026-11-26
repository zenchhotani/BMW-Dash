package com.zen.bmwdash.core;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** Tells moving vehicles from stopped and parked ones, using our speed and how fast the gap changes. */
public final class MotionClassifier {
    private static final class Past {
        double speed = Double.NaN;
        long stillSince = -1, goSince = -1, seen;
        boolean moved;
        int state;
    }
    private final Map<Integer, Past> past = new HashMap<>();

    /** Sets Track.motion; a parked vehicle can never be the car we are following. */
    public void update(long t, double ego, List<Track> tracks) {
        for (Track k : tracks) {
            if (!k.isVehicle()) continue;
            Past p = past.get(k.id);
            if (p == null) past.put(k.id, p = new Past());
            p.seen = t;
            if (!Float.isNaN(k.rangeRate)) {
                double other = ego + k.rangeRate;
                p.speed = Double.isNaN(p.speed) ? other : p.speed + 0.35 * (other - p.speed);
                // distance estimates are noisy, more so the faster we close on something
                boolean still = Math.abs(p.speed) < Math.max(2.2, ego * .25);
                boolean going = Math.abs(p.speed) > Math.max(3.5, ego * .4);
                if (!still) p.stillSince = -1; else if (p.stillSince < 0) p.stillSince = t;
                if (!going) p.goSince = -1; else if (p.goSince < 0) p.goSince = t;
                if (p.goSince >= 0 && t - p.goSince >= 800) { p.state = Track.MOVING; p.moved = true; }
                else if (p.stillSince >= 0 && t - p.stillSince >= 800) p.state = Track.STOPPED;
            }
            k.motion = p.state;
            // standing still, never seen to move, and off to the side: parked
            if (p.state == Track.STOPPED && !p.moved && !Float.isNaN(k.lateral) && Math.abs(k.lateral) > 1.1f) {
                k.motion = Track.PARKED;
                k.lead = false;
            }
        }
        for (Iterator<Past> it = past.values().iterator(); it.hasNext(); ) if (t - it.next().seen > 2000) it.remove();
    }
}
