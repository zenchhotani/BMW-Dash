package com.zen.bmwdash.core;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Turns text the camera read into road signs. Needs two agreeing reads before reporting, except STOP: one clear read is enough. */
public final class SignReader {
    public static final class Line {
        final String text; final float left, top, right, bottom;
        public Line(String text, float left, float top, float right, float bottom) {
            this.text = text.toUpperCase(java.util.Locale.US).trim();
            this.left = left; this.top = top; this.right = right; this.bottom = bottom;
        }
    }
    public static final class Sign {
        public final String code, text;
        /** posted number for speed limit signs, otherwise 0 */
        public final int limit;
        Sign(String code, String text, int limit) { this.code = code; this.text = text; this.limit = limit; }
    }

    private static final String[][] PHRASES = {
            {"WRONG WAY", "wrong", "Wrong way sign"}, {"DO NOT ENTER", "noentry", "Do not enter"},
            {"ROAD CLOSED", "closed", "Road closed"}, {"DETOUR", "detour", "Detour"},
            {"SCHOOL", "school", "School zone"}, {"ROAD WORK", "work", "Road work ahead"},
            {"WORK ZONE", "work", "Work zone"}, {"WORK AHEAD", "work", "Road work ahead"},
            {"NO TURN ON RED", "noturnred", "No turn on red"}, {"LANE ENDS", "laneends", "Lane ends"},
            {"LANE CLOSED", "laneclosed", "Lane closed"}, {"YIELD", "yield", "Yield"},
            {"ONE WAY", "oneway", "One way"}, {"MERGE", "merge", "Merging traffic"},
            {"NO U TURN", "nouturn", "No U turn"}, {"PED XING", "ped", "Pedestrian crossing"}};
    private static final Pattern NUMBER = Pattern.compile("(?<![0-9])([1-8][05]|5)(?![0-9])");

    private final java.util.Map<String, Long> seen = new java.util.HashMap<>();

    public List<Sign> read(List<Line> lines, long t) {
        List<Sign> found = new ArrayList<>();
        StringBuilder all = new StringBuilder();
        for (Line l : lines) all.append(l.text.replaceAll("[^A-Z0-9 ]", " ").replaceAll("\\s+", " ")).append(' ');
        String text = " " + all.toString().replaceAll("\\s+", " ");
        boolean school = text.contains(" SCHOOL ");
        for (Line l : lines) {
            if (!l.text.contains("LIMIT")) continue;
            String above = "", number = null;
            float h = Math.max(.001f, l.bottom - l.top), w = l.right - l.left;
            for (Line o : lines) {
                boolean aligned = o.right > l.left - w * .3f && o.left < l.right + w * .3f;
                if (!aligned) continue;
                if (o.bottom <= l.top + h && l.top - o.bottom < h * 1.5f && o != l) above += o.text;
                if (o == l || (o.top >= l.top && o.top - l.bottom < h * 2.5f)) {
                    Matcher m = NUMBER.matcher(o.text);
                    if (number == null && m.find()) number = m.group(1);
                }
            }
            if (number != null && (l.text.contains("SPEED") || above.contains("SPEED")) && !school)
                found.add(new Sign("limit", "Speed limit " + number, Integer.parseInt(number)));
        }
        for (Line l : lines) if (l.text.equals("STOP")) { found.add(new Sign("stop", "Stop sign", 0)); break; }
        for (String[] p : PHRASES) if (text.contains(" " + p[0] + " ")) found.add(new Sign(p[1], p[2], 0));

        List<Sign> sure = new ArrayList<>();
        for (Sign s : found) {
            String key = s.code + s.limit;
            Long before = seen.put(key, t);
            if (s.code.equals("stop") || (before != null && t - before < 4000 && t > before)) sure.add(s);
        }
        return sure;
    }
}
