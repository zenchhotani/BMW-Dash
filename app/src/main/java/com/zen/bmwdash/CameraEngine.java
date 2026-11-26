package com.zen.bmwdash;

import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.hardware.camera2.CameraCharacteristics;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Log;
import android.util.Size;
import android.util.SizeF;

import androidx.camera.camera2.interop.Camera2CameraInfo;
import androidx.camera.camera2.interop.ExperimentalCamera2Interop;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.core.UseCase;
import androidx.camera.core.resolutionselector.AspectRatioStrategy;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.FallbackStrategy;
import androidx.camera.video.MediaStoreOutputOptions;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;

import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.components.containers.Category;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector;
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetectorResult;
import com.zen.bmwdash.core.RoadUserFilter;
import com.zen.bmwdash.core.Detection;
import com.zen.bmwdash.core.LaneDetector;
import com.zen.bmwdash.core.LaneState;
import com.zen.bmwdash.core.SignReader;
import com.zen.bmwdash.core.SignalColor;
import com.zen.bmwdash.core.SignalPicker;
import com.zen.bmwdash.core.SignalState;
import com.zen.bmwdash.core.Track;
import com.zen.bmwdash.core.ViewQuality;
import com.zen.bmwdash.core.Tracker;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Preview + on-device vision + loop recording. */
@ExperimentalCamera2Interop
final class CameraEngine {
    interface Listener {
        void onVision(List<Track> tracks, LaneState lane, int w, int h, int fps);
        void onRecording(boolean recording, long seconds, boolean locked);
        void onError(String message);
        void onScene(int light, float brightness, boolean blocked);
        void onSigns(List<SignReader.Sign> signs);
        /** ask first: is a picture of this lead car wanted? */
        boolean wantsCar(int id, int widthPx);
        void onCarPicture(int id, Bitmap crop, int widthPx);
    }

    private static final String TAG = "CameraEngine", FOLDER = "Movies/BMWDash";
    private static final long SEGMENT_MS = 120_000;
    private static final Set<String> WANTED = new HashSet<>(Arrays.asList(
            "car", "truck", "bus", "motorcycle", "bicycle", "person", "traffic light", "stop sign",
            "dog", "cat", "horse", "cow", "sheep", "bear", "suitcase", "chair", "backpack", "bench"));

    private final Context ctx;
    private final Prefs prefs;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Tracker tracker = new Tracker();
    private final LaneDetector laneDetector = new LaneDetector();
    private final LaneState laneState = new LaneState();

    private ObjectDetector detector;
    private Preview preview;
    private ImageAnalysis analysis;
    private VideoCapture<Recorder> video;
    private Recording recording;
    private volatile float focalOverLong = 0.65f;
    private volatile boolean enhance;
    private volatile int maxBoost;
    private Camera boundCamera;
    private float brightness = 128;
    private static final int[] LUT = new int[256];
    static { for (int i = 0; i < 256; i++) LUT[i] = (int) Math.round(255 * Math.pow(i / 255.0, 0.55)); }
    private boolean wantRecording, lockCurrent, closed, paused;
    private long frameCount, fpsStart;
    private List<Track> recent = new ArrayList<>();
    private final ViewQuality viewQuality = new ViewQuality();
    private final SignalState signalState = new SignalState();
    private final SignalPicker signalPicker = new SignalPicker();
    private final SignReader signReader = new SignReader();
    private com.google.mlkit.vision.text.TextRecognizer reader;
    private volatile boolean reading;
    private long readAt;
    private int reads;
    private final android.util.SparseIntArray lampColors = new android.util.SparseIntArray();
    private int fps, fpsFrames;

    CameraEngine(Context c, Prefs p, Listener l) { ctx = c; prefs = p; listener = l; }

    void start(LifecycleOwner owner, PreviewView view, int rotation) {
        worker.execute(this::loadDetector);
        com.google.common.util.concurrent.ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(ctx);
        future.addListener(() -> {
            try { bind(future.get(), owner, view, rotation); }
            catch (Exception e) { Log.e(TAG, "camera", e); listener.onError("Camera failed: " + e.getMessage()); }
        }, ContextCompat.getMainExecutor(ctx));
    }

    private void loadDetector() {
        if (detector != null) return;
        try {
            ObjectDetector.ObjectDetectorOptions o = ObjectDetector.ObjectDetectorOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath("detector.tflite").build())
                    .setRunningMode(RunningMode.IMAGE).setMaxResults(25).setScoreThreshold(0.33f).build();
            detector = ObjectDetector.createFromOptions(ctx, o);
        } catch (Exception e) {
            Log.e(TAG, "detector", e);
            main.post(() -> listener.onError("Car detection could not start: " + e.getMessage()));
        }
    }

    private void bind(ProcessCameraProvider provider, LifecycleOwner owner, PreviewView view, int rotation) {
        ResolutionSelector wide = new ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY).build();
        ResolutionSelector small = new ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(new ResolutionStrategy(new Size(1920, 1080),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build();
        preview = new Preview.Builder().setResolutionSelector(wide).setTargetRotation(rotation).build();
        preview.setSurfaceProvider(view.getSurfaceProvider());
        analysis = new ImageAnalysis.Builder().setResolutionSelector(small).setTargetRotation(rotation)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888).build();
        analysis.setAnalyzer(worker, this::analyze);
        Recorder recorder = new Recorder.Builder().setQualitySelector(
                QualitySelector.from(Quality.FHD, FallbackStrategy.lowerQualityOrHigherThan(Quality.FHD))).build();
        video = VideoCapture.withOutput(recorder);
        video.setTargetRotation(rotation);

        provider.unbindAll();
        Camera camera;
        try {
            camera = provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, new UseCase[]{preview, analysis, video});
        } catch (Exception e) {
            provider.unbindAll();
            video = null;
            camera = provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis);
            listener.onError("This camera cannot record and analyse together. Recording is off.");
        }
        boundCamera = camera;
        applyExposure();
        try {
            Camera2CameraInfo info = Camera2CameraInfo.from(camera.getCameraInfo());
            float[] f = info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
            SizeF s = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
            if (f != null && f.length > 0 && s != null && s.getWidth() > 0)
                focalOverLong = f[0] / Math.max(s.getWidth(), s.getHeight());
        } catch (Exception e) { Log.w(TAG, "focal length unavailable", e); }
        if (prefs.autoRecord()) { wantRecording = true; main.postDelayed(this::nextSegment, 1200); }
    }

    void setRotation(int rotation) {
        if (preview != null) preview.setTargetRotation(rotation);
        if (analysis != null) analysis.setTargetRotation(rotation);
        if (video != null) video.setTargetRotation(rotation);
    }

    /** focal length over upright frame width, as used for distances */
    float focal() { return tracker.focal; }

    /** Night mode: lift exposure a little and brighten what the AI and lane finder see. */
    void setNight(boolean on) {
        if (enhance == on) return;
        enhance = on;
        applyExposure();
    }
    private void applyExposure() {
        if (boundCamera == null) return;
        androidx.camera.core.ExposureState e = boundCamera.getCameraInfo().getExposureState();
        if (!e.isExposureCompensationSupported()) return;
        float step = e.getExposureCompensationStep().floatValue();
        int index = enhance && step > 0 ? Math.min(e.getExposureCompensationRange().getUpper(), Math.round(0.7f / step)) : 0;
        boundCamera.getCameraControl().setExposureCompensationIndex(index);
    }

    private static Bitmap brighten(Bitmap src) {
        Bitmap out = Bitmap.createBitmap(src.getWidth(), src.getHeight(), Bitmap.Config.ARGB_8888);
        android.graphics.Paint paint = new android.graphics.Paint();
        paint.setColorFilter(new android.graphics.ColorMatrixColorFilter(new float[]{
                1.6f, 0, 0, 0, 14, 0, 1.6f, 0, 0, 14, 0, 0, 1.6f, 0, 14, 0, 0, 0, 1, 0}));
        new android.graphics.Canvas(out).drawBitmap(src, 0, 0, paint);
        return out;
    }

    private void analyze(ImageProxy image) {
        Bitmap frame = null;
        try {
            if (detector == null) return;
            Bitmap raw = image.toBitmap();
            int deg = image.getImageInfo().getRotationDegrees();
            if (deg != 0) {
                Matrix m = new Matrix(); m.postRotate(deg);
                frame = Bitmap.createBitmap(raw, 0, 0, raw.getWidth(), raw.getHeight(), m, true);
            } else frame = raw;
            int w = frame.getWidth(), h = frame.getHeight();
            long t = SystemClock.elapsedRealtime();
            float horizon = prefs.horizon(), center = prefs.center(), hood = prefs.hood();

            Bitmap seen = enhance ? brighten(frame) : frame;
            // take turns: whole frame and road zoom most often, then overhead signals and roadside signs
            List<Detection> dets = new ArrayList<>();
            int pass = new int[]{0, 1, 2, 0, 1, 3}[(int) (frameCount++ % 6)];
            if (pass == 0) detect(seen, 0, 0, w, h, w, h, dets);
            else {
                int cw = w / 2, ch = h / 2;
                int cx = pass == 3 ? w - cw : clamp(Math.round(center * w) - cw / 2, 0, w - cw);
                int top = Math.round(horizon * h) - (pass == 2 ? ch * 9 / 10 : ch * 3 / 5);
                if (pass == 1) top = Math.round(horizon * h) - ch * 2 / 5;
                int cy = clamp(top, 0, h - ch);
                Bitmap crop = Bitmap.createBitmap(seen, cx, cy, cw, ch);
                detect(crop, cx, cy, cw, ch, w, h, dets);
                crop.recycle();
            }
            tracker.horizon = horizon; tracker.center = center; tracker.cameraHeight = prefs.cameraHeight();
            tracker.aspect = h / (float) w;
            tracker.focal = focalOverLong * Math.max(w, h) / w;

            int lw = w >= h ? 480 : 270, lh = Math.max(1, lw * h / w);
            Bitmap tiny = Bitmap.createScaledBitmap(frame, lw, lh, true);
            int[] px = new int[lw * lh];
            tiny.getPixels(px, 0, lw, 0, 0, lw, lh);
            if (tiny != frame) tiny.recycle();
            int[] gray = new int[px.length];
            long sum = 0;
            boolean[] yellow = new boolean[px.length];
            for (int i = 0; i < px.length; i++) {
                int r = (px[i] >> 16) & 255, g = (px[i] >> 8) & 255, b = px[i] & 255;
                int lum = (r * 77 + g * 150 + b * 29) >> 8;
                sum += lum;
                gray[i] = enhance ? LUT[lum] : lum;
                // by proportion, so yellow paint still counts in shade
                yellow[i] = r > 65 && g > 50 && (r - b) * 4 > r && g - b > 15 && r >= g - 10 && (r - g) * 5 < r * 2;
            }
            // people and animals must look right and not be a patch of glare
            boolean dim = enhance || brightness < 90;
            for (java.util.Iterator<Detection> it = dets.iterator(); it.hasNext(); ) {
                Detection d = it.next();
                if (RoadUserFilter.strict(d.label) && (!RoadUserFilter.plausible(d, horizon, h / (float) w, dim)
                        || RoadUserFilter.glare(px, lw, lh, d))) it.remove();
            }
            tracker.poor = dim;

            List<float[]> blocked = new ArrayList<>();
            for (Track k : recent)
                if (k.isVehicle() || k.label.equals("person")) blocked.add(new float[]{k.left, k.top, k.right, k.bottom - 0.01f});
            laneDetector.ignore = blocked;
            LaneDetector.Result res = laneDetector.detect(gray, yellow, lw, lh, horizon, hood, center);
            laneState.horizon = horizon;
            laneState.update(res, t, center, hood);
            List<Track> tracks = tracker.update(dets, t, laneState);
            recent = tracks;

            brightness += 0.05f * (sum / (float) px.length - brightness);

            final boolean unclear = viewQuality.update(gray, lw, lh, sum / (float) px.length, hood, t);

            Track ahead = null;
            for (Track k : tracks) if (k.lead) ahead = k;
            // only the one light judged to be ours is read
            Track ours = signalPicker.pick(tracks, center, horizon, w);
            int read = SignalColor.NONE;
            if (ours != null) {
                if (ours.lastSeen == t) {
                    int x = clamp((int) (ours.left * w), 0, w - 2), y = clamp((int) (ours.top * h), 0, h - 2);
                    int bw = clamp((int) ((ours.right - ours.left) * w), 1, w - x), bh = clamp((int) ((ours.bottom - ours.top) * h), 1, h - y);
                    int[] lp = new int[bw * bh];
                    // the untouched frame: night brightening would wash the lamp colour out
                    frame.getPixels(lp, 0, bw, x, y, bw, bh);
                    read = SignalColor.classify(lp, bw, bh);
                    lampColors.put(ours.id, read);
                }
                ours.signal = lampColors.get(ours.id);
            }
            if (lampColors.size() > 200) lampColors.clear();
            int light = signalState.update(read, t);
            if (!reading && t - readAt > 600) readSigns(frame, horizon, t);
            if (ahead != null && ahead.hits >= 5) {
                float bw = ahead.right - ahead.left, bh = ahead.bottom - ahead.top;
                int x = clamp((int) ((ahead.left - bw * 0.08f) * w), 0, w - 2), y = clamp((int) ((ahead.top - bh * 0.08f) * h), 0, h - 2);
                int pw = clamp((int) (bw * 1.16f * w), 1, w - x), ph = clamp((int) (bh * 1.16f * h), 1, h - y);
                int carPx = (int) (bw * w);
                if (listener.wantsCar(ahead.id, carPx)) {
                    Bitmap cut = Bitmap.createBitmap(seen, x, y, pw, ph).copy(Bitmap.Config.ARGB_8888, false);
                    final int id = ahead.id;
                    main.post(() -> { if (!closed) listener.onCarPicture(id, cut, carPx); });
                }
            }

            fpsFrames++;
            if (t - fpsStart >= 1000) { fps = (int) Math.round(fpsFrames * 1000.0 / (t - fpsStart)); fpsFrames = 0; fpsStart = t; }
            final int fw = w, fh = h, rate = fps, signalNow = light;
            // the lane state is written on this thread; give the screen its own copy
            final LaneState laneNow = laneState.snapshot();
            final float bright = brightness;
            main.post(() -> { if (!closed) { listener.onScene(signalNow, bright, unclear); listener.onVision(tracks, laneNow, fw, fh, rate); } });
        } catch (Exception e) {
            Log.e(TAG, "analyze", e);
        } finally {
            image.close();
        }
    }

    /** Reads sign text from one side of the upper road view; the sides take turns. */
    private void readSigns(Bitmap frame, float horizon, long t) {
        readAt = t; reading = true;
        int w = frame.getWidth(), h = frame.getHeight();
        boolean right = reads++ % 3 != 2;
        int cw = w / 2, x = right ? w - cw : w / 5;
        int y = clamp((int) ((horizon - .33f) * h), 0, h - 2), ch = clamp((int) (.45f * h), 1, h - y);
        Bitmap cut = Bitmap.createBitmap(frame, x, y, cw, ch);
        // enlarging helps the recogniser with small, distant lettering
        Bitmap big = Bitmap.createScaledBitmap(cut, cw * 2, ch * 2, true);
        if (cut != big && cut != frame) cut.recycle();
        try {
            if (reader == null) reader = com.google.mlkit.vision.text.TextRecognition.getClient(
                    com.google.mlkit.vision.text.latin.TextRecognizerOptions.DEFAULT_OPTIONS);
            reader.process(com.google.mlkit.vision.common.InputImage.fromBitmap(big, 0)).addOnCompleteListener(task -> {
                reading = false;
                big.recycle();
                if (closed || !task.isSuccessful()) return;
                List<SignReader.Line> lines = new ArrayList<>();
                for (com.google.mlkit.vision.text.Text.TextBlock b : task.getResult().getTextBlocks())
                    for (com.google.mlkit.vision.text.Text.Line l : b.getLines()) {
                        android.graphics.Rect r = l.getBoundingBox();
                        if (r != null) lines.add(new SignReader.Line(l.getText(), r.left, r.top, r.right, r.bottom));
                    }
                List<SignReader.Sign> signs = signReader.read(lines, SystemClock.elapsedRealtime());
                if (!signs.isEmpty()) listener.onSigns(signs);
            });
        } catch (RuntimeException e) { reading = false; Log.w(TAG, "signs", e); }
    }

    private void detect(Bitmap b, int ox, int oy, int cw, int ch, int w, int h, List<Detection> out) {
        MPImage img = new BitmapImageBuilder(b).build();
        ObjectDetectorResult result = detector.detect(img);
        for (com.google.mediapipe.tasks.components.containers.Detection d : result.detections()) {
            if (d.categories().isEmpty()) continue;
            Category c = d.categories().get(0);
            if (!WANTED.contains(c.categoryName())) continue;
            android.graphics.RectF r = d.boundingBox();
            float l = (ox + r.left) / w, t = (oy + r.top) / h, rr = (ox + r.right) / w, bb = (oy + r.bottom) / h;
            // ignore our own bonnet and boxes cut off by the zoom window
            if (t > prefs.hood() - 0.03f || (rr - l > 0.85f && bb > prefs.hood())) continue;
            if (cw < w && (r.left <= 2 || r.right >= cw - 2)) continue;
            out.add(new Detection(c.categoryName(), c.score(), l, t, rr, bb));
        }
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    // ---- loop recording ----

    void toggleRecording() {
        wantRecording = recording == null;
        if (wantRecording) nextSegment(); else stopSegment();
    }
    /** Protects the current clip from being overwritten. */
    boolean lockClip() { if (recording == null) return false; lockCurrent = true; return true; }

    private void nextSegment() {
        if (!wantRecording || recording != null || video == null || closed || paused) return;
        worker.execute(this::prune);
        String name = "DASH_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        ContentValues v = new ContentValues();
        v.put(MediaStore.Video.Media.DISPLAY_NAME, name);
        v.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        v.put(MediaStore.Video.Media.RELATIVE_PATH, FOLDER);
        MediaStoreOutputOptions opts = new MediaStoreOutputOptions.Builder(ctx.getContentResolver(),
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI).setContentValues(v).build();
        try {
            final Recording[] mine = new Recording[1];
            mine[0] = video.getOutput().prepareRecording(ctx, opts).start(ContextCompat.getMainExecutor(ctx), e -> {
                if (e instanceof VideoRecordEvent.Status) {
                    long ms = e.getRecordingStats().getRecordedDurationNanos() / 1_000_000;
                    listener.onRecording(true, ms / 1000, lockCurrent);
                    if (ms >= SEGMENT_MS && recording == mine[0]) stopSegment();
                } else if (e instanceof VideoRecordEvent.Finalize) {
                    VideoRecordEvent.Finalize f = (VideoRecordEvent.Finalize) e;
                    Uri uri = f.getOutputResults().getOutputUri();
                    if (lockCurrent) rename(uri, "LOCK_" + name);
                    lockCurrent = false;
                    if (recording == mine[0]) recording = null;
                    listener.onRecording(false, 0, false);
                    int err = f.getError();
                    if (err == VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE) {
                        wantRecording = false; listener.onError("Storage is full. Recording stopped.");
                    } else if (wantRecording && !closed) main.postDelayed(this::nextSegment, err == 0 ? 150 : 3000);
                }
            });
            recording = mine[0];
        } catch (Exception ex) {
            Log.e(TAG, "record", ex);
            listener.onError("Recording failed: " + ex.getMessage());
        }
    }

    private void stopSegment() { if (recording != null) recording.stop(); }
    void pause() { paused = true; stopSegment(); }
    void resume() { paused = false; if (wantRecording) main.postDelayed(this::nextSegment, 1200); }

    private void rename(Uri uri, String name) {
        try {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Video.Media.DISPLAY_NAME, name + ".mp4");
            ctx.getContentResolver().update(uri, v, null, null);
        } catch (Exception e) { Log.w(TAG, "lock", e); }
    }

    /** Deletes the oldest unlocked clips once the loop budget is exceeded. */
    private void prune() {
        String[] cols = {MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.SIZE};
        try (Cursor c = ctx.getContentResolver().query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cols,
                MediaStore.Video.Media.RELATIVE_PATH + " LIKE ?", new String[]{FOLDER + "%"},
                MediaStore.Video.Media.DATE_ADDED + " ASC")) {
            if (c == null) return;
            long total = 0;
            List<long[]> old = new ArrayList<>();
            while (c.moveToNext()) {
                String n = c.getString(1);
                if (n == null || !n.startsWith("DASH_")) continue;
                total += c.getLong(2);
                old.add(new long[]{c.getLong(0), c.getLong(2)});
            }
            long limit = prefs.storageGb() * 1_000_000_000L;
            for (long[] o : old) {
                if (total <= limit) break;
                ctx.getContentResolver().delete(ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, o[0]), null, null);
                total -= o[1];
            }
        } catch (Exception e) { Log.w(TAG, "prune", e); }
    }

    void close() {
        closed = true; wantRecording = false; stopSegment();
        if (analysis != null) analysis.clearAnalyzer();
        worker.execute(() -> { if (detector != null) detector.close(); detector = null; });
        worker.shutdown();
    }
}
