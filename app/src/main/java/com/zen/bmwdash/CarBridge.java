package com.zen.bmwdash;

import android.graphics.Bitmap;

import com.zen.bmwdash.core.LaneState;
import com.zen.bmwdash.core.Track;

import java.util.ArrayList;
import java.util.List;

/**
 * What the phone app knows right now, handed to the Android Auto screen. The camera and the AI keep running in
 * the phone app exactly as before; the car screen only draws what they produce.
 */
final class CarBridge {
    static final int CAMERA = 0, SAFETY = 1, VIEW3D = 2, MODES = 3;

    /** the car screen is up: the engine then keeps a small copy of the camera picture for it */
    static volatile boolean shown;
    static volatile int mode = CAMERA;

    static volatile Bitmap frame;
    static volatile long frameAt;
    static volatile List<Track> tracks = new ArrayList<>();
    static volatile LaneState lane;
    static volatile int frameW = 16, frameH = 9;
    /** last time the phone app fed us, to tell a running app from a closed one */
    static volatile long updated;

    static volatile double speed, limit = Double.NaN;
    static volatile int light, heat;
    static volatile boolean mph = true, poorView, lanesOn = true;
    static volatile ArRenderer.Guide guide;

    static volatile int alertLevel = -1;
    static volatile String alertText = "";

    static String modeName() { return mode == SAFETY ? "Safety card" : mode == VIEW3D ? "3D view" : "Camera"; }
    static void nextMode() { mode = (mode + 1) % MODES; }

    private CarBridge() {}
}
