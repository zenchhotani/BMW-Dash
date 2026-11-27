package com.zen.bmwdash;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.View;

import com.zen.bmwdash.core.ArPath;
import com.zen.bmwdash.core.LaneState;
import com.zen.bmwdash.core.Pace;
import com.zen.bmwdash.core.Scene;
import com.zen.bmwdash.core.SignalColor;
import com.zen.bmwdash.core.Track;

import java.util.ArrayList;
import java.util.List;

/**
 * 3D View: a drawn scene seen from above and behind our car. It shows only what the front camera
 * measured. It does not know what is beside or behind us, and positions are estimates.
 */
final class RoadView extends View {
    private static final float LANE = 3.6f, FAR = 90;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final float d;
    private final Scene scene = new Scene();
    private List<Track> tracks = new ArrayList<>();
    private ArRenderer.Guide guide;
    private double speed;
    private float offset, travelled;
    private boolean laneSure, departing, leftSide, rightSide, unsure;
    private int light, heat;
    private long fed = -10000, drawn = -1;
    private LinearGradient sky;
    private int skyH;
    private float[] bend;

    RoadView(Context c) { super(c); d = c.getResources().getDisplayMetrics().density; }

    void show(List<Track> t, LaneState l, double egoSpeed, boolean lanesTrusted) {
        long now = SystemClock.elapsedRealtime();
        tracks = t; speed = egoSpeed; fed = now;
        scene.feed(t, now);
        laneSure = lanesTrusted && l != null && l.confident && l.hasLeft && l.hasRight;
        departing = laneSure && l.departing;
        if (laneSure) offset += .3f * (Math.max(-.6f, Math.min(.6f, l.offset)) - offset);
        else offset *= .85f;
        LaneState.LaneType type = laneSure ? l.type : LaneState.LaneType.UNKNOWN;
        // a neighbouring lane is drawn only when the paint says one is there
        leftSide = type == LaneState.LaneType.MIDDLE || type == LaneState.LaneType.RIGHTMOST;
        rightSide = type == LaneState.LaneType.MIDDLE || type == LaneState.LaneType.LEFTMOST;
        invalidate();
    }

    void update(ArRenderer.Guide g, int signal, double egoSpeed, boolean cannotSee, int thermal) {
        guide = g; light = signal; speed = egoSpeed; unsure = cannotSee; heat = thermal;
        invalidate();
    }

    @Override protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        long now = SystemClock.elapsedRealtime();
        if (drawn >= 0) travelled = (travelled + (float) speed * Math.min(200, now - drawn) / 1000f) % 9f;
        drawn = now;
        if (sky == null || skyH != h) {
            skyH = h;
            sky = new LinearGradient(0, 0, 0, h, new int[]{0xFF04060C, 0xFF16223C, 0xFF0A0F1C, 0xFF070A12},
                    new float[]{0, .31f, .38f, 1}, Shader.TileMode.CLAMP);
        }
        p.setStyle(Paint.Style.FILL); p.setShader(sky); c.drawRect(0, 0, w, h, p); p.setShader(null);

        bend = bend(now);
        road(c, w, h);
        if (bend != null) ribbon(c, w, h);
        List<Scene.Thing> things = scene.step(now);
        // a stale picture is worse than an empty one
        if (now - fed > 2000) { things.clear(); tracks = new ArrayList<>(); }
        if (light != SignalColor.NONE) signal(c, w, h);
        stopSign(c, w, h);
        for (Scene.Thing o : things) thing(c, o, w, h);
        own(c, w, h);
        if (unsure || now - fed > 2000) note(c, now - fed > 2000 ? "Waiting for camera" : "Low visibility", w, h);
        boolean fading = false;
        for (Scene.Thing o : things) if (o.alpha < 1 || now - fed < 700) fading = true;
        // redraw only while something is moving, to keep the phone cool
        if (speed > .5 || fading) postInvalidateDelayed(Pace.frameMs(heat));
        else drawn = -1;
    }

    /** sideways position of the road centre every 5 m, from the route; null when not guiding */
    private float[] bend(long now) {
        ArRenderer.Guide g = guide;
        if (g == null || g.offRoute > 45) return null;
        double along = g.along + g.speed * Math.min(1.5, Math.max(0, (now - g.fixTime) / 1000.0));
        double view = ArPath.viewHeading(g.route, along, g.along, g.heading);
        List<float[]> pts = ArPath.build(g.route, along, view, FAR + 15, 2.5);
        float[] out = new float[(int) (FAR / 5) + 1];
        int j = 0;
        float last = 0;
        for (int i = 0; i < out.length; i++) {
            float z = i * 5;
            while (j + 1 < pts.size() && pts.get(j + 1)[1] < z) j++;
            // a sharp turn leaves the picture: hold the last sensible value
            if (j + 1 < pts.size()) {
                float[] a = pts.get(j), b = pts.get(j + 1);
                if (b[1] > a[1] && b[1] >= z) {
                    float x = a[0] + (b[0] - a[0]) * Math.max(0, (z - a[1]) / (b[1] - a[1]));
                    if (Math.abs(x - last) < 4.5f) last = x;
                }
            }
            out[i] = last;
        }
        float start = out[0];
        for (int i = 0; i < out.length; i++) out[i] -= start;
        return out;
    }

    private float curve(float z) {
        if (bend == null || z <= 0) return 0;
        float at = Math.min(bend.length - 1.001f, z / 5);
        int i = (int) at;
        return bend[i] + (bend[i + 1] - bend[i]) * (at - i);
    }

    /** a strip on the ground between two sideways offsets from the road centre */
    private void strip(Canvas c, int w, int h, float x0, float x1, float from, float to, int color) {
        path.rewind();
        int n = Math.max(1, (int) Math.ceil((to - from) / 5));
        for (int i = 0; i <= n; i++) {
            float z = from + (to - from) * i / n;
            float[] s = Scene.project(x0 + curve(z), z, 0, w, h);
            if (s == null) return;
            if (i == 0) path.moveTo(s[0], s[1]); else path.lineTo(s[0], s[1]);
        }
        for (int i = n; i >= 0; i--) {
            float z = from + (to - from) * i / n;
            float[] s = Scene.project(x1 + curve(z), z, 0, w, h);
            if (s == null) return;
            path.lineTo(s[0], s[1]);
        }
        path.close();
        p.setStyle(Paint.Style.FILL); p.setColor(color); c.drawPath(path, p);
    }

    private void road(Canvas c, int w, int h) {
        float mid = -offset * LANE;
        float left = mid - LANE / 2 - (leftSide ? LANE : 0), right = mid + LANE / 2 + (rightSide ? LANE : 0);
        strip(c, w, h, left - .5f, right + .5f, -6, FAR, 0xFF1B2233);
        if (laneSure) strip(c, w, h, mid - LANE / 2, mid + LANE / 2, -6, FAR, departing ? 0x33FF3B5C : 0x181C9BFF);
        // solid where the camera sees the lane, faint dashes where it does not
        for (int side = -1; side <= 1; side += 2) {
            float x = mid + side * LANE / 2;
            if (laneSure) strip(c, w, h, x - .08f, x + .08f, -6, FAR, departing ? Ui.RED : 0xFFE9F0FF);
            else dashes(c, w, h, x, 0x55AAB4C8);
        }
        if (leftSide) strip(c, w, h, left - .07f, left + .07f, -6, FAR, 0x88C9D3EA);
        if (rightSide) strip(c, w, h, right - .07f, right + .07f, -6, FAR, 0x88C9D3EA);
        // marks that slide past give the sense of speed
        for (float z = 9 - travelled; z < 60; z += 9) {
            strip(c, w, h, left - .8f, left - .6f, z, z + 1.5f, 0x44FFFFFF);
            strip(c, w, h, right + .6f, right + .8f, z, z + 1.5f, 0x44FFFFFF);
        }
    }

    private void dashes(Canvas c, int w, int h, float x, int color) {
        for (float z = 9 - travelled - 9; z < 60; z += 9) strip(c, w, h, x - .06f, x + .06f, z, z + 4, color);
    }

    private void ribbon(Canvas c, int w, int h) {
        float mid = -offset * LANE;
        strip(c, w, h, mid - .75f, mid + .75f, 3, FAR, 0x881C7CFF);
        strip(c, w, h, mid - .8f, mid - .68f, 3, FAR, 0xCC35F0E0);
        strip(c, w, h, mid + .68f, mid + .8f, 3, FAR, 0xCC35F0E0);
    }

    private static int shade(int color, float f, int alpha) {
        int r = Math.min(255, (int) (((color >> 16) & 255) * f)), g = Math.min(255, (int) (((color >> 8) & 255) * f)),
                b = Math.min(255, (int) ((color & 255) * f));
        return (alpha << 24) | (r << 16) | (g << 8) | b;
    }

    private void face(Canvas c, float[][] q, int color) {
        for (float[] v : q) if (v == null) return;
        path.rewind(); path.moveTo(q[0][0], q[0][1]);
        for (int i = 1; i < q.length; i++) path.lineTo(q[i][0], q[i][1]);
        path.close();
        p.setStyle(Paint.Style.FILL); p.setColor(color); c.drawPath(path, p);
    }

    /** a box standing on the ground, narrower at the top when taper is below 1 */
    private void box(Canvas c, int w, int h, float x, float z, float width, float length, float y0, float y1, float taper, int color, int alpha) {
        float a = width / 2, b = a * taper, z1 = z + length, tz0 = z + length * (1 - taper) * .8f, tz1 = z1 - length * (1 - taper) * .5f;
        float[] nl = Scene.project(x - a, z, y0, w, h), nr = Scene.project(x + a, z, y0, w, h),
                fl = Scene.project(x - a, z1, y0, w, h), fr = Scene.project(x + a, z1, y0, w, h),
                tnl = Scene.project(x - b, tz0, y1, w, h), tnr = Scene.project(x + b, tz0, y1, w, h),
                tfl = Scene.project(x - b, tz1, y1, w, h), tfr = Scene.project(x + b, tz1, y1, w, h);
        if (x > width / 2) face(c, new float[][]{nl, fl, tfl, tnl}, shade(color, .62f, alpha));
        if (x < -width / 2) face(c, new float[][]{nr, fr, tfr, tnr}, shade(color, .62f, alpha));
        face(c, new float[][]{tnl, tnr, tfr, tfl}, shade(color, 1.15f, alpha));
        face(c, new float[][]{nl, nr, tnr, tnl}, shade(color, .85f, alpha));
    }

    private void shadow(Canvas c, int w, int h, float x, float z, float width, float length, int color) {
        face(c, new float[][]{Scene.project(x - width * .65f, z - .3f, 0, w, h), Scene.project(x + width * .65f, z - .3f, 0, w, h),
                Scene.project(x + width * .65f, z + length + .3f, 0, w, h), Scene.project(x - width * .65f, z + length + .3f, 0, w, h)}, color);
    }

    private void vehicle(Canvas c, int w, int h, float x, float z, String kind, int body, int alpha, boolean braking) {
        float wide = 1.85f, len = 4.4f;
        if (kind.equals("truck")) { wide = 2.4f; len = 6.5f; } else if (kind.equals("bus")) { wide = 2.55f; len = 10f; }
        else if (kind.equals("motorcycle")) { wide = .7f; len = 2f; }
        box(c, w, h, x, z, wide, len, .3f, kind.equals("truck") || kind.equals("bus") ? 2.7f : .95f, .96f, body, alpha);
        if (kind.equals("car")) box(c, w, h, x, z + .7f, wide * .9f, len * .55f, .95f, 1.55f, .72f, shade(body, .7f, 255), alpha);
        int tail = braking ? 0xFF2040 : 0xB02038;
        for (int s = -1; s <= 1; s += 2) {
            float[] t = Scene.project(x + s * wide * .34f, z - .02f, .75f, w, h);
            if (t == null) continue;
            p.setStyle(Paint.Style.FILL); p.setColor((alpha << 24) | tail);
            float rw = t[2] * wide * .11f, rh = t[2] * .09f;
            c.drawRoundRect(t[0] - rw, t[1] - rh, t[0] + rw, t[1] + rh, rh, rh, p);
        }
    }

    private void thing(Canvas c, Scene.Thing o, int w, int h) {
        float x = o.x, z = Math.max(o.z, 2.5f);
        int alpha = (int) (255 * Math.max(0, Math.min(1, o.alpha)));
        if (o.label.equals("car") || o.label.equals("truck") || o.label.equals("bus") || o.label.equals("motorcycle")) {
            int body = 0xFF8C97B0;
            if (o.lead) {
                double gap = speed > 3 ? o.z / speed : 99;
                body = gap < 1.2 ? Ui.RED : gap < 2 ? Ui.AMBER : Ui.BLUE;
            }
            if (o.motion == Track.PARKED) body = 0xFF566076;
            shadow(c, w, h, x, z, 1.85f, 4.4f, (alpha / 3) << 24);
            vehicle(c, w, h, x, z, o.label, body, alpha, o.motion == Track.STOPPED);
            if (o.lead) tag(c, Scene.project(x, z, 2.9f, w, h), Ui.range(o.z, mph), body);
        } else if (o.label.equals("person") || o.label.equals("bicycle")) {
            shadow(c, w, h, x, z, .8f, .6f, (alpha / 3) << 24);
            box(c, w, h, x, z, .55f, .4f, 0, 1.35f, .8f, Ui.AMBER, alpha);
            float[] head = Scene.project(x, z + .2f, 1.65f, w, h);
            if (head != null) { p.setStyle(Paint.Style.FILL); p.setColor((alpha << 24) | (Ui.AMBER & 0xFFFFFF)); c.drawCircle(head[0], head[1], head[2] * .25f, p); }
        } else {
            // animals and objects share a plain marker with their name
            box(c, w, h, x, z, .9f, .9f, 0, .8f, .7f, Ui.AMBER, alpha);
            tag(c, Scene.project(x, z, 1.5f, w, h), o.label.toUpperCase(java.util.Locale.US), Ui.AMBER);
        }
    }

    boolean mph = true;

    private void own(Canvas c, int w, int h) {
        shadow(c, w, h, 0, -3.4f, 1.9f, 4.4f, 0x55000000);
        vehicle(c, w, h, 0, -3.4f, "car", 0xFFE8EEFA, 255, false);
    }

    private void signal(Canvas c, int w, int h) {
        float z = 24, x = LANE * 1.75f + curve(z);
        float[] foot = Scene.project(x, z, 0, w, h), head = Scene.project(x, z, 4.6f, w, h);
        if (foot == null || head == null) return;
        float k = head[2];
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(k * .15f); p.setColor(0xFF59647E); c.drawLine(foot[0], foot[1], head[0], head[1], p);
        p.setStyle(Paint.Style.FILL); p.setColor(0xFF10141F);
        c.drawRoundRect(head[0] - k * .45f, head[1] - k * 1.35f, head[0] + k * .45f, head[1] + k * 1.35f, k * .2f, k * .2f, p);
        int[] lit = {Ui.RED, Ui.AMBER, Ui.GREEN};
        for (int i = 0; i < 3; i++) {
            p.setColor(light == i + 1 ? lit[i] : 0xFF2A3042);
            c.drawCircle(head[0], head[1] + (i - 1) * k * .82f, k * .32f, p);
        }
    }

    private void stopSign(Canvas c, int w, int h) {
        for (Track t : tracks) {
            if (!t.label.equals("stop sign") || t.cx() < .35f) continue;
            float z = 18, x = LANE * 1.65f + curve(z);
            float[] foot = Scene.project(x, z, 0, w, h), s = Scene.project(x, z, 2.4f, w, h);
            if (foot == null || s == null) return;
            float r = s[2] * .75f;
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(s[2] * .12f); p.setColor(0xFF59647E); c.drawLine(foot[0], foot[1], s[0], s[1], p);
            path.rewind();
            for (int i = 0; i < 8; i++) {
                double a = Math.PI / 8 + i * Math.PI / 4;
                float px = s[0] + (float) Math.cos(a) * r, py = s[1] + (float) Math.sin(a) * r;
                if (i == 0) path.moveTo(px, py); else path.lineTo(px, py);
            }
            path.close();
            p.setStyle(Paint.Style.FILL); p.setColor(Ui.RED); c.drawPath(path, p);
            text(c, "STOP", s[0], s[1] + r * .22f, r * .62f, 0xFFFFFFFF);
            return;
        }
    }

    private void tag(Canvas c, float[] at, String s, int color) {
        if (at == null) return;
        float size = 13 * d;
        p.setTextSize(size); p.setTypeface(Typeface.DEFAULT_BOLD);
        float half = p.measureText(s) / 2 + 8 * d;
        p.setStyle(Paint.Style.FILL); p.setColor(0xDD0D1322); c.drawRoundRect(at[0] - half, at[1] - size, at[0] + half, at[1] + size * .5f, size, size, p);
        text(c, s, at[0], at[1] + size * .1f, size, color);
    }

    private void note(Canvas c, String s, int w, int h) { tag(c, new float[]{w * .16f, h * .52f}, s, Ui.AMBER); }

    private void text(Canvas c, String s, float x, float y, float size, int color) {
        p.setStyle(Paint.Style.FILL); p.setTypeface(Typeface.DEFAULT_BOLD); p.setTextSize(size);
        p.setTextAlign(Paint.Align.CENTER); p.setColor(color); c.drawText(s, x, y, p);
        p.setTextAlign(Paint.Align.LEFT);
    }
}
