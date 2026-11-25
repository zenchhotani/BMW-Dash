package com.zen.bmwdash;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.view.Gravity;
import android.widget.TextView;

/** Shared look: night cockpit with electric blue, violet and signal colours. */
final class Ui {
    static final int BG = 0xFF05070D, CARD = 0xE60D1322, STROKE = 0x33FFFFFF, TEXT = 0xFFF4F7FF, DIM = 0xFF8D9AB8;
    static final int BLUE = 0xFF1C9BFF, CYAN = 0xFF35F0E0, VIOLET = 0xFF7A5CFF, RED = 0xFFFF3B5C, AMBER = 0xFFFFB020, GREEN = 0xFF2EE88A;

    static int dp(Context c, float v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }

    static GradientDrawable card(Context c, int fill, float radius, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill); d.setCornerRadius(dp(c, radius)); d.setStroke(dp(c, 1), stroke);
        return d;
    }

    static TextView text(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s); t.setTextSize(sp); t.setTextColor(color);
        t.setTypeface(Typeface.create("sans-serif-medium", bold ? Typeface.BOLD : Typeface.NORMAL));
        return t;
    }

    static TextView button(Context c, String s, int accent, Runnable r) {
        TextView t = text(c, s, 13, TEXT, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(c, 13), dp(c, 9), dp(c, 13), dp(c, 9));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{Color.argb(235, Color.red(accent) / 3, Color.green(accent) / 3, Color.blue(accent) / 3), CARD});
        bg.setCornerRadius(dp(c, 22)); bg.setStroke(dp(c, 1), accent);
        t.setBackground(new RippleDrawable(ColorStateList.valueOf(0x55FFFFFF), bg, null));
        t.setOnClickListener(v -> r.run());
        return t;
    }

    static String distance(double m, boolean mph) {
        if (mph) return m < 160 ? Math.round(m * 3.28084 / 10) * 10 + " ft" : String.format(java.util.Locale.US, "%.1f mi", m / 1609.344);
        return m < 950 ? Math.round(m / 10) * 10 + " m" : String.format(java.util.Locale.US, "%.1f km", m / 1000);
    }
    /** short range to a vehicle */
    static String range(double m, boolean mph) { return mph ? Math.round(m * 3.28084) + " ft" : Math.round(m) + " m"; }
    static int speed(double ms, boolean mph) { return (int) Math.round(ms * (mph ? 2.236936 : 3.6)); }
    static String unit(boolean mph) { return mph ? "mph" : "km/h"; }
}
