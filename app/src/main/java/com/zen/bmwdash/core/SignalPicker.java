package com.zen.bmwdash.core;

import java.util.List;

/** Chooses the one traffic light that most likely governs our lane: central, overhead and nearest. */
public final class SignalPicker {
    private int lastId = -1;

    /** @return the chosen light, or null. Marks it with Track.ours. */
    public Track pick(List<Track> tracks, float center, float horizon, int frameWidth) {
        Track best = null;
        float bestScore = 0;
        for (Track k : tracks) {
            k.ours = false;
            if (!k.label.equals("traffic light")) continue;
            float width = k.right - k.left, offset = Math.abs(k.cx() - center);
            // off to the side belongs to another road; near or under the horizon is a post lamp or tail light
            if (offset > 0.2f || k.bottom > horizon - 0.01f || width * frameWidth < 8) continue;
            float score = width * (1f - 3.5f * offset) * (k.id == lastId ? 1.35f : 1f);
            if (score > bestScore) { bestScore = score; best = k; }
        }
        if (best != null) { best.ours = true; lastId = best.id; }
        return best;
    }
}
