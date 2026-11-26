package com.zen.bmwdash.core;

/** Steadies frame-by-frame colour reads: two agreeing reads to change, a short hold when lost. */
public final class SignalState {
    private int shown, candidate, count;
    private long candidateAt, supportedAt;

    /** @param read this frame's colour, or NONE when no light could be read */
    public int update(int read, long t) {
        if (read != SignalColor.NONE) {
            if (read != candidate || t - candidateAt > 1200) count = 0;
            candidate = read;
            candidateAt = t;
            count++;
            if (read == shown) supportedAt = t;
            else if (count >= 2) { shown = read; supportedAt = t; }
        }
        // LED lamps can flicker on camera, so do not drop the colour at once
        if (shown != SignalColor.NONE && t - supportedAt > 1500) shown = SignalColor.NONE;
        return shown;
    }
}
