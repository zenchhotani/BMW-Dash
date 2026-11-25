package com.zen.bmwdash;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;

import com.zen.bmwdash.core.LaneState;
import com.zen.bmwdash.core.Track;

import java.util.ArrayList;
import java.util.List;

/** Augmented-reality layer drawn over the camera preview. */
final class OverlayView extends View {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float d;
    private List<Track> tracks = new ArrayList<>();
    private LaneState lane;
    private int frameW = 16, frameH = 9;
    private double ego;
    private boolean mph = true, calibrating;
    private float horizon = .5f, center = .5f, hood = .88f;

    OverlayView(Context c) { super(c); d = c.getResources().getDisplayMetrics().density; }

    void show(List<Track> t, LaneState l, int w, int h, double egoSpeed, boolean useMph) {
        tracks = t; lane = l; frameW = w; frameH = h; ego = egoSpeed; mph = useMph; invalidate();
    }
    void calibration(boolean on, float hz, float cx, float hd) {
        calibrating = on; horizon = hz; center = cx; hood = hd; invalidate();
    }

    @Override protected void onDraw(Canvas c) {
        // same centre-crop mapping as PreviewView.FILL_CENTER
        float s = Math.max(getWidth() / (float) frameW, getHeight() / (float) frameH);
        float w = frameW * s, h = frameH * s, ox = (getWidth() - w) / 2, oy = (getHeight() - h) / 2;
        if (lane != null) drawLane(c, ox, oy, w, h);
        for (Track t : tracks) drawTrack(c, t, new RectF(ox + t.left * w, oy + t.top * h, ox + t.right * w, oy + t.bottom * h));
        if (calibrating) {
            p.setShader(null); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2 * d);
            p.setColor(Ui.AMBER);
            c.drawLine(0, oy + horizon * h, getWidth(), oy + horizon * h, p);
            c.drawLine(ox + center * w, oy + horizon * h, ox + center * w, getHeight(), p);
            p.setColor(Ui.CYAN);
            c.drawLine(0, oy + hood * h, getWidth(), oy + hood * h, p);
        }
    }

    private void drawLane(Canvas c, float ox, float oy, float w, float h) {
        float y0 = Math.min(horizon + .10f, .8f), y1 = Math.max(hood, y0 + .1f);
        int color = lane.departing ? Ui.RED : lane.position == LaneState.Position.CENTERED ? Ui.CYAN : Ui.AMBER;
        if (lane.hasLeft && lane.hasRight) {
            Path path = new Path();
            path.moveTo(ox + (lane.la + lane.lb * y0) * w, oy + y0 * h);
            path.lineTo(ox + (lane.ra + lane.rb * y0) * w, oy + y0 * h);
            path.lineTo(ox + (lane.ra + lane.rb * y1) * w, oy + y1 * h);
            path.lineTo(ox + (lane.la + lane.lb * y1) * w, oy + y1 * h);
            path.close();
            p.setStyle(Paint.Style.FILL);
            p.setShader(new LinearGradient(0, oy + y0 * h, 0, oy + y1 * h,
                    color & 0x00FFFFFF, (color & 0x00FFFFFF) | 0x70000000, Shader.TileMode.CLAMP));
            c.drawPath(path, p);
            p.setShader(null);
            // flowing chevrons
            float phase = (System.currentTimeMillis() % 900) / 900f;
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(4 * d); p.setStrokeCap(Paint.Cap.ROUND);
            for (int i = 0; i < 4; i++) {
                float f = (i + (ego > 1 ? phase : 0)) / 4f, y = y0 + (y1 - y0) * f * f;
                float xl = lane.la + lane.lb * y, xr = lane.ra + lane.rb * y, m = (xl + xr) / 2, q = (xr - xl) * .12f;
                p.setColor((color & 0x00FFFFFF) | ((int) (60 + 160 * f) << 24));
                c.drawLine(ox + (m - q) * w, oy + (y + .025f * f) * h, ox + m * w, oy + y * h, p);
                c.drawLine(ox + m * w, oy + y * h, ox + (m + q) * w, oy + (y + .025f * f) * h, p);
            }
        }
        p.setStyle(Paint.Style.STROKE); p.setStrokeCap(Paint.Cap.ROUND);
        for (int side = 0; side < 2; side++) {
            if (side == 0 ? !lane.hasLeft : !lane.hasRight) continue;
            float a = side == 0 ? lane.la : lane.ra, b = side == 0 ? lane.lb : lane.rb;
            boolean yellow = side == 0 ? lane.leftYellow : lane.rightYellow;
            int col = yellow ? Ui.AMBER : color;
            float x0 = ox + (a + b * y0) * w, x1 = ox + (a + b * y1) * w;
            p.setColor((col & 0x00FFFFFF) | 0x50000000); p.setStrokeWidth(12 * d);
            c.drawLine(x0, oy + y0 * h, x1, oy + y1 * h, p);
            p.setColor(col); p.setStrokeWidth(3.5f * d);
            c.drawLine(x0, oy + y0 * h, x1, oy + y1 * h, p);
        }
    }

    private void drawTrack(Canvas c, Track t, RectF r) {
        boolean vehicle = t.isVehicle();
        int color = Ui.BLUE;
        if (t.label.equals("person") || t.label.equals("bicycle")) color = Ui.AMBER;
        else if (t.label.equals("traffic light") || t.label.equals("stop sign")) color = Ui.VIOLET;
        else if (t.lead) {
            double gap = ego > 3 && !Float.isNaN(t.distance) ? t.distance / ego : 99;
            color = gap < 1.2 ? Ui.RED : gap < 2 ? Ui.AMBER : Ui.GREEN;
        }
        float k = Math.max(8 * d, Math.min(r.width(), r.height()) * .24f);
        p.setShader(null); p.setStyle(Paint.Style.STROKE); p.setStrokeCap(Paint.Cap.ROUND);
        for (int pass = 0; pass < 2; pass++) {
            p.setStrokeWidth((pass == 0 ? 9 : t.lead ? 3.5f : 2) * d);
            p.setColor(pass == 0 ? (color & 0x00FFFFFF) | 0x40000000 : color);
            corner(c, r.left, r.top, k, k); corner(c, r.right, r.top, -k, k);
            corner(c, r.left, r.bottom, k, -k); corner(c, r.right, r.bottom, -k, -k);
        }
        if (t.lead) {
            p.setStyle(Paint.Style.FILL); p.setColor((color & 0x00FFFFFF) | 0x22000000);
            c.drawRoundRect(r, 6 * d, 6 * d, p);
        }
        if (r.width() < 22 * d && !t.lead) return;
        String s = t.label.toUpperCase(java.util.Locale.US);
        if (vehicle && !Float.isNaN(t.distance)) {
            s = Ui.range(t.distance, mph);
            if (!Float.isNaN(t.rangeRate)) {
                double other = ego + t.rangeRate; // their speed relative to the road
                if (ego > 3 && other > -2) s += "  •  " + Ui.speed(Math.max(0, other), mph) + " " + Ui.unit(mph);
            }
        }
        p.setTextSize((t.lead ? 15 : 11) * d); p.setTypeface(Typeface.DEFAULT_BOLD); p.setStyle(Paint.Style.FILL);
        float tw = p.measureText(s), pad = 6 * d, th = p.getTextSize() + pad;
        float x = Math.max(0, Math.min(r.centerX() - tw / 2 - pad, getWidth() - tw - 2 * pad)), y = Math.max(0, r.top - th - 4 * d);
        p.setColor(t.lead ? color : 0xCC0A1020);
        c.drawRoundRect(x, y, x + tw + 2 * pad, y + th, th / 2, th / 2, p);
        p.setColor(t.lead ? 0xFF05070D : Ui.TEXT);
        c.drawText(s, x + pad, y + th - pad * .9f, p);
    }

    private void corner(Canvas c, float x, float y, float dx, float dy) {
        c.drawLine(x, y, x + dx, y, p); c.drawLine(x, y, x, y + dy, p);
    }
}
