package com.zen.bmwdash.core;

/** Turns the live driving picture into one prioritised message. Speeds in m/s. */
public final class Advisor {
    public static final int INFO = 0, GOOD = 1, WARN = 2, DANGER = 3;

    public static final class Input {
        public double speed, limit = Double.NaN, flow = Double.NaN, freeFlow = Double.NaN;
        public Track lead;
        public boolean laneDeparture;
        public LaneState.LaneType laneType = LaneState.LaneType.UNKNOWN;
        public int turnDirection; // -1 left, +1 right
        public double turnMeters = Double.NaN;
        public String weather; // short hazard text or null
        public String incident; // nearby incident text or null
    }

    public static final class Advice {
        public final int level; public final String text, speak;
        Advice(int level, String text, String speak) { this.level = level; this.text = text; this.speak = speak; }
    }

    public static Advice advise(Input in) {
        boolean moving = in.speed > 4;
        Track l = in.lead;
        if (l != null && !Float.isNaN(l.distance) && moving) {
            double closing = Float.isNaN(l.rangeRate) ? 0 : -l.rangeRate;
            double ttc = closing > 1.5 ? l.distance / closing : Double.MAX_VALUE;
            if (ttc < 2.8 && l.distance < 45) return new Advice(DANGER, "BRAKE: closing fast on the car ahead", "Brake");
        }
        if (in.laneType == LaneState.LaneType.YELLOW_ON_RIGHT && moving)
            return new Advice(DANGER, "CHECK LANE: yellow line on your right", "Check your lane");
        if (l != null && !Float.isNaN(l.distance) && in.speed > 9 && l.distance / in.speed < 1.2)
            return new Advice(WARN, "Too close: leave more room", "Too close");
        if (in.laneDeparture && in.speed > 13) return new Advice(WARN, "Drifting out of lane", "Lane departure");
        if (in.turnDirection != 0 && in.turnMeters < 900) {
            if (in.turnDirection < 0 && in.laneType == LaneState.LaneType.RIGHTMOST)
                return new Advice(WARN, "Move left for your turn", "Move to the left lane");
            if (in.turnDirection > 0 && in.laneType == LaneState.LaneType.LEFTMOST)
                return new Advice(WARN, "Move right for your turn", "Move to the right lane");
        }
        if (!Double.isNaN(in.limit) && in.speed > in.limit + 4.5)
            return new Advice(WARN, "Over the speed limit", "Check your speed");
        if (in.incident != null) return new Advice(INFO, in.incident, null);
        if (!Double.isNaN(in.flow) && !Double.isNaN(in.freeFlow) && in.flow < in.freeFlow * 0.6)
            return new Advice(WARN, "Slow traffic on this road", "Slow traffic");
        if (in.weather != null) return new Advice(INFO, in.weather, null);
        if (l != null && !Float.isNaN(l.distance) && moving && l.distance / Math.max(in.speed, 1) < 2)
            return new Advice(INFO, "Following: ease back to a 2 second gap", null);
        return new Advice(GOOD, moving ? "Road looks clear. Drive smoothly." : "Ready when you are.", null);
    }
}
