package com.zen.bmwdash;

import android.content.Context;
import android.content.SharedPreferences;

final class Prefs {
    private final SharedPreferences p;
    Prefs(Context c) { p = c.getSharedPreferences("dash", Context.MODE_PRIVATE); }

    String tomtomKey() { return p.getString("tomtom", "").trim(); }
    boolean mph() { return p.getBoolean("mph", true); }
    boolean voice() { return p.getBoolean("voice", true); }
    boolean autoRecord() { return p.getBoolean("autorec", true); }
    boolean mapDark() { return p.getBoolean("dark", true); }
    boolean mapTraffic() { return extra("x_traffic"); }
    /** set while the road check is far behind: the extras step aside until it recovers */
    static volatile boolean paused;
    /** Extras are separate switches, all off until chosen; each steps aside while the road check is behind */
    private boolean extra(String key) { return p.getBoolean(key, false) && !paused; }
    boolean chosen(String key) { return p.getBoolean(key, false); }
    boolean carName() { return extra("x_carid"); }
    boolean predict() { return extra("x_predict"); }
    boolean outlook() { return extra("x_outlook"); }
    boolean anyExtra() { for (String k : KEYS) if (chosen(k)) return true; return false; }
    static final String[] KEYS = {"x_carid", "x_predict", "x_3d", "x_route", "x_outlook", "x_radar", "x_traffic"};
    /** fraction of frame height */
    float horizon() { return p.getFloat("horizon", 0.50f); }
    float center() { return p.getFloat("center", 0.50f); }
    float hood() { return p.getFloat("hood", 0.88f); }
    float cameraHeight() { return p.getFloat("height", 1.20f); }
    String aiKey() { return p.getString("aikey", "").trim(); }
    String sosNumber() { return p.getString("sos", "").trim(); }
    /** 0 auto, 1 off, 2 night boost, 3 green night view */
    int night() { return p.getInt("night", 0); }
    /** 0 auto, 1 off, 2 rain, 3 snow, 4 fog */
    int weatherMode() { return p.getInt("wxmode", 0); }
    /** sideways correction of the drawn lane, fraction of frame width */
    float laneShift() { return p.getFloat("laneshift", 0f); }
    boolean radar() { return extra("x_radar"); }
    String reports() { return p.getString("reports", ""); }
    String home() { return p.getString("home", ""); }
    String work() { return p.getString("work", ""); }
    String recents() { return p.getString("recents", "[]"); }
    /** 0 small, 1 large, 2 hidden */
    int mapSize() { return p.getInt("mapsize", 0); }
    /** drawn 3D scene instead of the camera picture */
    boolean view3d() { return extra("x_3d"); }
    /** keep the picture uncovered: small alerts, messages that fade, controls tucked away while moving */
    boolean cleanScreen() { return p.getBoolean("clean", true); }
    /** extra camera tuning for night (fixed focus, 30 fps exposure cap, crisp edges): off unless asked, it can slow the preview */
    boolean sharpCamera() { return p.getBoolean("sharpcam", false); }
    boolean arRoute() { return extra("x_route"); }
    int storageGb() { return p.getInt("gb", 8); }

    SharedPreferences.Editor edit() { return p.edit(); }
}
