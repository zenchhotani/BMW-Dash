package com.zen.bmwdash.core;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Follows road objects across frames and works out, for each vehicle, how far it is, where its back is
 * relative to our path, how fast we close on it (from how quickly it grows in the picture, as single-camera
 * collision warning does), and whether it is driving, stopped in traffic or parked.
 */
public final class Tracker {
    /** focal length divided by frame width (pinhole model) */
    public float focal = 0.65f;
    /** frame height / width */
    public float aspect = 9f / 16f;
    /** camera height above road, metres */
    public float cameraHeight = 1.25f;
    public float horizon = 0.5f, center = 0.5f;
    /** dark picture: people and animals need more proof */
    public boolean poor;
    /** our speed in m/s, NaN when there is no fresh GPS fix */
    public double ego = Double.NaN;
    /** curvature of our own path, 1/m, positive when turning left */
    public double curvature;
    /** half width of the corridor in front of us that counts as our path, metres */
    public static final float PATH = 1.5f, PATH_KEEP = 1.9f;
    private final List<Track> tracks = new ArrayList<>();
    private int nextId = 1, leadId = -1;

    public List<Track> update(List<Detection> dets, long t, LaneState lane) {
        boolean[] used = new boolean[dets.size()];
        for (Track tr : tracks) {
            int best = -1; float bestIou = 0.25f;
            for (int i = 0; i < dets.size(); i++) {
                Detection d = dets.get(i);
                if (used[i] || !sameFamily(tr.label, d.label)) continue;
                float v = iou(tr.left, tr.top, tr.right, tr.bottom, d.left, d.top, d.right, d.bottom);
                if (v > bestIou) { bestIou = v; best = i; }
            }
            if (best < 0) best = nearest(tr, dets, used);
            if (best >= 0) { used[best] = true; apply(tr, dets.get(best), t, 0.65f); }
        }
        for (int i = 0; i < dets.size(); i++) {
            Detection d = dets.get(i);
            if (used[i] || overlapsExisting(d)) continue;
            Track tr = new Track();
            tr.id = nextId++; tr.firstSeen = t; tr.label = d.label;
            apply(tr, d, t, 1f);
            tracks.add(tr);
        }
        for (Iterator<Track> it = tracks.iterator(); it.hasNext(); ) {
            if (t - it.next().lastSeen > 1200) it.remove();
        }
        for (Track tr : tracks) { place(tr, lane); if (tr.isVehicle()) judge(tr, t); }
        selectLead();
        List<Track> out = new ArrayList<>();
        for (Track tr : tracks)
            if (RoadUserFilter.strict(tr.label) ? tr.confirmed : tr.hits >= 2) out.add(copy(tr));
        return out;
    }

    /** Small far vehicles can jump clear of their old box between looks: match by nearness and like size. */
    private int nearest(Track tr, List<Detection> dets, boolean[] used) {
        float w = tr.right - tr.left;
        if (!tr.isVehicle() || w > 0.12f) return -1;
        int best = -1; float bestFar = 1f;
        for (int i = 0; i < dets.size(); i++) {
            Detection d = dets.get(i);
            if (used[i] || !sameFamily(tr.label, d.label)) continue;
            float size = (d.right - d.left) / Math.max(.001f, w);
            if (size < 0.65f || size > 1.55f) continue;
            float dx = ((d.left + d.right) - (tr.left + tr.right)) / 2, dy = ((d.top + d.bottom) - (tr.top + tr.bottom)) / 2;
            float far = (float) Math.hypot(dx / w, dy / Math.max(.001f, tr.bottom - tr.top));
            if (far < bestFar) { bestFar = far; best = i; }
        }
        return best;
    }

    private boolean overlapsExisting(Detection d) {
        for (Track tr : tracks) {
            if (sameFamily(tr.label, d.label)
                    && iou(tr.left, tr.top, tr.right, tr.bottom, d.left, d.top, d.right, d.bottom) > 0.5f) return true;
        }
        return false;
    }

    private void apply(Track tr, Detection d, long t, float k) {
        tr.left += k * (d.left - tr.left); tr.top += k * (d.top - tr.top);
        tr.right += k * (d.right - tr.right); tr.bottom += k * (d.bottom - tr.bottom);
        tr.score = d.score; tr.label = d.label; tr.lastSeen = t; tr.hits++;
        if (!d.lamps) tr.lamps = false;
        tr.scoreSum += d.score; tr.poor = poor;
        if (!tr.confirmed && RoadUserFilter.strict(tr.label))
            tr.confirmed = tr.hits >= 4 && t - tr.firstSeen >= 700
                    && tr.scoreSum / tr.hits >= RoadUserFilter.minScore(tr.label, tr.poor);
        float sec = (t - tr.firstSeen) / 1000f;
        // growth comes from the raw box, never the smoothed one, which lags; boxes cut by the frame are left out
        float h = d.bottom - d.top;
        if (h > 0.004f && d.top > 0.004f && d.bottom < 0.975f) {
            tr.sizes.addLast(new float[]{sec, (float) Math.log(h), (d.left + d.right) / 2});
            while (!tr.sizes.isEmpty() && sec - tr.sizes.peekFirst()[0] > 1.2f) tr.sizes.removeFirst();
            float g = slope(tr.sizes, 1, 4, 0.45f);
            tr.growth = Float.isNaN(g) ? Float.NaN : Float.isNaN(tr.growth) ? g : tr.growth + 0.5f * (g - tr.growth);
        }
        if (tr.isVehicle()) {
            float m = range(tr.label, d.left, d.top, d.right, d.bottom);
            if (Float.isNaN(m)) return;
            tr.distance = Float.isNaN(tr.distance) ? m : tr.distance + 0.35f * (m - tr.distance);
            tr.history.addLast(new float[]{sec, m});
            while (!tr.history.isEmpty() && sec - tr.history.peekFirst()[0] > 2f) tr.history.removeFirst();
            // closing speed from growth is far steadier than differencing noisy distances
            if (!Float.isNaN(tr.growth)) {
                tr.rangeRate = -tr.distance * tr.growth;
                tr.ttc = tr.growth > 0.02f ? 1f / tr.growth : Float.NaN;
            } else {
                tr.rangeRate = slope(tr.history, 1, 6, 0.7f);
                tr.ttc = Float.NaN;
            }
        } else {
            // people, animals and objects: flat-road range from where they touch the ground
            float dy = (tr.bottom - horizon) * aspect;
            if (dy > 0.012f && tr.bottom < 0.97f) {
                float g = focal * cameraHeight / dy;
                tr.distance = Float.isNaN(tr.distance) ? g : tr.distance + 0.4f * (g - tr.distance);
            } else tr.distance = Float.NaN;
        }
    }

    private static float realWidth(String label) {
        return label.equals("truck") ? 2.2f : label.equals("bus") ? 2.55f : label.equals("motorcycle") ? 0.8f : 1.85f;
    }
    private static float realHeight(String label) {
        return label.equals("truck") ? 2.2f : label.equals("bus") ? 3.0f : label.equals("motorcycle") ? 1.3f : 1.55f;
    }

    /**
     * Distance from three independent cues: where the wheels meet the road, the height of the vehicle,
     * and (only when we see its back square on) its width. A vehicle seen at an angle looks wider
     * than it is, so width alone made cars beside us look too close.
     */
    public float range(String label, float l, float t, float r, float b) {
        float w = r - l, h = b - t;
        if (w <= 0.004f) return Float.NaN;
        float[] c = new float[3];
        int n = 0;
        float dy = (b - horizon) * aspect;
        if (dy > 0.012f && b < 0.97f) c[n++] = focal * cameraHeight / dy;
        if (h > 0.004f && t > 0.004f && b < 0.97f) c[n++] = focal * realHeight(label) / (h * aspect);
        boolean square = Math.abs((l + r) / 2 - center) < 0.12f && l > 0.005f && r < 0.995f;
        if (square || n == 0) c[n++] = focal * realWidth(label) / w;
        float m;
        if (n == 3) m = Math.max(Math.min(c[0], c[1]), Math.min(Math.max(c[0], c[1]), c[2]));
        else if (n == 2) m = (c[0] + c[1]) / 2;
        else m = c[0];
        return m > 0 && m < 200f ? m : Float.NaN;
    }

    /** Where the thing is across the road, and whether it is in our path. */
    private void place(Track tr, LaneState lane) {
        float z = tr.distance;
        boolean user = tr.isVehicle() || RoadUserFilter.strict(tr.label);
        if (Float.isNaN(z) || !user) { tr.lateral = tr.pathX = Float.NaN; tr.inPath = false; return; }
        float w = tr.right - tr.left, x = tr.cx();
        if (tr.isVehicle()) {
            // the back of the car is the part nearest us; at an angle we also see its side,
            // which stretches the box towards the middle of the picture, so measure from the outer edge
            float back = Math.min(w, focal * realWidth(tr.label) / z);
            x = x > center ? tr.right - back / 2 : tr.left + back / 2;
        }
        tr.lateral = (x - center) * z / focal;
        tr.pathX = tr.lateral + (float) (0.5 * curvature * z * z);
        boolean keep = tr.id == leadId;
        boolean lanes = lane != null && lane.hasLeft && lane.hasRight && lane.confident
                && !lane.estimatedLeft && !lane.estimatedRight && tr.bottom > horizon + 0.06f && tr.bottom < 0.97f
                && Math.abs(curvature) < 0.004;
        if (lanes) {
            float y = tr.bottom, xl = lane.la + lane.lb * y, xr = lane.ra + lane.rb * y, pad = (keep ? 0.02f : 0.12f) * (xr - xl);
            tr.inPath = xr > xl && x > xl + pad && x < xr - pad;
        } else tr.inPath = Math.abs(tr.pathX) < (keep ? PATH_KEEP : PATH);
    }

    /**
     * Driving, stopped or parked. With us moving, a still object closes at our own speed and one driving
     * our way at almost none; with us stopped, anything that grows, shrinks or slides is moving.
     * A car is only called parked if it was never seen to move and stands outside our path.
     */
    private void judge(Track tr, long t) {
        float dt = tr.judgedAt < 0 ? 0 : Math.min(0.5f, (t - tr.judgedAt) / 1000f);
        tr.judgedAt = t;
        if (tr.lastSeen != t || Double.isNaN(ego) || Float.isNaN(tr.distance)) { decide(tr); return; }
        if (ego > 3 && !Float.isNaN(tr.growth)) {
            double r = tr.distance * tr.growth / ego;
            if (r > 0.6 && r < 1.6) { tr.stillFor += dt; tr.goingFor = Math.max(0, tr.goingFor - dt / 2); }
            else if (r < 0.35 || r > 1.9) { tr.goingFor += dt; tr.stillFor = Math.max(0, tr.stillFor - dt / 2); }
        } else if (ego < 0.5 && tr.sizes.size() >= 4) {
            float g = Float.isNaN(tr.growth) ? 0 : Math.abs(tr.growth);
            float slide = Math.abs(slope(tr.sizes, 2, 4, 0.45f)) / Math.max(0.02f, tr.right - tr.left);
            if (g > 0.12f || slide > 0.35f) { tr.goingFor += dt * 1.5f; tr.stillFor = Math.max(0, tr.stillFor - dt); }
            else tr.stillFor += dt / 2;
        }
        tr.stillFor = Math.min(tr.stillFor, 4); tr.goingFor = Math.min(tr.goingFor, 4);
        if (tr.goingFor > 1.2f) tr.moved = true;
        decide(tr);
    }

    private static void decide(Track tr) {
        if (tr.goingFor > 0.7f && tr.goingFor > tr.stillFor) tr.motion = Track.MOVING;
        else if (tr.stillFor > 0.8f && tr.stillFor > tr.goingFor)
            tr.motion = !tr.moved && !Float.isNaN(tr.pathX) && Math.abs(tr.pathX) > 2.1f ? Track.PARKED : Track.STOPPED;
    }

    private static float slope(Iterable<float[]> pts, int col, int minN, float minSpan) {
        int n = 0; double sx = 0, sy = 0, sxx = 0, sxy = 0; float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
        for (float[] p : pts) {
            n++; sx += p[0]; sy += p[col]; sxx += p[0] * p[0]; sxy += p[0] * p[col];
            min = Math.min(min, p[0]); max = Math.max(max, p[0]);
        }
        double den = n * sxx - sx * sx;
        if (n < minN || max - min < minSpan || den < 1e-9) return Float.NaN;
        return (float) ((n * sxy - sx * sy) / den);
    }

    /** The nearest vehicle in our path that is not parked. */
    private void selectLead() {
        Track best = null;
        for (Track tr : tracks) {
            tr.lead = false;
            if (!tr.isVehicle() || tr.hits < (tr.id == leadId ? 2 : 4) || Float.isNaN(tr.distance) || !tr.inPath || tr.motion == Track.PARKED) continue;
            if (best == null || tr.distance < best.distance) best = tr;
        }
        if (best != null) { best.lead = true; leadId = best.id; } else leadId = -1;
    }

    private static Track copy(Track s) {
        Track c = new Track();
        c.id = s.id; c.label = s.label; c.score = s.score; c.left = s.left; c.top = s.top;
        c.right = s.right; c.bottom = s.bottom; c.firstSeen = s.firstSeen; c.lastSeen = s.lastSeen;
        c.hits = s.hits; c.distance = s.distance; c.rangeRate = s.rangeRate; c.lead = s.lead; c.lateral = s.lateral;
        c.confirmed = s.confirmed; c.motion = s.motion; c.pathX = s.pathX; c.inPath = s.inPath; c.ttc = s.ttc; c.growth = s.growth; c.lamps = s.lamps;
        return c;
    }

    private static boolean sameFamily(String a, String b) {
        return a.equals(b) || (vehicle(a) && vehicle(b));
    }
    private static boolean vehicle(String s) { return s.equals("car") || s.equals("truck") || s.equals("bus"); }

    public static float iou(float al, float at, float ar, float ab, float bl, float bt, float br, float bb) {
        float w = Math.min(ar, br) - Math.max(al, bl), h = Math.min(ab, bb) - Math.max(at, bt);
        if (w <= 0 || h <= 0) return 0;
        float i = w * h;
        return i / ((ar - al) * (ab - at) + (br - bl) * (bb - bt) - i);
    }
}
