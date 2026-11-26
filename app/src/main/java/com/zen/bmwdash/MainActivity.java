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
import com.zen.bmwdash.core.Conditions;
import com.zen.bmwdash.core.Geo;
import com.zen.bmwdash.core.LaneState;
import com.zen.bmwdash.core.Predictor;
import com.zen.bmwdash.core.Safety;
import android.graphics.Bitmap;
import android.os.VibrationEffect;
import android.os.Vibrator;
import java.util.HashSet;
import java.util.Set;
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
    private HorizontalScrollView routeScroll, buttonScroll;
    private SearchPanel searchPanel;
    private long showButtonsUntil;
    private TextView arButton;
    private SignalView signalView;
    private TextView trafficChip, weatherBanner, weatherButton;
    private boolean viewBlocked, clearShown;
    private int remindedKind;
    private long bridgeSaidAt = -1000000, viewSaidAt = -1000000;
    private final Predictor predictor = new Predictor();
    private Safety.Event predicted;
    private long predictedAt;
    private boolean navShown;
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
    private final Safety safety = new Safety();
    private CarId carId;
    private AlarmView alarm;
    private TextView carCard, nightButton;
    private List<Track> tracks = new ArrayList<>();
    private int light, nightShown = -1, speedLeadId = -1;
    private float brightness = 128;
    private boolean autoDark;
    private double theirSpeed = Double.NaN, recentSpeed;
    private long recentSpeedAt, lastBuzz;
    private final Set<String> announced = new HashSet<>();
    private AlertDialog sos;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        prefs = new Prefs(this);
        data = new RoadData(prefs);
        data.onChange = this::refresh;
        carId = new CarId(prefs);
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
        turnBar = Ui.text(this, "", 14, Ui.TEXT, true);
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
        carCard = chip("", Ui.GREEN);
        carCard.setTextSize(13);
        carCard.setVisibility(View.GONE);
        LinearLayout.LayoutParams cc = new LinearLayout.LayoutParams(-2, -2);
        cc.topMargin = dp(6);
        top.addView(carCard, cc);
        trafficChip = chip("", Ui.AMBER);
        trafficChip.setTextSize(13);
        trafficChip.setVisibility(View.GONE);
        LinearLayout.LayoutParams tc = new LinearLayout.LayoutParams(-2, -2);
        tc.gravity = Gravity.CENTER_HORIZONTAL;
        tc.topMargin = dp(6);
        top.addView(trafficChip, tc);
        weatherBanner = chip("", Ui.CYAN);
        weatherBanner.setTextSize(13);
        weatherBanner.setVisibility(View.GONE);
        LinearLayout.LayoutParams wb = new LinearLayout.LayoutParams(-2, -2);
        wb.gravity = Gravity.CENTER_HORIZONTAL;
        wb.topMargin = dp(6);
        top.addView(weatherBanner, wb);
        signalView = new SignalView(this);
        LinearLayout.LayoutParams sv = new LinearLayout.LayoutParams(dp(210), dp(48));
        sv.gravity = Gravity.CENTER_HORIZONTAL;
        sv.topMargin = dp(6);
        top.addView(signalView, sv);
        // always reachable while a route is active, even when the other buttons are tucked away
        arButton = Ui.button(this, "", Ui.CYAN, () -> {
            prefs.edit().putBoolean("arroute", !prefs.arRoute()).apply();
            showArLabel();
            refresh();
        });
        arButton.setVisibility(View.GONE);
        LinearLayout.LayoutParams ab = new LinearLayout.LayoutParams(-2, -2);
        ab.gravity = Gravity.END;
        ab.topMargin = dp(6);
        top.addView(arButton, ab);
        showArLabel();
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
        buttons.addView(Ui.button(this, "🔍 Go to", Ui.CYAN, this::openSearch), margins(-2, -2, 6));
        trafficButton = Ui.button(this, "🚦 Traffic", Ui.GREEN, () -> {
            if (prefs.tomtomKey().isEmpty()) { toast("Add your free TomTom key in Setup"); settingsDialog(); return; }
            prefs.edit().putBoolean("traffic", !prefs.mapTraffic()).apply();
            map.loadStyle();
            toast(prefs.mapTraffic() ? "Traffic layer on" : "Traffic layer off");
        });
        buttons.addView(trafficButton, margins(-2, -2, 6));
        buttons.addView(Ui.button(this, "⌖ Aim", Ui.VIOLET, () -> calibrate(!calibrating)), margins(-2, -2, 6));
        buttons.addView(Ui.button(this, "⚙ Setup", Ui.BLUE, this::settingsDialog), margins(-2, -2, 6));
        nightButton = Ui.button(this, "\uD83C\uDF19 Auto", Ui.VIOLET, this::cycleNight);
        buttons.addView(nightButton, margins(-2, -2, 6));
        weatherButton = Ui.button(this, "", Ui.CYAN, () -> {
            prefs.edit().putInt("wxmode", (prefs.weatherMode() + 1) % 5).apply();
            showWeatherLabel();
            refresh();
        });
        showWeatherLabel();
        buttons.addView(weatherButton, margins(-2, -2, 6));
        buttons.addView(Ui.button(this, "\uD83D\uDDFA Map", Ui.CYAN, this::cycleMap), margins(-2, -2, 6));
        buttons.addView(Ui.button(this, "\u26A0 Report", Ui.AMBER, this::reportDialog), margins(-2, -2, 6));
        buttons.addView(Ui.button(this, "\uD83D\uDCCA Trip", Ui.GREEN, this::tripDialog), margins(-2, -2, 6));
        buttons.addView(Ui.button(this, "\uD83C\uDD98 SOS", Ui.RED, () -> sosDialog(false)), margins(-2, -2, 6));
        HorizontalScrollView bs = new HorizontalScrollView(this);
        bs.setHorizontalScrollBarEnabled(false);
        bs.addView(buttons);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
        blp.topMargin = dp(6);
        bottom.addView(bs, blp);
        buttonScroll = bs;
        // while guiding, buttons tuck away; touching the camera picture brings them back
        overlay.setOnClickListener(v -> { showButtonsUntil = SystemClock.elapsedRealtime() + 8000; refresh(); });
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
        TextView credit = Ui.text(this, "© OpenStreetMap • OpenFreeMap • TomTom • Open-Meteo • NWS • IEM radar", 8, Ui.DIM, false);
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
        FrameLayout screen = new FrameLayout(this);
        screen.addView(root, new FrameLayout.LayoutParams(-1, -1));
        alarm = new AlarmView(this);
        screen.addView(alarm, new FrameLayout.LayoutParams(-1, -1));
        setContentView(screen);
        arrange();
        showNightLabel();
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
        weights();
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
        if (v > 5) { recentSpeed = v; recentSpeedAt = now; }
        if (l.hasBearing() && v > 2) { heading = l.getBearing(); headingValid = true; }
        last = l;
        lastFixTime = now;
        map.location(l.getLatitude(), l.getLongitude(), heading, speed);
        data.path = routes.isEmpty() ? null : routes.get(selected).points;
        data.update(l.getLatitude(), l.getLongitude(), heading, headingValid);
        checkOffRoute();
        refresh();
    }
    @Override public void onProviderEnabled(@NonNull String p) { }
    @Override public void onProviderDisabled(@NonNull String p) { toast("Location is off"); }
    @Override @SuppressWarnings("deprecation") public void onStatusChanged(String p, int s, Bundle b) { }

    @Override public void onScene(int signal, float bright, boolean blocked) {
        light = signal;
        viewBlocked = blocked;
        signalView.show(signal);
        brightness = bright;
        // hysteresis so auto night view does not flicker under street lamps
        if (bright < 55) autoDark = true; else if (bright > 80) autoDark = false;
        applyNight();
    }

    @Override public boolean wantsCar(int id, int widthPx) { return carId.wants(id, widthPx); }

    @Override public void onCarPicture(int id, Bitmap crop, int widthPx) {
        carId.identify(id, crop, widthPx, (car, r) -> {
            if (!r.emergency.equals("none") && lead != null && lead.id == car && announced.add("em" + car)) {
                String who = r.emergency.equals("police") ? "Police vehicle" : r.emergency.equals("ambulance") ? "Ambulance" : "Fire vehicle";
                toast(who + " ahead");
                if (prefs.voice()) say(who + " ahead");
            }
            refresh();
        });
    }

    @Override public void onVision(List<Track> seen, LaneState l, int w, int h, int fps) {
        lane = l;
        tracks = seen;
        CarId.Result known = null;
        for (Track t : seen) if (t.lead) known = carId.get(t.id);
        overlay.leadName = known == null ? "" : known.label();
        lead = null;
        int cars = 0;
        for (Track t : seen) { if (t.lead) lead = t; if (t.isVehicle()) cars++; }
        lastVision = SystemClock.elapsedRealtime();
        overlay.calibration(calibrating, prefs.horizon(), prefs.center(), prefs.hood());
        long seenAt = SystemClock.elapsedRealtime();
        Safety.Event guess = predictor.update(seenAt, speed, seen);
        if (guess != null) { predicted = guess; predictedAt = seenAt; }
        // parked or crawling: floor tiles and kerbs look like lane paint, so hide lanes
        overlay.show(seen, calibrating || (moving() && !lanesUnsafe(l)) ? l : null, w, h, speed, prefs.mph());
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
        boolean hidden = seeing && lanesUnsafe(lane);
        boolean laneKnown = seeing && moving() && !hidden && lane != null && lane.position != LaneState.Position.UNKNOWN;
        gauges.laneKnown = laneKnown; gauges.laneOffset = laneKnown ? lane.offset : 0; gauges.departing = laneKnown && lane.departing;
        LaneState.LaneType type = seeing && moving() && !hidden && lane != null ? lane.type : LaneState.LaneType.UNKNOWN;
        String where = type == LaneState.LaneType.LEFTMOST ? "LEFT LANE" : type == LaneState.LaneType.RIGHTMOST ? "RIGHT LANE"
                : type == LaneState.LaneType.MIDDLE ? "MIDDLE LANE" : type == LaneState.LaneType.YELLOW_ON_RIGHT ? "CHECK SIDE" : "";
        String how = !moving() ? "PARKED" : hidden ? "LANES NOT VISIBLE" : !laneKnown ? "SCANNING" : lane.position == LaneState.Position.CENTERED ? "CENTERED"
                : lane.position == LaneState.Position.DRIFT_LEFT ? "DRIFT LEFT" : "DRIFT RIGHT";
        gauges.laneLabel = where.isEmpty() ? how : laneKnown && lane.position != LaneState.Position.CENTERED ? how : where;
        gauges.invalidate();
        applyNight();

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
        in.laneDeparture = seeing && moving() && !hidden && lane != null && lane.departing;
        in.laneType = type;
        if (data.weather != null) in.weather = data.weather.hazard;
        in.incident = hazardAhead();
        int kind = weatherKind();
        boolean ice = data.weather != null && data.weather.ice && prefs.weatherMode() != 1;
        in.safeGap = Conditions.gap(kind, ice);
        in.suggested = Conditions.suggested(road.limit, kind);
        in.aheadFlow = data.aheadFlow;
        if (!data.trafficStatus.isEmpty()) in.incident = data.trafficStatus;
        guidance(in, fresh);
        driveFocus();
        Safety.Input s = new Safety.Input();
        s.t = now; s.speed = speed; s.seeing = seeing; s.tracks = tracks; s.limit = road.limit;
        s.safeGap = in.safeGap; s.light = seeing ? light : 0;
        s.caution = Conditions.caution(kind, ice);
        Safety.Event event = safety.update(s);
        // a predicted move only takes the banner when nothing more serious is happening
        if (seeing && predicted != null && now - predictedAt < 3000 && (event == null || event.level < predicted.level))
            event = predicted;
        trafficAhead(fresh);
        weatherSafety(kind, ice, road.limit, fresh, seeing, now);
        Advisor.Advice advice = Advisor.advise(in);
        if (event != null && event.level >= advice.level) {
            String text = event.text;
            if (event.code.equals("limit") && !Double.isNaN(road.limit))
                text = "Speed limit now " + Ui.speed(road.limit, prefs.mph()) + " " + Ui.unit(prefs.mph());
            show(event.level, text, event.speak, true, now);
        } else show(advice.level, advice.text, advice.speak, advice.level >= Advisor.WARN, now);
        boolean urgent = event != null && event.level == Advisor.DANGER;
        if (urgent) {
            alarm.show(event.code.equals("brake") ? "BRAKE!" : "STOP!");
            if (now - lastBuzz > 900) {
                lastBuzz = now;
                Vibrator v = getSystemService(Vibrator.class);
                if (v != null) v.vibrate(VibrationEffect.createWaveform(new long[]{0, 250, 90, 250}, -1));
                if (tone != null) tone.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 700);
            }
        } else alarm.hide();
        carCard(seeing, fresh);
        if (!seeing) signalView.show(0);
    }

    private void carCard(boolean seeing, boolean fresh) {
        if (!seeing || lead == null || Float.isNaN(lead.distance)) { carCard.setVisibility(View.GONE); speedLeadId = -1; return; }
        if (lead.id != speedLeadId) { speedLeadId = lead.id; theirSpeed = Double.NaN; }
        if (fresh && !Float.isNaN(lead.rangeRate)) {
            double v = Math.max(0, speed + lead.rangeRate);
            theirSpeed = Double.isNaN(theirSpeed) ? v : theirSpeed + 0.2 * (v - theirSpeed);
        }
        CarId.Result r = carId.get(lead.id);
        String name = r != null ? r.label() : prefs.aiKey().isEmpty() ? "Car ahead (add AI key for make and model)"
                : !carId.problem.isEmpty() ? "Car ahead (" + carId.problem + ")" : "Identifying car ahead\u2026";
        if (r != null && r.emergency.equals("police")) name = "\uD83D\uDE93 Police  \u2022  " + name;
        String info = (Double.isNaN(theirSpeed) || !fresh ? "speed --" : "\u2248 " + Ui.speed(theirSpeed, prefs.mph()) + " " + Ui.unit(prefs.mph()))
                + "   \u2022   " + Ui.range(lead.distance, prefs.mph())
                + (speed > 3 ? String.format(Locale.US, "   \u2022   %.1f s gap", lead.distance / speed) : "");
        carCard.setText("\uD83D\uDE98 " + name + "\n" + info);
        carCard.setVisibility(View.VISIBLE);
    }

    private int weatherKind() {
        int m = prefs.weatherMode();
        if (m == 1) return Conditions.CLEAR;
        if (m == 2) return Conditions.RAIN;
        if (m == 3) return Conditions.SNOW;
        if (m == 4) return Conditions.FOG;
        return data.weather == null ? Conditions.CLEAR : data.weather.kind;
    }

    private void showWeatherLabel() {
        weatherButton.setText("\u2614 " + new String[]{"Auto", "Off", "Rain", "Snow", "Fog"}[prefs.weatherMode()]);
    }

    /** Snow hides paint: only trust lanes there when both sides are really seen. */
    private boolean lanesUnsafe(LaneState l) {
        if (viewBlocked) return true;
        int k = weatherKind();
        return (k == Conditions.SNOW || k == Conditions.ICE) && l != null
                && !(l.hasLeft && l.hasRight && !l.estimatedLeft && !l.estimatedRight);
    }

    /** One banner for weather mode, official alerts, what is coming and a blocked camera. */
    private void weatherSafety(int kind, boolean ice, double limit, boolean fresh, boolean seeing, long now) {
        map.refreshRadar();
        StringBuilder b = new StringBuilder();
        if (kind != Conditions.CLEAR) {
            b.append(new String[]{"", "\u2614 RAIN MODE", "\u2744 SNOW MODE", "\u2744 ICE MODE", "\u2601 FOG MODE"}[kind]);
            if (!Double.isNaN(limit)) {
                // round down to a speed that can be read at a glance
                double unit = prefs.mph() ? 2.23694 : 3.6;
                long top = (long) (Conditions.suggested(limit, kind) * unit / 5) * 5;
                if (top > 0) b.append("  \u2022  suggested ").append(top).append(' ').append(Ui.unit(prefs.mph()));
            }
            b.append("  \u2022  headlights on");
        } else if (ice) b.append("\u2744 Near freezing: bridges and overpasses ice first");
        if (!data.alerts.isEmpty()) {
            RoadData.Alert a = data.alerts.get(0);
            line(b, "\u26A0 " + a.event + (data.alerts.size() > 1 ? "  (+" + (data.alerts.size() - 1) + " more)" : ""));
            if (prefs.voice() && announced.add("nws" + a.event)) say("National Weather Service alert: " + a.event);
        }
        if (!data.outlook.isEmpty()) {
            line(b, "\u27A1 " + data.outlook);
            if (prefs.voice() && speed > 3 && announced.add("out" + data.outlook.split(" in ")[0])) say(data.outlook);
        }
        boolean blind = seeing && viewBlocked && speed > 5;
        if (blind) {
            line(b, "\uD83D\uDEAB Camera view unclear: check the glass, do not rely on camera alerts");
            if (now - viewSaidAt > 300_000) { viewSaidAt = now; if (prefs.voice()) say("Camera view unclear. Check the windshield."); }
        }
        if (ice && fresh && speed > 5 && headingValid && now - bridgeSaidAt > 60_000
                && data.bridgeAhead(last.getLatitude(), last.getLongitude(), heading)) {
            bridgeSaidAt = now;
            toast("Bridge ahead: possible ice");
            if (prefs.voice()) say("Bridge ahead. Possible ice.");
        }
        if (now - bridgeSaidAt < 12_000) line(b, "\u2744 Bridge ahead: possible ice");
        if (kind != Conditions.CLEAR && kind != remindedKind && fresh && speed > 2) {
            remindedKind = kind;
            String tip = kind == Conditions.SNOW || kind == Conditions.ICE ? "Snow mode. Headlights on, and make sure all snow is cleared off the car."
                    : (kind == Conditions.RAIN ? "Rain mode." : "Fog mode.") + " Turn your headlights on.";
            if (prefs.voice()) say(tip); else toast(tip);
        }
        weatherBanner.setText(b);
        weatherBanner.setVisibility(b.length() == 0 ? View.GONE : View.VISIBLE);
    }

    private static void line(StringBuilder b, String s) { if (b.length() > 0) b.append('\n'); b.append(s); }

    /** Nearest slow traffic, jam or closure further along, shown early so there is time to react or reroute. */
    private void trafficAhead(boolean fresh) {
        if (last == null || !fresh) { trafficChip.setVisibility(View.GONE); return; }
        double best = 1e9;
        String text = null, key = null, spoken = null;
        for (RoadData.Jam j : data.jams) {
            double d = further(j.lat, j.lon);
            if (d < 150 || d >= best) continue;
            best = d; key = "j" + Math.round(j.lat * 100) + "," + Math.round(j.lon * 100);
            text = "Slow traffic in " + Ui.distance(d, prefs.mph()) + "  \u2022  " + Ui.speed(j.speed, prefs.mph()) + " " + Ui.unit(prefs.mph())
                    + " (usually " + Ui.speed(j.free, prefs.mph()) + ")";
            spoken = "Slow traffic ahead in " + spokenDistance(d);
        }
        for (RoadData.Incident i : data.incidents) {
            if (i.category != 6 && i.category != 8 && i.category != 1) continue;
            double d = further(i.lat, i.lon);
            if (d < 150 || d >= best) continue;
            best = d; key = "t" + i.category + Math.round(i.lat * 100) + "," + Math.round(i.lon * 100);
            text = i.title + " in " + Ui.distance(d, prefs.mph());
            // nearer ones are already announced by the hazard warning
            spoken = d > 1600 ? i.title + " ahead in " + spokenDistance(d) : null;
        }
        if (text == null) { trafficChip.setVisibility(View.GONE); return; }
        trafficChip.setText("\uD83D\uDEA6 " + text);
        trafficChip.setVisibility(View.VISIBLE);
        if (spoken != null && speed > 3 && prefs.voice() && announced.add(key)) say(spoken);
    }

    private String spokenDistance(double d) { return Ui.distance(d, prefs.mph()).replace(" mi", " miles").replace(" ft", " feet"); }

    /** metres to a point further along the route (or ahead of us without one), -1 if not on the way */
    private double further(double lat, double lon) {
        if (routes.isEmpty()) return headingValid ? ahead(lat, lon, 5500) : -1;
        double[][] path = routes.get(selected).points;
        double[] there = Geo.project(path, lat, lon);
        if (there[0] > 60) return -1;
        double d = there[1] - Geo.project(path, last.getLatitude(), last.getLongitude())[1];
        return d > 0 && d < 8000 ? d : -1;
    }

    /** Closest closure, incident, speed camera or saved report in front of us; speaks each one once. */
    private String hazardAhead() {
        map.incidents(data.incidents);
        if (last == null) return null;
        double best = 1e9;
        String text = null, key = null, spoken = null;
        for (RoadData.Incident i : data.incidents) {
            double d = ahead(i.lat, i.lon, 1600);
            // closures and accidents outrank nearer but minor items
            double rank = d - (i.category == 8 || i.category == 1 ? 700 : 0);
            if (d >= 0 && rank < best) {
                best = rank; key = "i" + i.title + Math.round(i.lat * 500) + "," + Math.round(i.lon * 500);
                text = "\u26A0 " + i.title + " in " + Ui.distance(d, prefs.mph()) + ": " + i.text; spoken = i.title + " ahead";
            }
        }
        for (RoadData.Camera c : data.cameras) {
            double d = ahead(c.lat, c.lon, 600);
            if (d >= 0 && d < best) {
                best = d; key = "c" + c.lat + "," + c.lon;
                text = "\uD83D\uDCF7 Speed camera in " + Ui.distance(d, prefs.mph()); spoken = "Speed camera ahead";
            }
        }
        for (String[] m : reports()) {
            double lat = Double.parseDouble(m[1]), lon = Double.parseDouble(m[2]);
            double d = ahead(lat, lon, 900);
            if (d >= 0 && d < best && System.currentTimeMillis() - Long.parseLong(m[3]) > 60_000) {
                best = d; key = "r" + m[3];
                text = "\u26A0 " + m[0] + " reported by you, " + Ui.distance(d, prefs.mph()); spoken = m[0] + " reported ahead";
            }
        }
        if (key != null && speed > 3 && announced.add(key) && prefs.voice()) say(spoken);
        return text;
    }

    /** distance in metres if the point is within range and in front of us, otherwise -1 */
    private double ahead(double lat, double lon, double range) {
        double d = Geo.distance(last.getLatitude(), last.getLongitude(), lat, lon);
        if (d > range) return -1;
        if (headingValid && d > 60) {
            double b = Geo.bearing(last.getLatitude(), last.getLongitude(), lat, lon);
            if (Math.abs(((b - heading + 540) % 360) - 180) > 40) return -1;
        }
        return d;
    }

    private List<String[]> reports() {
        List<String[]> out = new ArrayList<>();
        for (String r : prefs.reports().split(";")) {
            String[] m = r.split(",");
            if (m.length != 4) continue;
            long age = System.currentTimeMillis() - Long.parseLong(m[3]);
            // police move on, potholes stay for a while
            if (age < (m[0].equals("Police") ? 7_200_000L : 30L * 86_400_000L)) out.add(m);
        }
        return out;
    }

    private void reportDialog() {
        if (last == null) { toast("Waiting for GPS"); return; }
        Location here = new Location(last);
        String[] kinds = {"Police", "Object on road", "Pothole", "Road closed"};
        new AlertDialog.Builder(this).setTitle("Report at this spot").setItems(kinds, (d, i) -> {
            StringBuilder b = new StringBuilder();
            List<String[]> all = reports();
            for (int k = Math.max(0, all.size() - 80); k < all.size(); k++) b.append(String.join(",", all.get(k))).append(';');
            long when = System.currentTimeMillis();
            b.append(kinds[i]).append(',').append(here.getLatitude()).append(',').append(here.getLongitude()).append(',').append(when).append(';');
            prefs.edit().putString("reports", b.toString()).apply();
            toast("Saved. You will be warned next time you approach.");
        }).show();
    }

    private void tripDialog() {
        Safety.Trip t = safety.trip;
        boolean mph = prefs.mph();
        new AlertDialog.Builder(this).setTitle("Safety score: " + t.score() + " / 100").setMessage(String.format(Locale.US,
                "Distance: %s\nDriving time: %d min\nTop speed: %d %s\nTime over the limit: %d s\nTime following too close: %d s\nHard brakes: %d\nCollision warnings: %d",
                Ui.distance(t.meters, mph), Math.round(t.movingSec / 60), Ui.speed(t.topSpeed, mph), Ui.unit(mph),
                Math.round(t.speedingSec), Math.round(t.closeSec), t.hardBrakes, t.collisionWarnings))
                .setPositiveButton("OK", null).show();
    }

    private void show(int level, String text, String speak, boolean aloud, long now) {
        adviceBar.setText(text);
        if (level != lastLevel) {
            int col = level == Advisor.DANGER ? Ui.RED : level == Advisor.WARN ? Ui.AMBER : level == Advisor.GOOD ? Ui.GREEN : Ui.BLUE;
            GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                    new int[]{(col & 0x00FFFFFF) | 0xF0000000, Color.argb(220, Color.red(col) / 4, Color.green(col) / 4, Color.blue(col) / 4)});
            g.setCornerRadius(dp(18)); g.setStroke(dp(1.5f), col);
            adviceBar.setBackground(g);
            if (level == Advisor.DANGER) {
                ObjectAnimator an = ObjectAnimator.ofFloat(adviceBar, "alpha", 1f, .35f, 1f);
                an.setDuration(400); an.setRepeatCount(3); an.start();
            }
            lastLevel = level;
        }
        if (speak != null && aloud) {
            long wait = speak.equals(lastSpeech) ? (level == Advisor.DANGER ? 4000 : 20000) : 2500;
            if (now - lastSpoke > wait) {
                lastSpoke = now; lastSpeech = speak;
                if (level == Advisor.DANGER && tone != null) tone.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 600);
                if (prefs.voice()) say(speak);
            }
        }
    }

    private void sosDialog(boolean crash) {
        if (isFinishing() || (sos != null && sos.isShowing())) return;
        String number = prefs.sosNumber();
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(crash ? "Hard crash detected. Are you OK?" : "Emergency")
                .setMessage(number.isEmpty() ? "Add an emergency contact in Setup to text your location."
                        : "Text your location to " + number + " or call 911.")
                .setPositiveButton("Call 911", (d, w) -> launch(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:911"))))
                .setNegativeButton("I'm OK", null);
        if (!number.isEmpty()) b.setNeutralButton("Text contact", (d, w) -> {
            Intent i = new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number)));
            i.putExtra("sms_body", "I need help. " + (last == null ? "Location unknown."
                    : "My location: https://maps.google.com/?q=" + last.getLatitude() + "," + last.getLongitude()));
            launch(i);
        });
        sos = b.show();
        if (crash) {
            say("Hard crash detected. Are you O K? Tap call 9 1 1 if you need help.");
            if (tone != null) tone.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 1500);
        }
    }

    private void launch(Intent i) {
        try { startActivity(i); } catch (RuntimeException e) { toast("No app available for that"); }
    }

    private void cycleNight() {
        prefs.edit().putInt("night", (prefs.night() + 1) % 4).apply();
        showNightLabel();
        applyNight();
    }
    private void showNightLabel() {
        String[] names = {"Auto", "Day", "Night", "Green"};
        nightButton.setText("\uD83C\uDF19 " + names[prefs.night()]);
    }
    private void applyNight() {
        int m = prefs.night();
        int view = m == 0 ? (autoDark ? 1 : 0) : m - 1;
        if (camera != null) camera.setNight(view > 0);
        boolean clear = weatherKind() != Conditions.CLEAR;
        if (view != nightShown || clear != clearShown) { nightShown = view; clearShown = clear; NightView.apply(preview, view, clear); }
    }

    private void say(String s) { if (ttsReady) tts.speak(s, TextToSpeech.QUEUE_FLUSH, null, "dash"); }

    // ---------------------------------------------------------------- routes

    private String spokenStep = "";

    private void guidance(Advisor.Input in, boolean fresh) {
        if (routes.isEmpty() || last == null) { turnBar.setVisibility(View.GONE); showGuide(null); return; }
        RoadData.Route r = routes.get(selected);
        double[] pr = Geo.project(r.points, last.getLatitude(), last.getLongitude());
        double remaining = Math.max(0, r.meters - pr[1]), secs = r.seconds * remaining / Math.max(1, r.meters);
        String eta = new SimpleDateFormat("h:mm a", Locale.US).format(new Date(System.currentTimeMillis() + (long) (secs * 1000)));
        etaChip.setText("ETA " + eta + "  •  " + Math.max(1, Math.round(secs / 60)) + " min  •  " + Ui.distance(remaining, prefs.mph()) + "   ✕");
        if (remaining < 35 && fresh) { say("You have arrived"); clearRoute(); return; }
        ArRenderer.Guide ar = new ArRenderer.Guide();
        ar.route = r.points; ar.along = pr[1]; ar.offRoute = pr[0]; ar.fixTime = lastFixTime;
        ar.speed = fresh ? speed : 0;
        ar.heading = headingValid && speed > 3 ? heading : Double.NaN;
        for (RoadData.Step s : r.steps) {
            if (s.along <= pr[1] + 12) continue;
            ar.turnAlong = s.along; ar.direction = s.direction;
            ar.label = Ui.distance(s.along - pr[1], prefs.mph()) + (s.direction < 0 ? "  LEFT" : s.direction > 0 ? "  RIGHT" : "  AHEAD");
            break;
        }
        showGuide(ar);
        for (RoadData.Step s : r.steps) {
            if (s.along <= pr[1] + 12) continue;
            double d = s.along - pr[1];
            String arrow = s.direction < 0 ? "⬅ " : s.direction > 0 ? "➡ " : "⬆ ";
            // big arrow and distance, small street name: readable at a glance
            String big = arrow + Ui.distance(d, prefs.mph());
            android.text.SpannableString card = new android.text.SpannableString(big + "\n" + s.text);
            card.setSpan(new android.text.style.RelativeSizeSpan(1.35f), 0, big.length(), 0);
            turnBar.setText(card);
            turnBar.setMaxLines(3);
            map.nextTurn(Geo.slice(r.points, Math.max(0, s.along - 50), s.along + 40), d);
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

    private void showArLabel() {
        boolean on = prefs.arRoute();
        arButton.setText(on ? "\u25C9 3D route ON" : "\u25CB 3D route OFF");
        arButton.setAlpha(on ? 1f : .75f);
    }

    private void showGuide(ArRenderer.Guide g) {
        arButton.setVisibility(g == null ? View.GONE : View.VISIBLE);
        overlay.guide(prefs.arRoute() ? g : null, camera == null ? .65f : camera.focal(), prefs.horizon(), prefs.center(), prefs.cameraHeight());
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
        showGuide(null);
        map.routes(routes, 0, null, false);
        map.followCar();
    }

    private void openSearch() {
        if (searchPanel == null) searchPanel = new SearchPanel(this, prefs, data, this::routeTo);
        searchPanel.open(last == null ? 0 : last.getLatitude(), last == null ? 0 : last.getLongitude(), last != null, speed > 3);
    }

    @Override protected void onActivityResult(int request, int result, Intent intent) {
        super.onActivityResult(request, result, intent);
        if (request == SearchPanel.VOICE && result == RESULT_OK && intent != null && searchPanel != null) {
            ArrayList<String> said = intent.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS);
            if (said != null && !said.isEmpty()) searchPanel.heard(said.get(0));
        }
    }

    /** Guidance view: bigger map, calmer screen. Only weights change, so the camera keeps running. */
    private void driveFocus() {
        boolean nav = !routes.isEmpty();
        if (nav != navShown) { navShown = nav; weights(); map.navigation(nav); }
        boolean calm = nav && speed > 3 && !calibrating;
        buttonScroll.setVisibility(!calm || SystemClock.elapsedRealtime() < showButtonsUntil ? View.VISIBLE : View.GONE);
        routeScroll.setVisibility(calm ? View.GONE : View.VISIBLE);
        visionChip.setVisibility(calm ? View.GONE : View.VISIBLE);
    }

    /** The camera gets most of the screen; the Map button picks small, large or hidden. */
    private void weights() {
        Configuration c = getResources().getConfiguration();
        float ratio = c.screenWidthDp / (float) Math.max(1, c.screenHeightDp);
        boolean wide = ratio > 1.35f, square = !wide && ratio > 0.75f;
        int size = prefs.mapSize();
        mapPane.setVisibility(size == 2 ? View.GONE : View.VISIBLE);
        if (wide) {
            weight(cameraPane, size == 1 ? 1.3f : size == 2 ? 3f : 2.2f); weight(side, 1f);
            weight(gauges, size == 1 ? .6f : .85f); weight(mapPane, size == 1 ? 1.3f : 1f);
        } else {
            weight(cameraPane, size == 1 ? 1.2f : square ? 2.1f : 1.8f);
            weight(gauges, square ? .5f : .58f);
            weight(mapPane, size == 1 ? 1f : .55f);
        }
    }

    private void cycleMap() {
        prefs.edit().putInt("mapsize", (prefs.mapSize() + 1) % 3).apply();
        weights();
        toast(new String[]{"Small map", "Large map", "Map hidden: full camera"}[prefs.mapSize()]);
    }

    private static void weight(View v, float w) {
        if (!(v.getLayoutParams() instanceof LinearLayout.LayoutParams)) return;
        ((LinearLayout.LayoutParams) v.getLayoutParams()).weight = w;
        v.requestLayout();
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
        p.addView(Ui.text(this, "AI key for car make and model. A free Google Gemini key (aistudio.google.com) or a Claude key (sk-ant-\u2026). A photo of the car ahead is sent to that service.", 12, Ui.DIM, false));
        EditText ai = secret(prefs.aiKey(), "Paste AI key");
        p.addView(ai);
        p.addView(Ui.text(this, "Emergency contact phone number", 12, Ui.DIM, false));
        EditText contact = new EditText(this);
        contact.setInputType(InputType.TYPE_CLASS_PHONE);
        contact.setText(prefs.sosNumber());
        p.addView(contact);
        CheckBox mph = check(p, "Miles per hour", prefs.mph());
        CheckBox voice = check(p, "Spoken alerts and directions", prefs.voice());
        CheckBox auto = check(p, "Start recording when the app opens", prefs.autoRecord());
        CheckBox dark = check(p, "Dark map", prefs.mapDark());
        CheckBox radar = check(p, "Rain and snow radar on map (US)", prefs.radar());
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
                    .putBoolean("dark", dark.isChecked()).putBoolean("radar", radar.isChecked()).putInt("gb", g)
                    .putString("aikey", ai.getText().toString().trim()).putString("sos", contact.getText().toString().trim()).apply();
            carId.reset();
            data.refreshNow();
            map.loadStyle();
            if (last != null) data.update(last.getLatitude(), last.getLongitude(), heading, headingValid);
            refresh();
        }).setNegativeButton("Cancel", null).show();
    }

    private EditText secret(String value, String hint) {
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        e.setText(value); e.setHint(hint);
        return e;
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
        // a very hard hit shortly after driving: offer help (a dropped parked phone does not count)
        if (x * x + y * y + z * z > 40 * 40 && recentSpeed > 5 && now - recentSpeedAt < 8000) {
            recentSpeed = 0;
            sosDialog(true);
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
        carId.close();
        if (tts != null) tts.shutdown();
        if (tone != null) tone.release();
        map.view.onDestroy();
        super.onDestroy();
    }
}
