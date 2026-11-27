package com.zen.bmwdash.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** The road users around us as smoothly moving things on flat ground, for the 3D view. */
public final class Scene {
    /** the virtual camera sits this far behind and above our car, metres */
    public static final float BACK = 15f, UP = 5.6f;
    private static final long HOLD = 600;

    public static final class Thing {
        public int id;
        public String label;
        /** metres right of us and ahead of us */
        public float x, z;
        /** 0..1, fades in when first seen and out when lost */
        public float alpha;
        public boolean lead;
        public int motion;
        float toX, toZ;
        long seen;
    }

    private final Map<Integer, Thing> things = new HashMap<>();
    private long stepped = -1;

    /** Takes the latest tracks. Only things with a measured position are placed: nothing is invented. */
    public void feed(List<Track> tracks, long t) {
        for (Track k : tracks) {
            boolean person = k.label.equals("person") || k.label.equals("bicycle") || k.isAnimal() || k.isObstacle();
            if (!k.isVehicle() && !person) continue;
            if (person && !k.confirmed) continue;
            if (Float.isNaN(k.distance) || Float.isNaN(k.lateral) || k.distance > 90 || Math.abs(k.lateral) > 14) continue;
            Thing o = things.get(k.id);
            if (o == null) {
                things.put(k.id, o = new Thing());
                o.id = k.id; o.x = k.lateral; o.z = k.distance;
            }
            o.label = k.label; o.toX = k.lateral; o.toZ = k.distance; o.lead = k.lead; o.motion = k.motion; o.seen = t;
        }
    }

    /** Moves everything towards where it was last measured. @return farthest first, ready to draw */
    public List<Thing> step(long t) {
        float dt = stepped < 0 ? 0 : Math.min(500, t - stepped) / 1000f;
        stepped = t;
        float glide = 1 - (float) Math.exp(-dt * 7);
        List<Thing> out = new ArrayList<>();
        for (Iterator<Thing> it = things.values().iterator(); it.hasNext(); ) {
            Thing o = it.next();
            o.x += (o.toX - o.x) * glide; o.z += (o.toZ - o.z) * glide;
            if (t - o.seen > HOLD) o.alpha -= dt * 3; else o.alpha = Math.min(1, o.alpha + Math.max(.34f, dt * 5));
            if (o.alpha <= 0) it.remove(); else out.add(o);
        }
        Collections.sort(out, (a, b) -> Float.compare(b.z, a.z));
        return out;
    }

    public void clear() { things.clear(); stepped = -1; }

    /**
     * Screen position of a point x metres right, z metres ahead and y metres up.
     * @return {screen x, screen y, pixels per metre there}, or null when behind the camera
     */
    public static float[] project(float x, float z, float y, int w, int h) {
        float depth = z + BACK;
        if (depth < 1.5f) return null;
        float f = Math.min(w * .9f, h * 1.45f), k = f / depth;
        return new float[]{w / 2f + x * k, h * .30f + (UP - y) * k, k};
    }
}
