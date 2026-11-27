package com.zen.bmwdash;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.View;

/** Full-screen flashing warning. Never blocks touches. */
final class AlarmView extends View {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private String word = "BRAKE!";

    AlarmView(Context c) { super(c); setVisibility(GONE); setClickable(false); }

    /** keep the road visible: a flashing border and a banner at the top instead of a covered screen */
    boolean clean = true;

    void show(String text) { word = text; if (getVisibility() != VISIBLE) setVisibility(VISIBLE); invalidate(); }
    void hide() { if (getVisibility() != GONE) setVisibility(GONE); }

    @Override protected void onDraw(Canvas c) {
        boolean on = (SystemClock.elapsedRealtime() / 220) % 2 == 0;
        float w = getWidth(), h = getHeight(), edge = Math.min(w, h) * .05f;
        if (clean) {
            float d = getResources().getDisplayMetrics().density, line = 7 * d;
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(line * 2); p.setColor(on ? Ui.RED : 0x66FF3B5C);
            c.drawRect(0, 0, w, h, p);
            p.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD_ITALIC));
            p.setTextSize(Math.min(46 * d, h / 9f)); p.setTextAlign(Paint.Align.CENTER);
            float half = p.measureText(word) / 2 + 28 * d, tall = p.getTextSize() * 1.35f;
            p.setStyle(Paint.Style.FILL); p.setColor(on ? Ui.RED : 0xFFB0102C);
            c.drawRoundRect(w / 2 - half, line * 2, w / 2 + half, line * 2 + tall, tall / 2, tall / 2, p);
            p.setColor(0xFFFFFFFF); c.drawText(word, w / 2, line * 2 + tall * .76f, p);
            postInvalidateDelayed(110);
            return;
        }
        p.setStyle(Paint.Style.FILL); p.setColor(on ? 0x66FF0030 : 0x22FF0030);
        c.drawRect(0, 0, w, h, p);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(edge * 2); p.setColor(on ? Ui.RED : 0x88FF3B5C);
        c.drawRect(0, 0, w, h, p);
        p.setStyle(Paint.Style.FILL); p.setColor(0xFFFFFFFF); p.setTextAlign(Paint.Align.CENTER);
        p.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD_ITALIC));
        p.setTextSize(Math.min(w / 4.2f, h / 4f));
        p.setShadowLayer(edge, 0, 0, 0xFF000000);
        c.drawText(word, w / 2, h * .32f, p);
        p.clearShadowLayer();
        postInvalidateDelayed(110);
    }
}
