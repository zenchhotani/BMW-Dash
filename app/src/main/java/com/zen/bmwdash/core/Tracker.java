package com.zen.bmwdash.core;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** IoU tracker with monocular range estimation and lead-vehicle selection. */
public final class Tracker {
    /** focal length divided by frame width (pinhole model) */
    public float focal = 0.65f;
    /** frame height / width */
    public float aspect = 9f / 16f;
    /** camera height above road, metres */
    public float cameraHeight = 1.25f;
    public float horizon = 0.5f, center = 0.5f;
    private final List<Track> tracks = new ArrayList<>();
    private int nextId = 1, leadId = -1;
    private long lastT;

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
            if (t - it.next().lastSeen > 700) it.remove();
        }
        selectLead(lane);
        lastT = t;
        List<Track> out = new ArrayList<>();
        for (Track tr : tracks) if (tr.hits >= 2) out.add(copy(tr));
        return out;
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
        if (!tr.isVehicle()) {
            // people, animals and objects: flat-road range from where they touch the ground
            float dy = (tr.bottom - horizon) * aspect;
            if (dy > 0.012f && tr.bottom < 0.9f) {
                float g = focal * cameraHeight / dy;
                tr.distance = Float.isNaN(tr.distance) ? g : tr.distance + 0.4f * (g - tr.distance);
                tr.lateral = (tr.cx() - center) * tr.distance / focal;
            } else { tr.distance = Float.NaN; tr.lateral = Float.NaN; }
            return;
        }
        float m = range(tr);
        if (Float.isNaN(m)) return;
        tr.distance = Float.isNaN(tr.distance) ? m : tr.distance + 0.35f * (m - tr.distance);
        tr.history.addLast(new float[]{(t - tr.firstSeen) / 1000f, m});
        float now = (t - tr.firstSeen) / 1000f;
        while (!tr.history.isEmpty() && now - tr.history.peekFirst()[0] > 2f) tr.history.removeFirst();
        tr.rangeRate = slope(tr.history);
    }

    /** Range from vehicle width, blended with the flat-road estimate when the two agree. */
    public float range(Track tr) {
        float width = tr.right - tr.left;
        if (width <= 0.005f) return Float.NaN;
        float real = tr.label.equals("truck") ? 2.4f : tr.label.equals("bus") ? 2.55f
                : tr.label.equals("motorcycle") ? 0.8f : 1.85f;
        boolean clipped = tr.left < 0.005f || tr.right > 0.995f;
        float byWidth = clipped ? Float.NaN : focal * real / width;
        float dy = (tr.bottom - horizon) * aspect;
        float byGround = (dy > 0.012f && tr.bottom < 0.9f) ? focal * cameraHeight / dy : Float.NaN;
        float m;
        if (Float.isNaN(byWidth)) m = byGround;
        else if (!Float.isNaN(byGround) && Math.abs(byGround - byWidth) < 0.4f * byWidth) m = 0.6f * byWidth + 0.4f * byGround;
        else m = byWidth;
        return (!Float.isNaN(m) && m < 200f) ? m : Float.NaN;
    }

    private static float slope(Iterable<float[]> pts) {
        int n = 0; double sx = 0, sy = 0, sxx = 0, sxy = 0; float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
        for (float[] p : pts) {
            n++; sx += p[0]; sy += p[1]; sxx += p[0] * p[0]; sxy += p[0] * p[1];
            min = Math.min(min, p[0]); max = Math.max(max, p[0]);
        }
        double den = n * sxx - sx * sx;
        if (n < 6 || max - min < 0.7f || den < 1e-9) return Float.NaN;
        return (float) ((n * sxy - sx * sy) / den);
    }

    private void selectLead(LaneState lane) {
        Track best = null;
        for (Track tr : tracks) {
            tr.lead = false;
            if (!tr.isVehicle() || tr.hits < 3 || Float.isNaN(tr.distance)) continue;
            boolean inLane;
            if (lane != null && lane.hasLeft && lane.hasRight && tr.bottom > horizon + 0.07f) {
                float y = Math.min(tr.bottom, 0.95f);
                float xl = lane.la + lane.lb * y, xr = lane.ra + lane.rb * y, pad = 0.08f * (xr - xl);
                inLane = tr.cx() > xl + pad && tr.cx() < xr - pad;
            } else {
                float lateral = (tr.cx() - center) * tr.distance / focal;
                inLane = Math.abs(lateral) < (tr.id == leadId ? 1.9f : 1.4f);
            }
            if (inLane && (best == null || tr.distance < best.distance)) best = tr;
        }
        if (best != null) { best.lead = true; leadId = best.id; } else leadId = -1;
    }

    private static Track copy(Track s) {
        Track c = new Track();
        c.id = s.id; c.label = s.label; c.score = s.score; c.left = s.left; c.top = s.top;
        c.right = s.right; c.bottom = s.bottom; c.firstSeen = s.firstSeen; c.lastSeen = s.lastSeen;
        c.hits = s.hits; c.distance = s.distance; c.rangeRate = s.rangeRate; c.lead = s.lead; c.lateral = s.lateral;
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
