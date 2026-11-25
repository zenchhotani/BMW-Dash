package com.zen.bmwdash;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.graphics.Typeface;
import android.view.View;

/** Instrument cluster: animated speed dial, speed-limit sign, live road speed, lane and gap tiles. */
final class GaugeView extends View {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Typeface heavy = Typeface.create("sans-serif-condensed", Typeface.BOLD_ITALIC);
    private final Typeface bold = Typeface.create("sans-serif-condensed", Typeface.BOLD);

    boolean mph = true, hasFix;
    double speed, limit = Double.NaN, flow = Double.NaN, freeFlow = Double.NaN; // m/s
    float leadDistance = Float.NaN, laneOffset;
    boolean laneKnown, departing, hasKey;
    String laneLabel = "SCANNING";
    private float shown;

    GaugeView(Context c) { super(c); }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        float target = (float) speed;
        shown += (target - shown) * .18f;
        boolean wide = w > h * 1.9f;
        float dialW = wide ? w * .36f : w * .62f, dialH = wide ? h : h * .64f;
        float r = Math.min(dialW, dialH) * .46f, cx = dialW / 2, cy = dialH / 2 + r * .04f;
        dial(c, cx, cy, r);
        if (wide) {
            float x = dialW, tw = (w - dialW) / 4f;
            sign(c, x, 0, tw, h);
            flowTile(c, x + tw, 0, tw, h);
            laneTile(c, x + 2 * tw, 0, tw, h);
            gapTile(c, x + 3 * tw, 0, tw, h);
        } else {
            sign(c, dialW, 0, w - dialW, dialH);
            float tw = w / 3f, th = h - dialH;
            flowTile(c, 0, dialH, tw, th);
            laneTile(c, tw, dialH, tw, th);
            gapTile(c, 2 * tw, dialH, tw, th);
        }
        if (Math.abs(target - shown) > .05f) postInvalidateOnAnimation();
    }

    private void dial(Canvas c, float cx, float cy, float r) {
        float max = mph ? 140 : 220, v = shown * (mph ? 2.236936f : 3.6f);
        boolean over = !Double.isNaN(limit) && speed > limit + 2.2;
        RectF o = new RectF(cx - r, cy - r, cx + r, cy + r);
        p.setStyle(Paint.Style.STROKE); p.setStrokeCap(Paint.Cap.BUTT); p.setStrokeWidth(r * .12f);
        p.setColor(0xFF172036); c.drawArc(o, 135, 270, false, p);
        SweepGradient g = new SweepGradient(cx, cy,
                over ? new int[]{Ui.AMBER, Ui.RED, Ui.RED, Ui.AMBER} : new int[]{Ui.CYAN, Ui.BLUE, Ui.VIOLET, Ui.CYAN},
                new float[]{0f, .35f, .75f, 1f});
        Matrix m = new Matrix(); m.setRotate(130, cx, cy); g.setLocalMatrix(m);
        float sweep = Math.min(270, 270 * v / max);
        p.setShader(g);
        p.setStrokeWidth(r * .22f); p.setAlpha(60); c.drawArc(o, 135, sweep, false, p);
        p.setStrokeWidth(r * .12f); p.setAlpha(255); c.drawArc(o, 135, sweep, false, p);
        p.setShader(null);
        p.setStrokeWidth(r * .012f);
        for (int i = 0; i <= 28; i++) {
            double a = Math.toRadians(135 + 270 * i / 28.0);
            float in = i % 4 == 0 ? .74f : .80f;
            p.setColor(270f * i / 28 <= sweep ? Ui.TEXT : 0xFF3A4766);
            c.drawLine((float) (cx + Math.cos(a) * r * in), (float) (cy + Math.sin(a) * r * in),
                    (float) (cx + Math.cos(a) * r * .86f), (float) (cy + Math.sin(a) * r * .86f), p);
        }
        center(c, hasFix ? String.valueOf(Math.round(v)) : "--", cx - r * .03f, cy + r * .22f, r * .72f, over ? Ui.RED : Ui.TEXT, heavy);
        center(c, Ui.unit(mph).toUpperCase(java.util.Locale.US), cx, cy + r * .50f, r * .17f, Ui.DIM, bold);
        if (!hasFix) center(c, "WAITING FOR GPS", cx, cy + r * .86f, r * .12f, Ui.AMBER, bold);
        // tricolour accent stripe
        int[] stripe = {0xFF35A7FF, 0xFF4B4DE0, 0xFFFF3B5C};
        for (int i = 0; i < 3; i++) {
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(r * .035f); p.setColor(stripe[i]);
            float x = cx - r * .16f + i * r * .11f;
            c.drawLine(x, cy - r * .40f, x + r * .08f, cy - r * .56f, p);
        }
    }

    private void tile(Canvas c, float x, float y, float w, float h, int accent) {
        float g = Math.min(w, h) * .045f;
        p.setShader(null); p.setStyle(Paint.Style.FILL); p.setColor(Ui.CARD);
        c.drawRoundRect(x + g, y + g, x + w - g, y + h - g, g * 3, g * 3, p);
        p.setColor(accent);
        c.drawRoundRect(x + w * .3f, y + g, x + w * .7f, y + g * 1.8f, g, g, p);
    }

    private void sign(Canvas c, float x, float y, float w, float h) {
        tile(c, x, y, w, h, Ui.VIOLET);
        String v = Double.isNaN(limit) ? "--" : String.valueOf(Ui.speed(limit, mph));
        float sh = Math.min(h * .78f, w * 1.1f), sw = sh * .74f, l = x + (w - sw) / 2, t = y + (h - sh) / 2;
        p.setStyle(Paint.Style.FILL);
        if (mph) {
            p.setColor(0xFFF8F8F4); c.drawRoundRect(l, t, l + sw, t + sh, sw * .12f, sw * .12f, p);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(sw * .035f); p.setColor(0xFF101010);
            c.drawRoundRect(l + sw * .06f, t + sw * .06f, l + sw * .94f, t + sh - sw * .06f, sw * .08f, sw * .08f, p);
            center(c, "SPEED", x + w / 2, t + sh * .22f, sh * .15f, 0xFF101010, bold);
            center(c, "LIMIT", x + w / 2, t + sh * .37f, sh * .15f, 0xFF101010, bold);
            center(c, v, x + w / 2, t + sh * .85f, sh * .48f, 0xFF101010, bold);
        } else {
            float r = sw / 2;
            p.setColor(0xFFF8F8F4); c.drawCircle(x + w / 2, y + h / 2, r, p);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(r * .2f); p.setColor(Ui.RED);
            c.drawCircle(x + w / 2, y + h / 2, r * .88f, p);
            center(c, v, x + w / 2, y + h / 2 + r * .3f, r * .85f, 0xFF101010, bold);
        }
    }

    private void flowTile(Canvas c, float x, float y, float w, float h) {
        double ratio = Double.isNaN(flow) || Double.isNaN(freeFlow) || freeFlow <= 0 ? Double.NaN : flow / freeFlow;
        int col = Double.isNaN(ratio) ? Ui.DIM : ratio > .8 ? Ui.GREEN : ratio > .5 ? Ui.AMBER : Ui.RED;
        tile(c, x, y, w, h, col);
        float s = Math.min(w * .5f, h * .5f);
        center(c, "ROAD SPEED NOW", x + w / 2, y + h * .22f, Math.min(h * .12f, w * .1f), Ui.DIM, bold);
        center(c, Double.isNaN(flow) ? "--" : String.valueOf(Ui.speed(flow, mph)), x + w / 2, y + h * .66f, s * .85f, col, heavy);
        String sub = !hasKey ? "ADD TOMTOM KEY" : Double.isNaN(ratio) ? "NO LIVE DATA"
                : ratio > .8 ? "FLOWING" : ratio > .5 ? "SLOWING" : "CONGESTED";
        center(c, sub, x + w / 2, y + h * .86f, Math.min(h * .12f, w * .09f), col, bold);
    }

    private void laneTile(Canvas c, float x, float y, float w, float h) {
        int col = !laneKnown ? Ui.DIM : departing ? Ui.RED : Math.abs(laneOffset) < .22f ? Ui.GREEN : Ui.AMBER;
        tile(c, x, y, w, h, col);
        center(c, "LANE", x + w / 2, y + h * .22f, Math.min(h * .12f, w * .1f), Ui.DIM, bold);
        float mx = x + w / 2, top = y + h * .30f, bot = y + h * .70f;
        p.setStyle(Paint.Style.STROKE); p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeWidth(Math.max(3, w * .025f)); p.setColor(col);
        c.drawLine(mx - w * .14f, top, mx - w * .32f, bot, p);
        c.drawLine(mx + w * .14f, top, mx + w * .32f, bot, p);
        float car = mx + Math.max(-.6f, Math.min(.6f, laneKnown ? laneOffset : 0)) * w * .5f, cw = w * .17f, ch = h * .24f;
        p.setStyle(Paint.Style.FILL); p.setColor(laneKnown ? Ui.TEXT : 0xFF3A4766);
        c.drawRoundRect(car - cw / 2, bot - ch, car + cw / 2, bot, cw * .35f, cw * .35f, p);
        p.setColor(0xFF1C2A4A); c.drawRoundRect(car - cw * .34f, bot - ch * .78f, car + cw * .34f, bot - ch * .45f, cw * .15f, cw * .15f, p);
        center(c, laneLabel, x + w / 2, y + h * .86f, Math.min(h * .12f, w * .09f), col, bold);
    }

    private void gapTile(Canvas c, float x, float y, float w, float h) {
        boolean has = !Float.isNaN(leadDistance);
        double gap = has && speed > 3 ? leadDistance / speed : Double.NaN;
        int col = !has ? Ui.DIM : Double.isNaN(gap) ? Ui.CYAN : gap < 1.2 ? Ui.RED : gap < 2 ? Ui.AMBER : Ui.GREEN;
        tile(c, x, y, w, h, col);
        center(c, "CAR AHEAD", x + w / 2, y + h * .22f, Math.min(h * .12f, w * .1f), Ui.DIM, bold);
        center(c, has ? Ui.range(leadDistance, mph) : "CLEAR", x + w / 2, y + h * .62f, Math.min(h * .34f, w * .27f), col, heavy);
        center(c, Double.isNaN(gap) ? (has ? "ESTIMATED" : "NO VEHICLE") : String.format(java.util.Locale.US, "%.1f s GAP", gap),
                x + w / 2, y + h * .86f, Math.min(h * .12f, w * .09f), col, bold);
    }

    private void center(Canvas c, String s, float x, float y, float size, int color, Typeface tf) {
        p.setShader(null); p.setStyle(Paint.Style.FILL); p.setTypeface(tf); p.setTextSize(size);
        p.setTextAlign(Paint.Align.CENTER); p.setColor(color);
        c.drawText(s, x, y, p);
    }
}
