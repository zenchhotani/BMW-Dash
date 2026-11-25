package com.zen.bmwdash.core;

/** Small-area geodesy helpers. Points are {lat, lon}. */
public final class Geo {
    private static final double R = 6371000;

    public static double distance(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1), dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) + Math.cos(Math.toRadians(lat1))
                * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * R * Math.asin(Math.min(1, Math.sqrt(a)));
    }

    public static double bearing(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2), dl = Math.toRadians(lon2 - lon1);
        double y = Math.sin(dl) * Math.cos(p2);
        double x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl);
        return (Math.toDegrees(Math.atan2(y, x)) + 360) % 360;
    }

    /** @return {distance to segment in metres, fraction along segment 0..1} */
    public static double[] toSegment(double lat, double lon, double aLat, double aLon, double bLat, double bLon) {
        double k = Math.cos(Math.toRadians(lat));
        double ax = Math.toRadians(aLon - lon) * k * R, ay = Math.toRadians(aLat - lat) * R;
        double bx = Math.toRadians(bLon - lon) * k * R, by = Math.toRadians(bLat - lat) * R;
        double dx = bx - ax, dy = by - ay, len = dx * dx + dy * dy;
        double f = len == 0 ? 0 : Math.max(0, Math.min(1, -(ax * dx + ay * dy) / len));
        return new double[]{Math.hypot(ax + f * dx, ay + f * dy), f};
    }

    /** @return {metres off the path, metres along the path} */
    public static double[] project(double[][] path, double lat, double lon) {
        double best = Double.MAX_VALUE, along = 0, run = 0;
        for (int i = 0; i + 1 < path.length; i++) {
            double[] s = toSegment(lat, lon, path[i][0], path[i][1], path[i + 1][0], path[i + 1][1]);
            double len = distance(path[i][0], path[i][1], path[i + 1][0], path[i + 1][1]);
            if (s[0] < best) { best = s[0]; along = run + s[1] * len; }
            run += len;
        }
        return new double[]{best, along};
    }

    /** Smallest angle between two headings, ignoring direction of travel (0..90). */
    public static double axisDifference(double a, double b) {
        double d = Math.abs(a - b) % 180;
        return d > 90 ? 180 - d : d;
    }

    /** Parses an OpenStreetMap maxspeed value to km/h, NaN if not numeric. */
    public static double parseMaxSpeedKmh(String s) {
        if (s == null) return Double.NaN;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^\\s*(\\d+(?:\\.\\d+)?)\\s*(mph|km/h|kmh)?").matcher(s);
        if (!m.find()) return Double.NaN;
        double v = Double.parseDouble(m.group(1));
        return "mph".equals(m.group(2)) ? v * 1.609344 : v;
    }
}
