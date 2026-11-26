package com.zen.bmwdash.core;

/**
 * Rejects false people, animals and objects. Night rain is full of glare, reflections and lit signs
 * that the general detector mistakes for them, so each hit must look right and keep being seen.
 */
public final class RoadUserFilter {
    private RoadUserFilter() { }

    /** true for the classes that need extra proof; vehicles, lights and signs do not */
    public static boolean strict(String label) {
        return person(label) || animal(label) || label.equals("suitcase") || label.equals("chair")
                || label.equals("backpack") || label.equals("bench");
    }
    private static boolean person(String s) { return s.equals("person") || s.equals("bicycle"); }
    private static boolean animal(String s) {
        return s.equals("dog") || s.equals("cat") || s.equals("horse") || s.equals("cow") || s.equals("sheep") || s.equals("bear");
    }

    /** lowest average confidence accepted; poor = dark, where the detector guesses more */
    public static float minScore(String label, boolean poor) {
        float base = person(label) ? 0.50f : 0.58f;
        return poor ? base + 0.08f : base;
    }

    /** Is the box a believable size, shape and place? aspect = frame height / width. */
    public static boolean plausible(Detection d, float horizon, float aspect, boolean poor) {
        if (!strict(d.label)) return true;
        // single frames may dip a little below the average the track has to reach
        if (d.score < minScore(d.label, poor) - 0.06f) return false;
        float w = d.right - d.left, h = d.bottom - d.top;
        if (w <= 0 || h <= 0) return false;
        // it has to be standing on something below the horizon
        if (d.bottom < horizon + 0.02f) return false;
        float tall = h * aspect / w;
        if (person(d.label)) {
            float least = d.label.equals("person") ? 1.3f : 0.7f;
            return w <= 0.22f && h >= 0.05f && tall >= least && tall <= 5f;
        }
        return w <= 0.3f && h <= 0.45f && tall >= 0.3f && tall <= 2.2f;
    }

    /** true when much of the box is blown-out light: headlights, lamps or their reflections. */
    public static boolean glare(int[] argb, int width, int height, Detection d) {
        int x0 = Math.max(0, (int) (d.left * width)), x1 = Math.min(width, (int) Math.ceil(d.right * width));
        int y0 = Math.max(0, (int) (d.top * height)), y1 = Math.min(height, (int) Math.ceil(d.bottom * height));
        int n = 0, hot = 0;
        for (int y = y0; y < y1; y++)
            for (int x = x0; x < x1; x++) {
                int p = argb[y * width + x];
                int lum = (((p >> 16) & 255) * 77 + ((p >> 8) & 255) * 150 + (p & 255) * 29) >> 8;
                n++;
                if (lum > 232) hot++;
            }
        return n > 0 && hot > n * 0.18f;
    }
}
