package com.zen.bmwdash.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Guesses what drivers ahead are about to do from how their position and speed change:
 * moving into our lane, leaving it, braking, stopped, Estimates only.
 */
public final class Predictor {
    private static final class Past {
        /** {seconds, lateral metres, their speed m/s} */
        final ArrayDeque<double[]> samples = new ArrayDeque<>();
        long seen, cutSince = -1, leaveSince = -1;
    }
    private final Map<Integer, Past> past = new HashMap<>();
    private final Map<String, Long> last = new HashMap<>();

    /** Sets Track.intent on each vehicle and returns the most important new warning, or null. */
    public Safety.Event update(long t, double speed, List<Track> tracks) {
        List<Track> cars = new ArrayList<>();
        List<Double> drift = new ArrayList<>();
        for (Track k : tracks) {
            k.intent = "";
            if (!k.isVehicle() || k.hits < 5 || Float.isNaN(k.distance) || Float.isNaN(k.lateral) || k.distance > 60) continue;
            Past p = past.get(k.id);
            if (p == null) past.put(k.id, p = new Past());
            p.seen = t;
            double other = Float.isNaN(k.rangeRate) ? Double.NaN : speed + k.rangeRate;
            p.samples.addLast(new double[]{t / 1000.0, k.lateral, other});
            while (t / 1000.0 - p.samples.peekFirst()[0] > 2.5) p.samples.removeFirst();
            cars.add(k);
            drift.add(rate(p, 1, 1.2) / Math.max(8, k.distance));
        }
        // when everything slides the same way it is our own car turning, not theirs
        double turning = 0;
        if (drift.size() >= 2) {
            List<Double> sorted = new ArrayList<>(drift);
            sorted.removeIf(d -> d.isNaN());
            java.util.Collections.sort(sorted);
            if (sorted.size() >= 2 && (sorted.get(0) > 0.025 || sorted.get(sorted.size() - 1) < -0.025))
                turning = sorted.get(sorted.size() / 2);
        }

        Safety.Event worst = null;
        for (Track k : cars) {
            Past p = past.get(k.id);
            double side = rate(p, 1, 1.2) - turning * Math.max(8, k.distance);
            double accel = rate(p, 2, 1.6);
            double lat = k.lateral;
            boolean moving = speed > 5;

            boolean driving = k.motion == Track.MOVING;
            boolean cutting = driving && moving && Math.abs(lat) > 1.3 && Math.abs(lat) < 4.5 && side * lat < 0
                    && Math.abs(side) > 0.55 && Math.abs(lat + side * 1.8) < 1.3 && k.distance < Math.max(20, speed * 2.5);
            if (!cutting) p.cutSince = -1; else if (p.cutSince < 0) p.cutSince = t;
            boolean leaving = driving && k.lead && moving && side * lat > 0 && Math.abs(side) > 0.55 && Math.abs(lat + side * 1.5) > 1.9;
            if (!leaving) p.leaveSince = -1; else if (p.leaveSince < 0) p.leaveSince = t;

            Safety.Event e = null;
            if (p.cutSince >= 0 && t - p.cutSince >= 400) {
                k.intent = "MOVING INTO YOUR LANE";
                e = event("p-cut", Advisor.WARN, "Car on your " + (lat < 0 ? "left" : "right") + " may cut in: ease off",
                        "Car may cut in", k.id, t);
            } else if (k.lead && moving && k.motion == Track.STOPPED && k.inPath && k.hits >= 8
                    && k.distance < Math.max(25, speed * 4)) {
                k.intent = "STOPPED";
                e = event("p-stop", Advisor.WARN, "Stopped vehicle ahead: slow down", "Stopped vehicle ahead", k.id, t);
            } else if (driving && k.lead && moving && accel < -1.8 && k.distance < speed * 3.5) {
                k.intent = "SLOWING";
                e = event("p-slow", Advisor.WARN, "Driver ahead is slowing: get ready to brake", "Car ahead slowing", k.id, t);
            } else if (p.leaveSince >= 0 && t - p.leaveSince >= 400) {
                k.intent = String.format(Locale.US, "MOVING %s", side < 0 ? "LEFT" : "RIGHT");
                e = event("p-leave", Advisor.INFO, "Car ahead is changing lanes", null, k.id, t);
            } else if (driving && k.lead && moving && accel > 1.5) k.intent = "SPEEDING UP";
            if (e != null && (worst == null || e.level > worst.level)) worst = e;
        }
        for (Iterator<Past> it = past.values().iterator(); it.hasNext(); ) if (t - it.next().seen > 2000) it.remove();
        return worst;
    }

    private Safety.Event event(String code, int level, String text, String speak, int id, long t) {
        Long same = last.get(code + id), any = last.get(code);
        if ((same != null && t - same < 20000) || (any != null && t - any < 8000)) return null;
        last.put(code + id, t); last.put(code, t);
        return new Safety.Event(code, level, text, speak);
    }

    /** least-squares change per second of one sample column over the last window, NaN if too little data */
    private static double rate(Past p, int column, double window) {
        if (p.samples.isEmpty()) return Double.NaN;
        double now = p.samples.peekLast()[0], n = 0, sx = 0, sy = 0, sxx = 0, sxy = 0, first = now;
        for (double[] s : p.samples) {
            double x = s[0] - now, y = s[column];
            if (-x > window || Double.isNaN(y)) continue;
            first = Math.min(first, s[0]);
            n++; sx += x; sy += y; sxx += x * x; sxy += x * y;
        }
        double den = n * sxx - sx * sx;
        return n < 4 || now - first < window * 0.5 || den < 1e-9 ? Double.NaN : (n * sxy - sx * sy) / den;
    }
}
