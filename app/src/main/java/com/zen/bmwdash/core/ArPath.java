package com.zen.bmwdash.core;

import java.util.ArrayList;
import java.util.List;

/** Turns the route ahead into ground coordinates seen from the car, for drawing on the camera picture. */
public final class ArPath {
    /** Point at a distance along a {lat, lon} path, clamped to its ends. */
    public static double[] pointAt(double[][] path, double along) {
        double run = 0;
        for (int i = 0; i + 1 < path.length; i++) {
            double len = Geo.distance(path[i][0], path[i][1], path[i + 1][0], path[i + 1][1]);
            if (len > 0 && run + len >= along) {
                double f = Math.max(0, (along - run) / len);
                return new double[]{path[i][0] + (path[i + 1][0] - path[i][0]) * f, path[i][1] + (path[i + 1][1] - path[i][1]) * f};
            }
            run += len;
        }
        return path[path.length - 1];
    }

    public static double bearingAt(double[][] path, double along) {
        double[] a = pointAt(path, along), b = pointAt(path, along + 15);
        return Geo.bearing(a[0], a[1], b[0], b[1]);
    }

    /** Signed smallest angle from a to b, -180..180. */
    public static double turn(double a, double b) { return ((b - a + 540) % 360) - 180; }

    /**
     * The car's heading to draw with: the measured one, snapped to the road when they nearly agree,
     * and the road's own direction when we have none.
     */
    public static double viewHeading(double[][] path, double along, double measured) {
        double road = bearingAt(path, along);
        if (Double.isNaN(measured) || Math.abs(turn(road, measured)) < 7) return road;
        return measured;
    }

    /** @return points {metres right, metres ahead, metres along from the car} */
    public static List<float[]> build(double[][] path, double along, double heading, double ahead, double step) {
        List<float[]> out = new ArrayList<>();
        if (path.length < 2) return out;
        double[] o = pointAt(path, along);
        double h = Math.toRadians(heading), k = Math.cos(Math.toRadians(o[0]));
        double total = 0;
        for (int i = 0; i + 1 < path.length; i++) total += Geo.distance(path[i][0], path[i][1], path[i + 1][0], path[i + 1][1]);
        for (double d = 0; d <= ahead && along + d <= total + step; d += step) {
            double[] p = pointAt(path, along + d);
            double e = (p[1] - o[1]) * 111_195 * k, n = (p[0] - o[0]) * 111_195;
            out.add(new float[]{(float) (e * Math.cos(h) - n * Math.sin(h)), (float) (e * Math.sin(h) + n * Math.cos(h)), (float) d});
        }
        return out;
    }
}
