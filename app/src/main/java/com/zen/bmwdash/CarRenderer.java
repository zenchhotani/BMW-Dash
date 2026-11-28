package com.zen.bmwdash;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;

import com.zen.bmwdash.core.Advisor;
import com.zen.bmwdash.core.LaneState;
import com.zen.bmwdash.core.Pace;
import com.zen.bmwdash.core.Track;

import java.util.List;
import java.util.Locale;

/** Draws the car screen: the camera view with the same overlay as the phone, a calm safety card, or the 3D view. */
final class CarRenderer {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pic = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final OverlayView overlay;
    private final RoadView road;
    private int laidW, laidH;

    /** views are made on the main thread; only drawing happens on the car's render thread */
    CarRenderer(Context app) {
        overlay = new OverlayView(app);
        road = new RoadView(app);
        overlay.clean = true;
    }

    private static String spd(double ms, boolean mph) { return String.valueOf(Ui.speed(ms, mph)); }

    int intervalMs() { return CarBridge.heat >= Pace.HOT ? 160 : 70; }

    void draw(Canvas c, int w, int h, Rect safeArea) {
        if (laidW != w || laidH != h) { overlay.layout(0, 0, w, h); road.layout(0, 0, w, h); laidW = w; laidH = h; }
        c.drawColor(Ui.BG);
        long now = SystemClock.elapsedRealtime();
        Rect safe = safeArea == null || safeArea.isEmpty() ? new Rect(0, 0, w, h) : safeArea;
        if (now - CarBridge.updated > 3000) { waiting(c, w, h, safe); return; }
        int mode = CarBridge.mode;
        if (mode == CarBridge.SAFETY) card(c, w, h, safe);
        else {
            if (mode == CarBridge.VIEW3D) view3d(c);
            else camera(c, w, h, now);
            hud(c, w, h, safe);
        }
        banner(c, w, h, safe, now);
        mode(c, w, h, safe);
    }

    private void waiting(Canvas c, int w, int h, Rect safe) {
        p.setStyle(Paint.Style.FILL); p.setTextAlign(Paint.Align.CENTER);
        p.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        p.setColor(Ui.TEXT); p.setTextSize(h / 12f);
        c.drawText("BMW Dash", w / 2f, h * .42f, p);
        p.setTypeface(Typeface.DEFAULT); p.setColor(Ui.DIM); p.setTextSize(h / 20f);
        c.drawText("Open BMW Dash on the phone and the", w / 2f, h * .55f, p);
        c.drawText("road view starts here.", w / 2f, h * .55f + h / 15f, p);
    }

    private void camera(Canvas c, int w, int h, long now) {
        Bitmap f = CarBridge.frame;
        int fw = CarBridge.frameW, fh = CarBridge.frameH;
        if (f == null || now - CarBridge.frameAt > 2500) {
            p.setStyle(Paint.Style.FILL); p.setTextAlign(Paint.Align.CENTER); p.setColor(Ui.DIM);
            p.setTypeface(Typeface.DEFAULT); p.setTextSize(h / 20f);
            c.drawText("Waiting for the camera…", w / 2f, h / 2f, p);
            return;
        }
        // the same centre-crop the phone uses for its preview
        float s = Math.max(w / (float) fw, h / (float) fh);
        float dw = fw * s, dh = fh * s, ox = (w - dw) / 2, oy = (h - dh) / 2;
        c.drawBitmap(f, null, new RectF(ox, oy, ox + dw, oy + dh), pic);
        List<Track> tracks = CarBridge.tracks;
        LaneState lane = CarBridge.lane;
        overlay.clean = true;
        overlay.heat = CarBridge.heat;
        overlay.show(tracks, CarBridge.lanesOn && lane != null && lane.confident ? lane : null, fw, fh, CarBridge.speed, CarBridge.mph);
        overlay.draw(c);
    }

    private void view3d(Canvas c) {
        road.mph = CarBridge.mph;
        LaneState lane = CarBridge.lane;
        road.show(CarBridge.tracks, lane, CarBridge.speed, CarBridge.lanesOn && lane != null && lane.confident);
        road.update(CarBridge.guide, CarBridge.light, CarBridge.speed, CarBridge.poorView, CarBridge.heat);
        road.draw(c);
    }

    /** speed, posted limit and the signal for our lane: always in the same place */
    private void hud(Canvas c, int w, int h, Rect safe) {
        float u = h / 100f, pad = 2f * u;
        String s = spd(CarBridge.speed, CarBridge.mph);
        p.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD)); p.setTextAlign(Paint.Align.LEFT);
        p.setTextSize(11 * u);
        float tw = p.measureText(s);
        float x = safe.left + pad, y = safe.bottom - pad - 14 * u;
        p.setStyle(Paint.Style.FILL); p.setColor(0xB005070D);
        c.drawRoundRect(x, y, x + tw + 5 * u + 9 * u, y + 14 * u, 4 * u, 4 * u, p);
        p.setColor(Ui.TEXT); c.drawText(s, x + 3 * u, y + 11.2f * u, p);
        p.setTextSize(3.6f * u); p.setColor(Ui.DIM);
        c.drawText(Ui.unit(CarBridge.mph), x + tw + 4 * u, y + 11.2f * u, p);

        double limit = CarBridge.limit;
        float cx = safe.right - pad - 8 * u, cy = safe.bottom - pad - 8 * u;
        if (!Double.isNaN(limit)) {
            p.setStyle(Paint.Style.FILL); p.setColor(0xFFFFFFFF); c.drawCircle(cx, cy, 8 * u, p);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1.4f * u); p.setColor(0xFFE02040); c.drawCircle(cx, cy, 7.2f * u, p);
            p.setStyle(Paint.Style.FILL); p.setColor(0xFF111111); p.setTextAlign(Paint.Align.CENTER); p.setTextSize(6 * u);
            c.drawText(spd(limit, CarBridge.mph), cx, cy + 2.1f * u, p);
        }
        int light = CarBridge.light;
        if (light != 0) {
            float lx = cx - (Double.isNaN(limit) ? 0 : 19 * u);
            int col = light == 1 ? Ui.RED : light == 2 ? Ui.AMBER : Ui.GREEN;
            p.setStyle(Paint.Style.FILL); p.setColor(0xB005070D); c.drawCircle(lx, cy, 8 * u, p);
            p.setColor(col); c.drawCircle(lx, cy, 6 * u, p);
        }
    }

    /** one message at a time at the top, large enough to read at a glance */
    private void banner(Canvas c, int w, int h, Rect safe, long now) {
        int level = CarBridge.alertLevel;
        String text = CarBridge.alertText;
        if (text == null || text.isEmpty() || level < Advisor.WARN) return;
        boolean flash = level == Advisor.DANGER && (now / 220) % 2 == 0;
        float u = h / 100f;
        int col = level == Advisor.DANGER ? (flash ? Ui.RED : 0xFFB0102C) : Ui.AMBER;
        p.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD)); p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(level == Advisor.DANGER ? 9 * u : 6.4f * u);
        float tw = Math.min(p.measureText(text), safe.width() - 10 * u), th = p.getTextSize() * 1.5f;
        float cx = safe.centerX(), top = safe.top + 2 * u;
        p.setStyle(Paint.Style.FILL); p.setColor(col);
        c.drawRoundRect(cx - tw / 2 - 4 * u, top, cx + tw / 2 + 4 * u, top + th, th / 2, th / 2, p);
        p.setColor(level == Advisor.DANGER ? 0xFFFFFFFF : 0xFF05070D);
        c.drawText(text, cx, top + th * .7f, p);
    }

    /** which view is showing, and that a tap changes it */
    private void mode(Canvas c, int w, int h, Rect safe) {
        float u = h / 100f;
        String s = CarBridge.modeName();
        p.setTypeface(Typeface.DEFAULT_BOLD); p.setTextAlign(Paint.Align.LEFT); p.setTextSize(3.2f * u);
        float tw = p.measureText(s);
        float x = safe.left + 2 * u, y = safe.bottom - 2 * u - 14 * u - 6 * u;
        if (CarBridge.mode == CarBridge.SAFETY) y = safe.bottom - 7 * u;
        p.setStyle(Paint.Style.FILL); p.setColor(0x80000000);
        c.drawRoundRect(x, y, x + tw + 4 * u, y + 5 * u, 2.5f * u, 2.5f * u, p);
        p.setColor(Ui.DIM); c.drawText(s, x + 2 * u, y + 3.6f * u, p);
    }

    /** speed, limit, the car ahead and the signal in big plain type, no picture */
    private void card(Canvas c, int w, int h, Rect safe) {
        float u = h / 100f;
        p.setStyle(Paint.Style.FILL); p.setTextAlign(Paint.Align.LEFT);
        p.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        p.setColor(Ui.TEXT); p.setTextSize(30 * u);
        String s = spd(CarBridge.speed, CarBridge.mph);
        float left = safe.left + 4 * u, base = safe.top + 36 * u;
        c.drawText(s, left, base, p);
        float sw = p.measureText(s);
        p.setTextSize(7 * u); p.setColor(Ui.DIM); c.drawText(Ui.unit(CarBridge.mph), left + sw + 2 * u, base, p);

        double limit = CarBridge.limit;
        float cx = safe.right - 18 * u, cy = safe.top + 24 * u;
        if (!Double.isNaN(limit)) {
            p.setStyle(Paint.Style.FILL); p.setColor(0xFFFFFFFF); c.drawCircle(cx, cy, 15 * u, p);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2.6f * u); p.setColor(0xFFE02040); c.drawCircle(cx, cy, 13.6f * u, p);
            p.setStyle(Paint.Style.FILL); p.setColor(0xFF111111); p.setTextAlign(Paint.Align.CENTER); p.setTextSize(11 * u);
            p.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
            c.drawText(spd(limit, CarBridge.mph), cx, cy + 4 * u, p);
        }
        int light = CarBridge.light;
        if (light != 0) {
            int col = light == 1 ? Ui.RED : light == 2 ? Ui.AMBER : Ui.GREEN;
            float lx = cx - 36 * u;
            p.setStyle(Paint.Style.FILL); p.setColor(0xFF111827); c.drawCircle(lx, cy, 15 * u, p);
            p.setColor(col); c.drawCircle(lx, cy, 11 * u, p);
        }

        Track lead = null;
        for (Track t : CarBridge.tracks) if (t.lead) lead = t;
        p.setTextAlign(Paint.Align.LEFT); p.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        float y = safe.top + 56 * u;
        if (lead != null && !Float.isNaN(lead.distance)) {
            double gap = CarBridge.speed > 3 ? lead.distance / CarBridge.speed : 99;
            int col = gap < 1.2 ? Ui.RED : gap < 2 ? Ui.AMBER : Ui.GREEN;
            p.setColor(col); p.setTextSize(11 * u);
            c.drawText("Car ahead  " + Ui.range(lead.distance, CarBridge.mph), left, y, p);
            p.setColor(Ui.DIM); p.setTextSize(6.5f * u);
            String more = (CarBridge.speed > 3 ? String.format(Locale.US, "%.1f s gap", gap) : "")
                    + (lead.motion == Track.STOPPED ? (CarBridge.speed > 3 ? "   •   " : "") + "stopped" : "");
            if (!more.isEmpty()) c.drawText(more, left, y + 9 * u, p);
        } else {
            p.setColor(Ui.DIM); p.setTextSize(8 * u);
            c.drawText("Road clear ahead", left, y, p);
        }
        String text = CarBridge.alertText;
        if (text != null && !text.isEmpty() && CarBridge.alertLevel >= Advisor.INFO && CarBridge.alertLevel < Advisor.WARN) {
            p.setColor(Ui.BLUE); p.setTextSize(6 * u);
            c.drawText(text, left, safe.bottom - 14 * u, p);
        }
    }
}
