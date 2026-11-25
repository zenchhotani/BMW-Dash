package com.zen.bmwdash;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.os.SystemClock;

import com.zen.bmwdash.core.ArPath;

import java.util.List;

/** Draws 3D guidance on the road: a route ribbon, flowing arrows and a floating turn sign. */
final class ArRenderer {
    static final class Guide {
        double[][] route;
        double along, speed, heading = Double.NaN, offRoute, turnAlong = Double.NaN;
        long fixTime;
        int direction;
        String label = "";
    }

    private static final float NEAR = 4.5f, HALF = 1.0f;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float d;
    private float focal, aspect, horizon, center, camH, ox, oy, w, h;

    ArRenderer(float density) { d = density; }

    void camera(float focalOverWidth, float frameAspect, float horizonY, float centerX, float height) {
        focal = focalOverWidth; aspect = frameAspect; horizon = horizonY; center = centerX; camH = height;
    }

    private float sx(float x, float z) { return ox + (center + focal * x / z) * w; }
    private float sy(float z, float up) { return oy + (horizon + focal * (camH - up) / z / aspect) * h; }

    void draw(Canvas c, Guide g, float left, float top, float width, float height, int viewW, int viewH) {
        ox = left; oy = top; w = width; h = height;
        long now = SystemClock.elapsedRealtime();
        // glide between GPS fixes
        double along = g.along + g.speed * Math.min(1.5, Math.max(0, (now - g.fixTime) / 1000.0));
        if (g.offRoute > 45) { banner(c, "Return to the route", viewW, viewH, Ui.AMBER); return; }
        double view = ArPath.viewHeading(g.route, along, g.heading);
        if (Math.abs(ArPath.turn(ArPath.bearingAt(g.route, along), view)) > 110) {
            banner(c, "↺  Turn around", viewW, viewH, Ui.AMBER);
            return;
        }
        List<float[]> pts = ArPath.build(g.route, along, view, 130, 2.5);
        ribbon(c, pts);
        arrows(c, pts, (now % 1000) / 1000f);
        if (!Double.isNaN(g.turnAlong)) {
            double to = g.turnAlong - along;
            if (to > -5) {
                sign(c, g, Math.max(0, to), view, along, viewW, viewH);
                if (g.direction != 0 && to < 180) sideChevrons(c, g.direction, viewW, viewH, (now % 700) / 700f);
            }
        }
    }

    /** offsets a ground point sideways from the direction of travel */
    private static float[] side(float[] a, float[] b, float[] at, float off) {
        float dx = b[0] - a[0], dz = b[1] - a[1], len = (float) Math.max(0.01, Math.hypot(dx, dz));
        return new float[]{at[0] + dz / len * off, at[1] - dx / len * off};
    }

    private void ribbon(Canvas c, List<float[]> pts) {
        Path path = new Path();
        for (int i = 0; i + 1 < pts.size(); i++) {
            float[] a = pts.get(i), b = pts.get(i + 1);
            float[] al = side(a, b, a, -HALF), ar = side(a, b, a, HALF), bl = side(a, b, b, -HALF), br = side(a, b, b, HALF);
            if (al[1] < NEAR || ar[1] < NEAR || bl[1] < NEAR || br[1] < NEAR) continue;
            float fade = Math.max(0.12f, 1f - a[2] / 140f);
            path.rewind();
            path.moveTo(sx(al[0], al[1]), sy(al[1], 0)); path.lineTo(sx(bl[0], bl[1]), sy(bl[1], 0));
            path.lineTo(sx(br[0], br[1]), sy(br[1], 0)); path.lineTo(sx(ar[0], ar[1]), sy(ar[1], 0)); path.close();
            p.setShader(null); p.setStyle(Paint.Style.FILL); p.setColor(((int) (120 * fade) << 24) | 0x1C7CFF);
            c.drawPath(path, p);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(3 * d); p.setColor(((int) (255 * fade) << 24) | 0x35F0E0);
            c.drawLine(sx(al[0], al[1]), sy(al[1], 0), sx(bl[0], bl[1]), sy(bl[1], 0), p);
            c.drawLine(sx(ar[0], ar[1]), sy(ar[1], 0), sx(br[0], br[1]), sy(br[1], 0), p);
        }
    }

    private void arrows(Canvas c, List<float[]> pts, float phase) {
        Path path = new Path();
        float spacing = 8;
        for (float at = NEAR + phase * spacing; at < 90; at += spacing) {
            for (int i = 0; i + 1 < pts.size(); i++) {
                float[] a = pts.get(i), b = pts.get(i + 1);
                if (at < a[2] || at >= b[2]) continue;
                float f = (at - a[2]) / (b[2] - a[2]);
                float[] m = {a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f};
                float dx = b[0] - a[0], dz = b[1] - a[1], len = (float) Math.max(0.01, Math.hypot(dx, dz));
                float ux = dx / len, uz = dz / len;
                float[][] shape = {{0, 2.0f}, {0.85f, 0}, {0.35f, 0.15f}, {0, 0.9f}, {-0.35f, 0.15f}, {-0.85f, 0}};
                path.rewind();
                boolean ok = true;
                for (int k = 0; k < shape.length; k++) {
                    float x = m[0] + uz * shape[k][0] + ux * shape[k][1], z = m[1] - ux * shape[k][0] + uz * shape[k][1];
                    if (z < NEAR) { ok = false; break; }
                    if (k == 0) path.moveTo(sx(x, z), sy(z, 0.05f)); else path.lineTo(sx(x, z), sy(z, 0.05f));
                }
                if (!ok) break;
                path.close();
                float fade = Math.max(0.2f, 1f - at / 100f);
                p.setStyle(Paint.Style.FILL); p.setColor(((int) (240 * fade) << 24) | 0xFFFFFF);
                c.drawPath(path, p);
                break;
            }
        }
    }

    private void sign(Canvas c, Guide g, double to, double view, double along, int viewW, int viewH) {
        float x = viewW / 2f, y = viewH * 0.38f, r = 34 * d, ground = Float.NaN;
        if (to < 130) {
            // plant the sign on the road where the turn happens
            List<float[]> at = ArPath.build(g.route, along, view, to + 0.1, Math.max(0.1, to));
            if (at.size() > 1 && at.get(1)[1] > NEAR) {
                float[] t = at.get(1);
                float near = (float) Math.max(0, 1 - to / 130);
                r = (26 + 30 * near) * d;
                x = Math.max(r * 1.6f, Math.min(viewW - r * 1.6f, sx(t[0], t[1])));
                ground = sy(t[1], 0);
                y = Math.max(viewH * 0.34f, Math.min(viewH * 0.52f, sy(t[1], 3.5f)));
            }
        }
        int color = g.direction == 0 ? Ui.BLUE : Ui.VIOLET;
        p.setShader(null);
        if (!Float.isNaN(ground)) {
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(4 * d); p.setColor(0xCCFFFFFF);
            c.drawLine(x, y + r, x, Math.max(y + r, ground), p);
        }
        p.setStyle(Paint.Style.FILL); p.setColor((color & 0x00FFFFFF) | 0x55000000); c.drawCircle(x, y, r * 1.35f, p);
        p.setColor(color); c.drawCircle(x, y, r, p);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(3 * d); p.setColor(0xFFFFFFFF); c.drawCircle(x, y, r, p);
        arrowGlyph(c, x, y, r * .62f, g.direction);
        pill(c, g.label, x, y + r * 1.4f + 14 * d, 15 * d, 0xE60D1322, 0xFFFFFFFF, viewW);
    }

    private void arrowGlyph(Canvas c, float x, float y, float s, int dir) {
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(s * .28f); p.setColor(0xFFFFFFFF);
        p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND);
        Path a = new Path();
        if (dir == 0) {
            a.moveTo(x, y + s * .8f); a.lineTo(x, y - s * .75f);
            a.moveTo(x - s * .55f, y - s * .2f); a.lineTo(x, y - s * .8f); a.lineTo(x + s * .55f, y - s * .2f);
        } else {
            float m = dir;
            a.moveTo(x - m * s * .45f, y + s * .8f); a.lineTo(x - m * s * .45f, y);
            a.quadTo(x - m * s * .45f, y - s * .35f, x, y - s * .35f); a.lineTo(x + m * s * .65f, y - s * .35f);
            a.moveTo(x + m * s * .2f, y - s * .82f); a.lineTo(x + m * s * .72f, y - s * .35f); a.lineTo(x + m * s * .2f, y + s * .12f);
        }
        c.drawPath(a, p);
    }

    private void sideChevrons(Canvas c, int dir, int viewW, int viewH, float phase) {
        float s = 22 * d, y = viewH * .55f;
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(7 * d); p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND);
        for (int i = 0; i < 3; i++) {
            float lit = 1f - Math.abs(phase * 3 - i) / 2f;
            p.setColor(((int) (60 + 195 * Math.max(0, lit)) << 24) | 0x35F0E0);
            float x = dir < 0 ? 18 * d + (2 - i) * s : viewW - 18 * d - (2 - i) * s;
            Path a = new Path();
            a.moveTo(x - dir * s * .35f, y - s); a.lineTo(x + dir * s * .35f, y); a.lineTo(x - dir * s * .35f, y + s);
            c.drawPath(a, p);
        }
    }

    private void banner(Canvas c, String s, int viewW, int viewH, int color) {
        pill(c, s, viewW / 2f, viewH * .45f, 22 * d, color, 0xFF05070D, viewW);
    }

    private void pill(Canvas c, String s, float x, float y, float size, int fill, int text, int viewW) {
        p.setShader(null); p.setStyle(Paint.Style.FILL); p.setTypeface(Typeface.DEFAULT_BOLD); p.setTextSize(size);
        p.setTextAlign(Paint.Align.CENTER);
        float half = p.measureText(s) / 2 + size * .7f;
        x = Math.max(half, Math.min(viewW - half, x));
        p.setColor(fill); c.drawRoundRect(x - half, y - size, x + half, y + size * .6f, size, size, p);
        p.setColor(text); c.drawText(s, x, y + size * .15f, p);
        p.setTextAlign(Paint.Align.LEFT);
    }
}
