package com.zen.bmwdash;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.View;

import com.zen.bmwdash.core.SignalColor;

/** Large three-lamp indicator showing the traffic light the camera is reading. */
final class SignalView extends View {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int state = SignalColor.NONE;

    SignalView(Context c) { super(c); setVisibility(GONE); }

    void show(int s) {
        setVisibility(s == SignalColor.NONE ? GONE : VISIBLE);
        if (s != state) { state = s; invalidate(); }
    }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), r = h * .30f;
        p.setStyle(Paint.Style.FILL); p.setColor(0xF005070D);
        c.drawRoundRect(0, 0, w, h, h / 2, h / 2, p);
        int[] colors = {Ui.RED, Ui.AMBER, Ui.GREEN};
        int[] states = {SignalColor.RED, SignalColor.YELLOW, SignalColor.GREEN};
        for (int i = 0; i < 3; i++) {
            float x = h * .5f + i * h * .72f;
            boolean on = state == states[i];
            if (on) { p.setColor((colors[i] & 0x00FFFFFF) | 0x66000000); c.drawCircle(x, h / 2, r * 1.45f, p); }
            p.setColor(on ? colors[i] : (colors[i] & 0x00FFFFFF) | 0x26000000);
            c.drawCircle(x, h / 2, r, p);
        }
        String s = state == SignalColor.RED ? "RED" : state == SignalColor.YELLOW ? "YELLOW" : "GREEN";
        p.setColor(state == SignalColor.RED ? Ui.RED : state == SignalColor.YELLOW ? Ui.AMBER : Ui.GREEN);
        p.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD_ITALIC));
        p.setTextSize(h * .46f); p.setTextAlign(Paint.Align.CENTER);
        float from = h * 2.35f;
        c.drawText(s, (from + w - h * .15f) / 2, h * .66f, p);
    }
}
