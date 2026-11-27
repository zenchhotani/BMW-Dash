package com.zen.bmwdash.core;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Event-based driver protection: collision, cut-in, pedestrians, lights, limits, fatigue and trip score. */
public final class Safety {
    public static final class Event {
        public final int level; public final String code, text, speak;
        public Event(String code, int level, String text, String speak) {
            this.code = code; this.level = level; this.text = text; this.speak = speak;
        }
    }
    public static final class Input {
        public long t;                       // ms, monotonic
        public double speed;                 // m/s
        public boolean seeing;               // camera data is fresh
        public List<Track> tracks;
        public double limit = Double.NaN;    // m/s
        public double safeGap = 2;           // seconds
        public int light;                    // SignalColor value
        public double caution = 1;           // above 1 on slippery or low-visibility roads
        public boolean turning;              // our car is going round a bend or corner
    }
    public static final class Trip {
        public double meters, movingSec, speedingSec, closeSec, topSpeed;
        public int hardBrakes, collisionWarnings;
        public int score() {
            double base = Math.max(60, movingSec);
            double s = 100 - hardBrakes * 4 - collisionWarnings * 4 - 60 * speedingSec / base - 40 * closeSec / base;
            return (int) Math.max(0, Math.min(100, Math.round(s)));
        }
    }

    public final Trip trip = new Trip();
    private final Map<String, Long> last = new HashMap<>();
    private Event held;
    private long heldUntil, prevT = -1, dangerSince = -1, stoppedSince = -1, restSince = -1, lightSince, speedT = -1;
    private double drivingSec, nextBreakSec = 7200, speedRef, prevLimit = Double.NaN;
    private int prevLeadId = -1, light, redWhileStopped;
    private float prevLeadDist = Float.NaN, waitDist = Float.NaN;
    private double leadSpeedRef = Double.NaN;
    private long leadRefT;

    public Event update(Input in) {
        long t = in.t;
        double dt = prevT < 0 ? 0 : Math.min(2, (t - prevT) / 1000.0);
        prevT = t;
        double v = in.speed;
        Track lead = null;
        if (in.seeing && in.tracks != null) for (Track k : in.tracks) if (k.lead) lead = k;
        boolean ranged = lead != null && !Float.isNaN(lead.distance);

        // trip statistics
        if (v > 2) {
            trip.meters += v * dt; trip.movingSec += dt; trip.topSpeed = Math.max(trip.topSpeed, v);
            if (!Double.isNaN(in.limit) && v > in.limit + 2.2) trip.speedingSec += dt;
            if (ranged && v > 9 && lead.distance / v < 1.0) trip.closeSec += dt;
        }
        if (speedT < 0 || t - speedT > 4000) { speedT = t; speedRef = v; }
        else if (t - speedT >= 1000) {
            if ((speedRef - v) / ((t - speedT) / 1000.0) > 3.8 && speedRef > 8 && fire("hardbrake", t, 8000)) trip.hardBrakes++;
            speedT = t; speedRef = v;
        }

        // collision: only for a vehicle in front of our own path, never a parked one, and only once it is well established
        boolean moves = ranged && lead.motion == Track.MOVING;
        boolean inPath = ranged && lead.inPath && lead.motion != Track.PARKED && (moves || lead.hits >= 6) && (!lead.lamps || lead.hits >= 10);
        if (inPath && v > 4 && lead.hits >= 5 && !Float.isNaN(lead.rangeRate)) {
            double closing = -lead.rangeRate, d = lead.distance;
            double ttc = closing > 1.5 ? d / closing : 99;
            double needed = closing > 1.5 ? closing * closing / (2 * Math.max(1, d - 4)) : 0;
            double c = Math.max(1, in.caution);
            boolean danger = d < 60 * c && (ttc < 2.5 * (1 + (c - 1) * .6) || needed > 4 / c);
            if (danger) {
                if (dangerSince < 0) dangerSince = t;
                if (t - dangerSince >= 400) {
                    if (fire("fcwcount", t, 8000)) trip.collisionWarnings++;
                    hold(new Event("brake", Advisor.DANGER, "BRAKE NOW", "Brake! Brake!"), t, 1500);
                }
            } else {
                dangerSince = -1;
                if (ttc < 4.5 * c && needed > 1.8 / c) hold(new Event("slow", Advisor.WARN, "Slow down: closing on the car ahead", "Slow down"), t, 2000);
            }
        } else dangerSince = -1;

        // lead vehicle braking hard
        if (ranged && !Float.isNaN(lead.rangeRate) && v > 8 && lead.id == prevLeadId) {
            double other = v + lead.rangeRate;
            if (Double.isNaN(leadSpeedRef) || t - leadRefT > 1500) { leadSpeedRef = other; leadRefT = t; }
            else if (leadSpeedRef - other > 4 && lead.distance < v * 3 && fire("leadbrake", t, 10000))
                hold(new Event("leadbrake", Advisor.WARN, "Car ahead is braking", "Car ahead braking"), t, 3000);
        } else leadSpeedRef = Double.NaN;

        // cut-in
        if (ranged && v > 10 && lead.id != prevLeadId && lead.motion == Track.MOVING && lead.hits >= 6 && lead.distance < v * 1.2
                && (Float.isNaN(prevLeadDist) || lead.distance < prevLeadDist * 0.65) && fire("cutin", t, 15000))
            hold(new Event("cutin", Advisor.WARN, "Vehicle cut in: ease off", "Vehicle cutting in"), t, 3000);

        // waiting in traffic and the car ahead leaves
        if (v < 0.7) { if (stoppedSince < 0) stoppedSince = t; } else { stoppedSince = -1; waitDist = Float.NaN; redWhileStopped = 0; }
        if (stoppedSince >= 0 && t - stoppedSince > 3000 && ranged) {
            if (lead.id != prevLeadId || Float.isNaN(waitDist)) waitDist = lead.distance < 14 ? lead.distance : Float.NaN;
            else if (lead.distance < waitDist) waitDist = lead.distance;
            else if (lead.distance - waitDist > 3.5 && fire("go", t, 20000)) {
                hold(new Event("go", Advisor.GOOD, "Traffic is moving", "Traffic is moving"), t, 3000);
                waitDist = Float.NaN;
            }
        }
        if (ranged) { prevLeadId = lead.id; prevLeadDist = lead.distance; }
        else if (lead == null && in.seeing) { prevLeadDist = Float.NaN; }

        // stop signs facing us: central or on our side of the road
        if (in.seeing && in.tracks != null && v > 2.5)
            for (Track k : in.tracks)
                if (k.label.equals("stop sign") && k.hits >= 2 && k.cx() > 0.35f && k.right - k.left > 0.012f && fire("stopsign", t, 15000))
                    hold(new Event("stopsign", Advisor.WARN, "Stop sign ahead", "Stop sign ahead"), t, 3500);

        // people, animals and objects in our path
        if (in.seeing && in.tracks != null && v > 3) {
            for (Track k : in.tracks) {
                if (k.isVehicle() || !k.confirmed || Float.isNaN(k.distance) || k.distance > Math.max(18, v * 2.5)) continue;
                if (Float.isNaN(k.pathX) || Math.abs(k.pathX) > 1.6) continue;
                String what = k.label.equals("person") ? "Person" : k.label.equals("bicycle") ? "Cyclist" : k.isAnimal() ? "Animal" : k.isObstacle() ? "Object" : null;
                if (what == null || !fire("path" + what, t, 6000)) continue;
                boolean close = k.distance < Math.max(10, v * 1.4);
                hold(new Event("path", close ? Advisor.DANGER : Advisor.WARN, what.toUpperCase(java.util.Locale.US) + " IN YOUR PATH", what + " ahead"), t, 2500);
            }
        }

        // traffic light advice
        if (in.light != light) { light = in.light; lightSince = t; }
        if (t - lightSince > 600) {
            if (light == SignalColor.RED) {
                if (v < 0.7) redWhileStopped = 1;
                else if (v > 6 && fire("red", t, 20000)) hold(new Event("red", Advisor.WARN, "Red light ahead", "Red light ahead"), t, 3000);
            } else if (light == SignalColor.YELLOW) {
                if (v > 6 && fire("yellow", t, 15000)) hold(new Event("yellow", Advisor.WARN, "Yellow light ahead", "Yellow light"), t, 3000);
            } else if (light == SignalColor.GREEN && redWhileStopped == 1 && v < 0.7) {
                redWhileStopped = 0;
                if (fire("green", t, 20000)) hold(new Event("green", Advisor.GOOD, "Light is green. Check before you go.", "Light is green"), t, 3000);
            }
        }

        // new speed limit
        if (!Double.isNaN(in.limit)) {
            if (!Double.isNaN(prevLimit) && Math.abs(in.limit - prevLimit) > 1 && v > 4 && fire("limit", t, 10000))
                hold(new Event("limit", Advisor.INFO, "New speed limit ahead", "Speed limit changed"), t, 4000);
            prevLimit = in.limit;
        }

        // fatigue
        if (v > 2) {
            restSince = -1; drivingSec += dt;
            if (drivingSec > nextBreakSec) {
                nextBreakSec += 3600;
                hold(new Event("break", Advisor.WARN, "Long drive: take a 15 minute break", "Time for a break"), t, 8000);
            }
        } else {
            if (restSince < 0) restSince = t;
            else if (t - restSince > 900_000) { drivingSec = 0; nextBreakSec = 7200; }
        }

        return t < heldUntil ? held : null;
    }

    private boolean fire(String code, long t, long cooldown) {
        Long l = last.get(code);
        if (l != null && t - l < cooldown) return false;
        last.put(code, t);
        return true;
    }

    private void hold(Event e, long t, long ms) {
        if (held != null && t < heldUntil && held.level > e.level) return;
        held = e; heldUntil = t + ms;
    }
}
