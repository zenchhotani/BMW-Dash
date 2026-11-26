package com.zen.bmwdash.core;

/** What the weather means for driving. */
public final class Conditions {
    public static final int CLEAR = 0, RAIN = 1, SNOW = 2, ICE = 3, FOG = 4;

    /** from a WMO weather code */
    public static int kind(int code) {
        if (code == 56 || code == 57 || code == 66 || code == 67) return ICE;
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return SNOW;
        if ((code >= 51 && code <= 65) || (code >= 80 && code <= 82) || code >= 95) return RAIN;
        if (code == 45 || code == 48) return FOG;
        return CLEAR;
    }

    public static boolean iceRisk(double celsius) { return !Double.isNaN(celsius) && celsius <= 1.5; }

    /** how much earlier to warn: 1 is a dry road */
    public static double caution(int kind, boolean iceRisk) {
        double c = kind == SNOW || kind == ICE ? 1.5 : kind == FOG ? 1.3 : kind == RAIN ? 1.25 : 1;
        return iceRisk ? Math.max(c, 1.15) : c;
    }

    /** following gap in seconds */
    public static double gap(int kind, boolean iceRisk) {
        double g = kind == SNOW || kind == ICE ? 5 : kind == FOG ? 3.5 : kind == RAIN ? 3 : 2;
        return iceRisk ? Math.max(g, 3) : g;
    }

    /** suggested top speed in the same unit as the limit, NaN in clear weather or without a limit */
    public static double suggested(double limit, int kind) {
        double f = kind == ICE ? .6 : kind == SNOW ? .7 : kind == FOG ? .8 : kind == RAIN ? .9 : Double.NaN;
        return limit * f;
    }
}
