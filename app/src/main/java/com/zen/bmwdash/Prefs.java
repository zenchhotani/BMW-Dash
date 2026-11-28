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
    boolean mapTraffic() { return p.getBoolean("traffic", true); }
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
    boolean radar() { return p.getBoolean("radar", true); }
    String reports() { return p.getString("reports", ""); }
    String home() { return p.getString("home", ""); }
    String work() { return p.getString("work", ""); }
    String recents() { return p.getString("recents", "[]"); }
    /** 0 small, 1 large, 2 hidden */
    int mapSize() { return p.getInt("mapsize", 0); }
    /** drawn 3D scene instead of the camera picture */
    boolean view3d() { return p.getBoolean("view3d", false); }
    /** keep the picture uncovered: small alerts, messages that fade, controls tucked away while moving */
    boolean cleanScreen() { return p.getBoolean("clean", true); }
    /** extra camera tuning for night (fixed focus, 30 fps exposure cap, crisp edges): off unless asked, it can slow the preview */
    boolean sharpCamera() { return p.getBoolean("sharpcam", false); }
    boolean arRoute() { return p.getBoolean("arroute", true); }
    int storageGb() { return p.getInt("gb", 8); }

    SharedPreferences.Editor edit() { return p.edit(); }
}
