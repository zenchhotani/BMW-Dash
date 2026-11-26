package com.zen.bmwdash.core;

/** Notices when the camera cannot see: drops, fog, snow or dirt on the glass, or heavy weather. */
public final class ViewQuality {
    private static final int COLS = 24, ROWS = 12;
    private boolean blocked;
    private long since = -1;

    /**
     * @param gray       w*h luminance
     * @param brightness average scene brightness 0..255; dark scenes are not judged
     * @param bottom     lowest usable row (above the hood), 0..1
     */
    public boolean update(int[] gray, int w, int h, float brightness, float bottom, long t) {
        if (brightness < 45) { since = -1; return blocked = false; }
        int y0 = (int) (h * .15f), y1 = Math.max(y0 + ROWS, (int) (h * bottom));
        int sharp = 0;
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                int edges = 0;
                for (int y = y0 + (y1 - y0) * r / ROWS; y < y0 + (y1 - y0) * (r + 1) / ROWS; y += 2)
                    for (int x = Math.max(2, w * c / COLS); x < w * (c + 1) / COLS; x++)
                        if (Math.abs(gray[y * w + x] - gray[y * w + x - 2]) >= 20) edges++;
                if (edges >= 3) sharp++;
            }
        }
        float share = sharp / (float) (COLS * ROWS);
        // slow to change either way, so wipers and single splashes do not trigger it
        boolean other = blocked ? share > .14f : share < .07f;
        if (!other) since = -1;
        else if (since < 0) since = t;
        else if (t - since > (blocked ? 2000 : 6000)) { blocked = !blocked; since = -1; }
        return blocked;
    }
}
