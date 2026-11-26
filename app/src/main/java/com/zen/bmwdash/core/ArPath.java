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
        double[] a = pointAt(path, Math.max(0, along - 4)), b = pointAt(path, along + 12);
        return Geo.bearing(a[0], a[1], b[0], b[1]);
    }

    /** Signed smallest angle from a to b, -180..180. */
    public static double turn(double a, double b) { return ((b - a + 540) % 360) - 180; }

    /**
     * The car's heading to draw with. In normal driving the car follows the road, so the road's direction
     * where the car is now is used: the GPS heading lags on bends and swung the route sideways.
     * Only a clear disagreement at the last fix (turning off, facing elsewhere) is honoured.
     *
     * @param fixAlong where along the route the car was when the heading was measured
     */
    public static double viewHeading(double[][] path, double along, double fixAlong, double measured) {
        double road = bearingAt(path, along);
        if (Double.isNaN(measured)) return road;
        double bias = turn(bearingAt(path, fixAlong), measured);
        return Math.abs(bias) < 25 ? road : (road + bias + 360) % 360;
    }

    /** Cuts the path where it swings out of the camera's view, so no stray piece floats off to one side. */
    public static List<float[]> inView(List<float[]> pts) {
        for (int i = 0; i < pts.size(); i++) {
            float[] p = pts.get(i);
            if (Math.abs(p[0]) > 7 + 0.55f * Math.max(0, p[1])) return new ArrayList<>(pts.subList(0, i));
        }
        return pts;
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
