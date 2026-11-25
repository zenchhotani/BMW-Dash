package com.zen.bmwdash;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.text.InputType;
import android.view.Gravity;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.camera.camera2.interop.ExperimentalCamera2Interop;
import androidx.camera.view.PreviewView;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;

import com.zen.bmwdash.core.Advisor;
import com.zen.bmwdash.core.Geo;
import com.zen.bmwdash.core.LaneState;
import com.zen.bmwdash.core.Track;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

@OptIn(markerClass = ExperimentalCamera2Interop.class)
public class MainActivity extends ComponentActivity implements CameraEngine.Listener, LocationListener, SensorEventListener {
    private Prefs prefs;
    private RoadData data;
    private CameraEngine camera;
    private MapPanel map;
    private GaugeView gauges;
    private OverlayView overlay;
    private PreviewView preview;
    private FrameLayout cameraPane, mapPane;
    private LinearLayout root, side;
    private TextView roadChip, weatherChip, recChip, adviceBar, turnBar, visionChip, etaChip, trafficButton;
    private HorizontalScrollView routeScroll;
    private LinearLayout routeRow;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextToSpeech tts;
    private boolean ttsReady, started, resumed, cameraStarted, locationOn, calibrating;
    private ToneGenerator tone;
    private LocationManager locations;
    private SensorManager sensors;

    private Location last, speedBase;
    private long lastFixTime, lastImpact, lastSpoke, lastRoute, lastVision;
    private String lastSpeech = "";
    private double speed, heading;
    private boolean headingValid;
    private Track lead;
    private LaneState lane;
    private List<RoadData.Route> routes = new ArrayList<>();
    private int selected, offRoute;
    private double[] destination;
    private int lastLevel = -1;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        prefs = new Prefs(this);
        data = new RoadData(prefs);
        data.onChange = this::refresh;
        locations = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        sensors = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        tts = new TextToSpeech(this, s -> {
            if (s == TextToSpeech.SUCCESS) { tts.setLanguage(Locale.US); ttsReady = true; }
        });
        try { tone = new ToneGenerator(AudioManager.STREAM_MUSIC, 90); } catch (RuntimeException ignored) { }
        buildUi(state);
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (calibrating) calibrate(false);
                else if (!routes.isEmpty()) clearRoute();
                else new AlertDialog.Builder(MainActivity.this).setMessage("Close BMW Dash and stop recording?")
                        .setPositiveButton("Close", (d, w) -> finish()).setNegativeButton("Keep driving", null).show();
            }
        });
        if (granted(Manifest.permission.CAMERA) && granted(Manifest.permission.ACCESS_FINE_LOCATION)) begin();
        else requestPermissions(new String[]{Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION}, 1);
    }

    private boolean granted(String p) { return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED; }

    @Override public void onRequestPermissionsResult(int code, @NonNull String[] p, @NonNull int[] r) {
        super.onRequestPermissionsResult(code, p, r);
        begin();
    }

    private void begin() {
        if (!started) {
            started = true;
            if (granted(Manifest.permission.CAMERA)) startCamera();
        }
        startLocation();
        if (!granted(Manifest.permission.CAMERA) || !granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            new AlertDialog.Builder(this).setTitle("Permissions needed")
                    .setMessage("Camera is needed to see the road. Precise location is needed for speed and the map.")
                    .setPositiveButton("Open settings", (d, w) -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", getPackageName(), null))))
                    .setNegativeButton("Later", null).show();
        }
    }

    private void startCamera() {
        if (cameraStarted) return;
        cameraStarted = true;
        camera = new CameraEngine(this, prefs, this);
        preview.post(() -> camera.start(this, preview, rotation()));
    }

    @SuppressWarnings("deprecation")
    private int rotation() {
        return getWindowManager().getDefaultDisplay() == null ? Surface.ROTATION_0 : getWindowManager().getDefaultDisplay().getRotation();
    }

    // ---------------------------------------------------------------- layout

    private void buildUi(Bundle state) {
        root = new LinearLayout(this);
        root.setBackgroundColor(Ui.BG);

        cameraPane = new FrameLayout(this);
        preview = new PreviewView(this);
        preview.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        preview.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        cameraPane.addView(preview, new FrameLayout.LayoutParams(-1, -1));
        overlay = new OverlayView(this);
        cameraPane.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        cameraPane.setClipChildren(true);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        LinearLayout chips = new LinearLayout(this);
        roadChip = chip("Finding your road…", Ui.BLUE);
        roadChip.setSingleLine(true);
        roadChip.setEllipsize(android.text.TextUtils.TruncateAt.END);
        chips.addView(roadChip, new LinearLayout.LayoutParams(0, -2, 1));
        recChip = chip("● REC", Ui.RED);
        recChip.setVisibility(View.GONE);
        chips.addView(recChip, margins(-2, -2, 6));
        weatherChip = chip("…", Ui.VIOLET);
        chips.addView(weatherChip, margins(-2, -2, 6));
        top.addView(chips);
        turnBar = Ui.text(this, "", 17, Ui.TEXT, true);
        GradientDrawable tg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xF01C5BFF, 0xF07A5CFF});
        tg.setCornerRadius(dp(16));
        turnBar.setBackground(tg);
        turnBar.setPadding(dp(14), dp(10), dp(14), dp(10));
        turnBar.setVisibility(View.GONE);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
        tlp.topMargin = dp(6);
        top.addView(turnBar, tlp);
        visionChip = Ui.text(this, "AI starting…", 10, Ui.DIM, true);
        visionChip.setPadding(dp(8), dp(4), dp(8), 0);
        top.addView(visionChip);
        FrameLayout.LayoutParams topLp = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        topLp.setMargins(dp(8), dp(8), dp(8), 0);
        cameraPane.addView(top, topLp);

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        adviceBar = Ui.text(this, "Ready when you are.", 16, Ui.TEXT, true);
        adviceBar.setGravity(Gravity.CENTER);
        adviceBar.setPadding(dp(14), dp(10), dp(14), dp(10));
        bottom.addView(adviceBar, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout buttons = new LinearLayout(this);
        buttons.addView(Ui.button(this, "● Rec", Ui.RED, () -> { if (camera != null) camera.toggleRecording(); }));
        buttons.addView(Ui.button(this, "🔒 Lock", Ui.AMBER, () -> lockClip("Clip locked")), margins(-2, -2, 6));
        buttons.addView(Ui.button(this, "🔍 Go to", Ui.CYAN, this::searchDialog), margins(-2, -2, 6));
        trafficButton = Ui.button(this, "🚦 Traffic", Ui.GREEN, () -> {
            if (prefs.tomtomKey().isEmpty()) { toast("Add your free TomTom key in Setup"); settingsDialog(); return; }
            prefs.edit().putBoolean("traffic", !prefs.mapTraffic()).apply();
            map.loadStyle();
            toast(prefs.mapTraffic() ? "Traffic layer on" : "Traffic layer off");
        });
        buttons.addView(trafficButton, margins(-2, -2, 6));
        buttons.addView(Ui.button(this, "⌖ Aim", Ui.VIOLET, () -> calibrate(!calibrating)), margins(-2, -2, 6));
        buttons.addView(Ui.button(this, "⚙ Setup", Ui.BLUE, this::settingsDialog), margins(-2, -2, 6));
        HorizontalScrollView bs = new HorizontalScrollView(this);
        bs.setHorizontalScrollBarEnabled(false);
        bs.addView(buttons);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
        blp.topMargin = dp(6);
        bottom.addView(bs, blp);
        FrameLayout.LayoutParams botLp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        botLp.setMargins(dp(8), 0, dp(8), dp(8));
        cameraPane.addView(bottom, botLp);

        gauges = new GaugeView(this);

        mapPane = new FrameLayout(this);
        map = new MapPanel(this, prefs, state);
        map.pick = this::routeTo;
        mapPane.addView(map, new FrameLayout.LayoutParams(-1, -1));
        etaChip = chip("Long-press the map or tap Go to", Ui.CYAN);
        FrameLayout.LayoutParams elp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        elp.setMargins(dp(8), dp(8), dp(8), 0);
        mapPane.addView(etaChip, elp);
        routeRow = new LinearLayout(this);
        routeScroll = new HorizontalScrollView(this);
        routeScroll.setHorizontalScrollBarEnabled(false);
        routeScroll.addView(routeRow);
        FrameLayout.LayoutParams rlp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        rlp.setMargins(dp(8), 0, dp(8), dp(8));
        mapPane.addView(routeScroll, rlp);
        TextView credit = Ui.text(this, "© OpenStreetMap • OpenFreeMap • TomTom • Open-Meteo", 8, Ui.DIM, false);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        clp.setMargins(0, dp(48), dp(8), 0);
        mapPane.addView(credit, clp);

        side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);

        ViewCompat.setOnApplyWindowInsetsListener(root, (v, in) -> {
            androidx.core.graphics.Insets i = in.getInsets(WindowInsetsCompat.Type.displayCutout());
            v.setPadding(i.left, i.top, i.right, i.bottom);
            return in;
        });
        setContentView(root);
        arrange();
    }

    /** Cover screen and landscape: side by side. Unfolded square or portrait: stacked. */
    private void arrange() {
        Configuration c = getResources().getConfiguration();
        float ratio = c.screenWidthDp / (float) Math.max(1, c.screenHeightDp);
        boolean wide = ratio > 1.35f, square = !wide && ratio > 0.75f;
        root.removeAllViews();
        side.removeAllViews();
        int gap = dp(4);
        if (wide) {
            root.setOrientation(LinearLayout.HORIZONTAL);
            root.addView(cameraPane, weighted(0, -1, 1.55f, gap));
            side.addView(gauges, weighted(-1, 0, 1f, 0));
            side.addView(mapPane, weighted(-1, 0, 1.15f, 0));
            root.addView(side, weighted(0, -1, 1f, gap));
        } else {
            root.setOrientation(LinearLayout.VERTICAL);
            root.addView(cameraPane, weighted(-1, 0, square ? 1.25f : 1.1f, gap));
            root.addView(gauges, weighted(-1, 0, square ? .52f : .62f, gap));
            root.addView(mapPane, weighted(-1, 0, square ? .8f : 1f, gap));
        }
        immersive();
        ViewCompat.requestApplyInsets(root);
    }

    private LinearLayout.LayoutParams weighted(int w, int h, float weight, int m) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h, weight);
        p.setMargins(m / 2, m / 2, m / 2, m / 2);
        return p;
    }
    private LinearLayout.LayoutParams margins(int w, int h, int left) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.leftMargin = dp(left);
        return p;
    }
    private int dp(float v) { return Ui.dp(this, v); }

    private TextView chip(String s, int accent) {
        TextView t = Ui.text(this, s, 13, Ui.TEXT, true);
        t.setPadding(dp(12), dp(7), dp(12), dp(7));
        t.setBackground(Ui.card(this, Ui.CARD, 18, (accent & 0x00FFFFFF) | 0xAA000000));
        return t;
    }

    private void immersive() {
        WindowInsetsControllerCompat w = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        w.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        w.hide(WindowInsetsCompat.Type.systemBars());
    }

    @Override public void onWindowFocusChanged(boolean f) { super.onWindowFocusChanged(f); if (f) immersive(); }

    @Override public void onConfigurationChanged(@NonNull Configuration c) {
        super.onConfigurationChanged(c);
        arrange();
        root.post(() -> { if (camera != null) camera.setRotation(rotation()); });
    }

    // ---------------------------------------------------------------- live data

    private void startLocation() {
        if (locationOn || !resumed || !granted(Manifest.permission.ACCESS_FINE_LOCATION)) return;
        try {
            // listen to every source: GPS gives speed, fused and network give a position sooner
            List<String> wanted = new ArrayList<>();
            wanted.add(LocationManager.GPS_PROVIDER);
            if (android.os.Build.VERSION.SDK_INT >= 31) wanted.add(LocationManager.FUSED_PROVIDER);
            wanted.add(LocationManager.NETWORK_PROVIDER);
            Location known = null;
            for (String provider : wanted) {
                if (!locations.getAllProviders().contains(provider)) continue;
                locations.requestLocationUpdates(provider, 500, 0, this, Looper.getMainLooper());
                locationOn = true;
                Location k = locations.getLastKnownLocation(provider);
                if (k != null && (known == null || k.getTime() > known.getTime())) known = k;
            }
            if (known != null && last == null) {
                map.location(known.getLatitude(), known.getLongitude(), 0, 0);
                data.update(known.getLatitude(), known.getLongitude(), 0, false);
                last = known;
            }
            if (!locations.isLocationEnabled()) toast("Turn on Location to see speed and map");
        } catch (SecurityException e) { toast("Allow Precise location in app settings"); }
    }

    @Override public void onLocationChanged(@NonNull Location l) {
        long now = SystemClock.elapsedRealtime();
        boolean recent = last != null && lastFixTime > 0 && now - lastFixTime < 3000;
        boolean gps = LocationManager.GPS_PROVIDER.equals(l.getProvider());
        // a rough network or fused fix must not override a better one that just arrived
        if (recent && !gps && (l.getAccuracy() > last.getAccuracy() * 1.5f + 5 || (!l.hasSpeed() && last.hasSpeed()))) return;
        double v = speed;
        if (l.hasSpeed()) v = l.getSpeed();
        else {
            // no speed from the chip: work it out from how far we moved
            if (speedBase == null || l.getAccuracy() > 30) { if (l.getAccuracy() <= 30) speedBase = l; if (!recent) v = 0; }
            else {
                double dt = (l.getElapsedRealtimeNanos() - speedBase.getElapsedRealtimeNanos()) / 1e9;
                if (dt >= 2) {
                    double moved = speedBase.distanceTo(l);
                    v = dt < 8 && moved > l.getAccuracy() * 0.5 ? moved / dt : 0;
                    if (v > 2) { heading = speedBase.bearingTo(l); headingValid = true; }
                    speedBase = l;
                }
            }
        }
        if (v < 0.7) v = 0;
        speed = v;
        if (l.hasBearing() && v > 2) { heading = l.getBearing(); headingValid = true; }
        last = l;
        lastFixTime = now;
        map.location(l.getLatitude(), l.getLongitude(), heading, speed);
        data.update(l.getLatitude(), l.getLongitude(), heading, headingValid);
        checkOffRoute();
        refresh();
    }
    @Override public void onProviderEnabled(@NonNull String p) { }
    @Override public void onProviderDisabled(@NonNull String p) { toast("Location is off"); }
    @Override @SuppressWarnings("deprecation") public void onStatusChanged(String p, int s, Bundle b) { }

    @Override public void onVision(List<Track> tracks, LaneState l, int w, int h, int fps) {
        lane = l;
        lead = null;
        int cars = 0;
        for (Track t : tracks) { if (t.lead) lead = t; if (t.isVehicle()) cars++; }
        lastVision = SystemClock.elapsedRealtime();
        overlay.calibration(calibrating, prefs.horizon(), prefs.center(), prefs.hood());
        // parked or crawling: floor tiles and kerbs look like lane paint, so hide lanes
        overlay.show(tracks, moving() || calibrating ? l : null, w, h, speed, prefs.mph());
        visionChip.setText("◉ AI LIVE  " + fps + " fps  •  " + cars + (cars == 1 ? " vehicle" : " vehicles"));
        visionChip.setTextColor(Ui.CYAN);
        refresh();
    }

    @Override public void onRecording(boolean on, long seconds, boolean locked) {
        recChip.setVisibility(on ? View.VISIBLE : View.GONE);
        if (on) recChip.setText(String.format(Locale.US, "%s %d:%02d", locked ? "🔒" : "● REC", seconds / 60, seconds % 60));
    }

    @Override public void onError(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }

    private boolean moving() { return speed > 3; }

    private void refresh() {
        long now = SystemClock.elapsedRealtime();
        boolean fresh = last != null && lastFixTime > 0 && now - lastFixTime < 5000;
        if (!fresh) speed = 0;
        boolean seeing = now - lastVision < 2000;
        RoadData.Road road = data.road;

        gauges.mph = prefs.mph(); gauges.hasFix = fresh; gauges.speed = speed; gauges.limit = road.limit;
        gauges.flow = data.flow; gauges.freeFlow = data.freeFlow; gauges.hasKey = !prefs.tomtomKey().isEmpty();
        gauges.leadDistance = seeing && lead != null ? lead.distance : Float.NaN;
        boolean laneKnown = seeing && moving() && lane != null && lane.position != LaneState.Position.UNKNOWN;
        gauges.laneKnown = laneKnown; gauges.laneOffset = laneKnown ? lane.offset : 0; gauges.departing = laneKnown && lane.departing;
        LaneState.LaneType type = seeing && moving() && lane != null ? lane.type : LaneState.LaneType.UNKNOWN;
        String where = type == LaneState.LaneType.LEFTMOST ? "LEFT LANE" : type == LaneState.LaneType.RIGHTMOST ? "RIGHT LANE"
                : type == LaneState.LaneType.MIDDLE ? "MIDDLE LANE" : type == LaneState.LaneType.YELLOW_ON_RIGHT ? "CHECK SIDE" : "";
        String how = !moving() ? "PARKED" : !laneKnown ? "SCANNING" : lane.position == LaneState.Position.CENTERED ? "CENTERED"
                : lane.position == LaneState.Position.DRIFT_LEFT ? "DRIFT LEFT" : "DRIFT RIGHT";
        gauges.laneLabel = where.isEmpty() ? how : laneKnown && lane.position != LaneState.Position.CENTERED ? how : where;
        gauges.invalidate();

        String name = road.name;
        if (name.isEmpty()) {
            if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) name = "Allow Precise location in settings";
            else if (!locations.isLocationEnabled()) name = "Location is turned off";
            else if (last == null) name = "Waiting for GPS (needs open sky)";
            else if (!data.roadProblem.isEmpty()) name = data.roadProblem;
            else name = "Looking up road\u2026";
        }
        if (road.lanes > 0) name += "  •  " + road.lanes + (road.lanes == 1 ? " lane" : " lanes");
        roadChip.setText(name);
        weatherChip.setText(data.weather == null ? "…" : data.weather.summary);

        Advisor.Input in = new Advisor.Input();
        in.speed = speed; in.limit = road.limit; in.flow = data.flow; in.freeFlow = data.freeFlow;
        in.lead = seeing ? lead : null;
        in.laneDeparture = seeing && moving() && lane != null && lane.departing;
        in.laneType = type;
        if (data.weather != null) in.weather = data.weather.hazard;
        in.incident = nearbyIncident();
        if (!data.trafficStatus.isEmpty()) in.incident = data.trafficStatus;
        guidance(in, fresh);
        show(Advisor.advise(in), now);
    }

    private String nearbyIncident() {
        if (last == null) return null;
        RoadData.Incident best = null; double bd = 1500;
        for (RoadData.Incident i : data.incidents) {
            double d = Geo.distance(last.getLatitude(), last.getLongitude(), i.lat, i.lon);
            boolean ahead = true;
            if (headingValid && d > 80) {
                double b = Geo.bearing(last.getLatitude(), last.getLongitude(), i.lat, i.lon);
                ahead = Math.abs(((b - heading + 540) % 360) - 180) < 45;
            }
            if (ahead && d < bd) { bd = d; best = i; }
        }
        map.incidents(data.incidents);
        return best == null ? null : "⚠ " + Ui.distance(bd, prefs.mph()) + ": " + best.text;
    }

    private void show(Advisor.Advice a, long now) {
        adviceBar.setText(a.text);
        if (a.level != lastLevel) {
            int col = a.level == Advisor.DANGER ? Ui.RED : a.level == Advisor.WARN ? Ui.AMBER : a.level == Advisor.GOOD ? Ui.GREEN : Ui.BLUE;
            GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                    new int[]{(col & 0x00FFFFFF) | 0xF0000000, Color.argb(220, Color.red(col) / 4, Color.green(col) / 4, Color.blue(col) / 4)});
            g.setCornerRadius(dp(18)); g.setStroke(dp(1.5f), col);
            adviceBar.setBackground(g);
            if (a.level == Advisor.DANGER) {
                ObjectAnimator an = ObjectAnimator.ofFloat(adviceBar, "alpha", 1f, .35f, 1f);
                an.setDuration(400); an.setRepeatCount(3); an.start();
            }
            lastLevel = a.level;
        }
        if (a.speak != null && a.level >= Advisor.WARN) {
            long wait = a.speak.equals(lastSpeech) ? (a.level == Advisor.DANGER ? 4000 : 20000) : 3000;
            if (now - lastSpoke > wait) {
                lastSpoke = now; lastSpeech = a.speak;
                if (a.level == Advisor.DANGER && tone != null) tone.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 600);
                if (prefs.voice()) say(a.speak);
            }
        }
    }

    private void say(String s) { if (ttsReady) tts.speak(s, TextToSpeech.QUEUE_FLUSH, null, "dash"); }

    // ---------------------------------------------------------------- routes

    private String spokenStep = "";

    private void guidance(Advisor.Input in, boolean fresh) {
        if (routes.isEmpty() || last == null) { turnBar.setVisibility(View.GONE); return; }
        RoadData.Route r = routes.get(selected);
        double[] pr = Geo.project(r.points, last.getLatitude(), last.getLongitude());
        double remaining = Math.max(0, r.meters - pr[1]), secs = r.seconds * remaining / Math.max(1, r.meters);
        String eta = new SimpleDateFormat("h:mm a", Locale.US).format(new Date(System.currentTimeMillis() + (long) (secs * 1000)));
        etaChip.setText("ETA " + eta + "  •  " + Math.max(1, Math.round(secs / 60)) + " min  •  " + Ui.distance(remaining, prefs.mph()) + "   ✕");
        if (remaining < 35 && fresh) { say("You have arrived"); clearRoute(); return; }
        for (RoadData.Step s : r.steps) {
            if (s.along <= pr[1] + 12) continue;
            double d = s.along - pr[1];
            String arrow = s.direction < 0 ? "⬅ " : s.direction > 0 ? "➡ " : "⬆ ";
            turnBar.setText(arrow + Ui.distance(d, prefs.mph()) + "   " + s.text);
            turnBar.setVisibility(View.VISIBLE);
            in.turnDirection = s.direction; in.turnMeters = d;
            String id = s.along + (d < 90 ? "near" : "far");
            if (fresh && prefs.voice() && d < Math.max(300, speed * 15) && !id.equals(spokenStep)) {
                spokenStep = id;
                say(d < 90 ? s.text : "In " + Ui.distance(d, prefs.mph()).replace(" mi", " miles").replace(" ft", " feet") + ", " + s.text);
            }
            return;
        }
        turnBar.setVisibility(View.GONE);
    }

    private void checkOffRoute() {
        if (routes.isEmpty() || destination == null || last == null || speed < 4) { offRoute = 0; return; }
        double off = Geo.project(routes.get(selected).points, last.getLatitude(), last.getLongitude())[0];
        offRoute = off > 80 ? offRoute + 1 : 0;
        if (offRoute > 5 && SystemClock.elapsedRealtime() - lastRoute > 20000) {
            offRoute = 0;
            say("Rerouting");
            loadRoutes(destination[0], destination[1], false);
        }
    }

    private void routeTo(double lat, double lon) {
        if (last == null) { toast("Waiting for GPS before routing"); return; }
        toast("Finding the best roads…");
        loadRoutes(lat, lon, true);
    }

    private void loadRoutes(double lat, double lon, boolean overview) {
        lastRoute = SystemClock.elapsedRealtime();
        destination = new double[]{lat, lon};
        data.routes(last.getLatitude(), last.getLongitude(), lat, lon, list -> {
            if (destination == null || destination[0] != lat || destination[1] != lon) return;
            if (list == null || list.isEmpty()) { toast("No route found. Check your connection."); return; }
            routes = list; selected = 0;
            for (int i = 1; i < list.size(); i++) if (list.get(i).seconds < list.get(selected).seconds) selected = i;
            map.routes(routes, selected, destination, overview);
            routeCards();
            if (overview) handler.postDelayed(() -> { if (!routes.isEmpty()) map.followCar(); }, 9000);
            refresh();
        });
    }

    private void routeCards() {
        routeRow.removeAllViews();
        double fastest = Double.MAX_VALUE, shortest = Double.MAX_VALUE;
        for (RoadData.Route r : routes) { fastest = Math.min(fastest, r.seconds); shortest = Math.min(shortest, r.meters); }
        for (int i = 0; i < routes.size(); i++) {
            RoadData.Route r = routes.get(i);
            final int idx = i;
            String tag = r.seconds == fastest ? "⚡ FASTEST" : r.meters == shortest ? "↘ SHORTEST" : "ALTERNATE";
            String traffic = !r.liveTraffic ? "typical time" : r.delay > 60 ? "+" + Math.round(r.delay / 60) + " min traffic" : "no delays";
            TextView t = Ui.button(this, tag + "\n" + Math.max(1, Math.round(r.seconds / 60)) + " min  •  " + Ui.distance(r.meters, prefs.mph())
                    + "\n" + traffic + (r.via.isEmpty() ? "" : "\nvia " + r.via), i == selected ? Ui.CYAN : Ui.DIM, () -> {
                selected = idx; map.routes(routes, selected, destination, false); routeCards(); refresh();
            });
            t.setTextSize(11);
            routeRow.addView(t, margins(-2, -2, i == 0 ? 0 : 6));
        }
        etaChip.setOnClickListener(v -> clearRoute());
    }

    private void clearRoute() {
        routes = new ArrayList<>(); destination = null; selected = 0;
        routeRow.removeAllViews();
        turnBar.setVisibility(View.GONE);
        etaChip.setText("Long-press the map or tap Go to");
        map.routes(routes, 0, null, false);
        map.followCar();
    }

    private void searchDialog() {
        EditText e = new EditText(this);
        e.setHint("Address or place");
        e.setSingleLine(true);
        e.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        AlertDialog d = new AlertDialog.Builder(this).setTitle("Where to?").setView(e)
                .setPositiveButton("Search", null).setNegativeButton("Cancel", null).create();
        Runnable go = () -> {
            String q = e.getText().toString().trim();
            if (q.isEmpty()) return;
            d.dismiss();
            toast("Searching…");
            data.search(q, last == null ? 0 : last.getLatitude(), last == null ? 0 : last.getLongitude(), last != null, places -> {
                if (isFinishing()) return;
                if (places == null || places.isEmpty()) { toast(places == null ? "Search failed" : "Nothing found"); return; }
                String[] names = new String[places.size()];
                for (int i = 0; i < names.length; i++) {
                    RoadData.Place p = places.get(i);
                    names[i] = (last == null ? "" : Ui.distance(Geo.distance(last.getLatitude(), last.getLongitude(), p.lat, p.lon), prefs.mph()) + "  •  ") + p.name;
                }
                new AlertDialog.Builder(this).setTitle("Choose destination")
                        .setItems(names, (x, i) -> routeTo(places.get(i).lat, places.get(i).lon)).show();
            });
        };
        e.setOnEditorActionListener((v, a, ev) -> { go.run(); return true; });
        d.show();
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> go.run());
    }

    // ---------------------------------------------------------------- settings

    private LinearLayout calibrationPanel;

    private void calibrate(boolean on) {
        calibrating = on;
        if (calibrationPanel != null) { cameraPane.removeView(calibrationPanel); calibrationPanel = null; }
        overlay.calibration(on, prefs.horizon(), prefs.center(), prefs.hood());
        if (!on) return;
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setPadding(dp(14), dp(8), dp(14), dp(10));
        p.setBackground(Ui.card(this, 0xEE0D1322, 16, Ui.VIOLET));
        p.addView(Ui.text(this, "Aim the camera (do this while parked)", 14, Ui.TEXT, true));
        slider(p, "Orange line on the horizon", "horizon", prefs.horizon(), .30f, .70f);
        slider(p, "Upright line down your lane centre", "center", prefs.center(), .30f, .70f);
        slider(p, "Blue line just above the hood", "hood", prefs.hood(), .65f, .98f);
        slider(p, "Camera height above road (m)", "height", prefs.cameraHeight(), .8f, 1.8f);
        p.addView(Ui.button(this, "Done", Ui.GREEN, () -> calibrate(false)));
        calibrationPanel = p;
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Math.min(dp(330), getResources().getDisplayMetrics().widthPixels - dp(24)), -2, Gravity.START | Gravity.CENTER_VERTICAL);
        lp.leftMargin = dp(8);
        ScrollView s = new ScrollView(this);
        s.addView(p);
        LinearLayout holder = new LinearLayout(this);
        holder.addView(s);
        calibrationPanel = holder;
        cameraPane.addView(holder, lp);
    }

    private void slider(LinearLayout p, String label, String key, float value, float min, float max) {
        TextView t = Ui.text(this, String.format(Locale.US, "%s: %.2f", label, value), 11, Ui.DIM, false);
        SeekBar s = new SeekBar(this);
        s.setMax(200);
        s.setProgress(Math.round((value - min) / (max - min) * 200));
        s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar b, int v, boolean user) {
                if (!user) return;
                float f = min + (max - min) * v / 200f;
                prefs.edit().putFloat(key, f).apply();
                t.setText(String.format(Locale.US, "%s: %.2f", label, f));
                overlay.calibration(true, prefs.horizon(), prefs.center(), prefs.hood());
            }
            @Override public void onStartTrackingTouch(SeekBar b) { }
            @Override public void onStopTrackingTouch(SeekBar b) { }
        });
        p.addView(t);
        p.addView(s);
    }

    private void settingsDialog() {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setPadding(dp(22), dp(8), dp(22), 0);
        p.addView(Ui.text(this, "TomTom API key (live road speed, traffic, incidents). Free at my.tomtom.com", 12, Ui.DIM, false));
        EditText key = new EditText(this);
        key.setSingleLine(true);
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        key.setText(prefs.tomtomKey());
        key.setHint("Paste key");
        p.addView(key);
        CheckBox mph = check(p, "Miles per hour", prefs.mph());
        CheckBox voice = check(p, "Spoken alerts and directions", prefs.voice());
        CheckBox auto = check(p, "Start recording when the app opens", prefs.autoRecord());
        CheckBox dark = check(p, "Dark map", prefs.mapDark());
        p.addView(Ui.text(this, "Loop recording storage in GB (locked clips are kept)", 12, Ui.DIM, false));
        EditText gb = new EditText(this);
        gb.setInputType(InputType.TYPE_CLASS_NUMBER);
        gb.setText(String.valueOf(prefs.storageGb()));
        p.addView(gb);
        p.addView(Ui.text(this, "Driver aid only. Distances and lane readings are camera estimates. Keep your eyes on the road and set this up while parked.", 11, Ui.AMBER, false));
        ScrollView s = new ScrollView(this);
        s.addView(p);
        new AlertDialog.Builder(this).setTitle("Setup").setView(s).setPositiveButton("Save", (d, w) -> {
            int g = 8;
            try { g = Math.max(1, Math.min(200, Integer.parseInt(gb.getText().toString().trim()))); } catch (NumberFormatException ignored) { }
            prefs.edit().putString("tomtom", key.getText().toString().trim()).putBoolean("mph", mph.isChecked())
                    .putBoolean("voice", voice.isChecked()).putBoolean("autorec", auto.isChecked())
                    .putBoolean("dark", dark.isChecked()).putInt("gb", g).apply();
            data.refreshNow();
            map.loadStyle();
            if (last != null) data.update(last.getLatitude(), last.getLongitude(), heading, headingValid);
            refresh();
        }).setNegativeButton("Cancel", null).show();
    }

    private CheckBox check(LinearLayout p, String s, boolean v) {
        CheckBox c = new CheckBox(this);
        c.setText(s); c.setChecked(v);
        p.addView(c);
        return c;
    }

    private void lockClip(String message) {
        toast(camera != null && camera.lockClip() ? message : "Start recording first");
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    // ---------------------------------------------------------------- impact + lifecycle

    @Override public void onSensorChanged(SensorEvent e) {
        float x = e.values[0], y = e.values[1], z = e.values[2];
        long now = SystemClock.elapsedRealtime();
        if (x * x + y * y + z * z > 24 * 24 && now - lastImpact > 10000) {
            lastImpact = now;
            if (camera != null && camera.lockClip()) toast("Hard impact: clip locked");
        }
    }
    @Override public void onAccuracyChanged(Sensor s, int a) { }

    private final Runnable tick = new Runnable() {
        @Override public void run() { refresh(); handler.postDelayed(this, 1000); }
    };

    @Override protected void onStart() { super.onStart(); map.view.onStart(); }
    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        map.view.onResume();
        if (started && granted(Manifest.permission.CAMERA)) { if (cameraStarted) camera.resume(); else startCamera(); }
        startLocation();
        Sensor a = sensors.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
        if (a != null) sensors.registerListener(this, a, SensorManager.SENSOR_DELAY_GAME);
        handler.post(tick);
    }
    @Override protected void onPause() {
        resumed = false;
        handler.removeCallbacks(tick);
        sensors.unregisterListener(this);
        locations.removeUpdates(this);
        locationOn = false;
        if (camera != null) camera.pause();
        map.view.onPause();
        super.onPause();
    }
    @Override protected void onStop() { map.view.onStop(); super.onStop(); }
    @Override public void onLowMemory() { super.onLowMemory(); map.view.onLowMemory(); }
    @Override protected void onSaveInstanceState(@NonNull Bundle out) { super.onSaveInstanceState(out); map.view.onSaveInstanceState(out); }
    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (camera != null) camera.close();
        data.close();
        if (tts != null) tts.shutdown();
        if (tone != null) tone.release();
        map.view.onDestroy();
        super.onDestroy();
    }
}
