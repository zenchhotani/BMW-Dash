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
import com.zen.bmwdash.core.Detection;
import com.zen.bmwdash.core.LaneDetector;
import com.zen.bmwdash.core.LaneState;
import com.zen.bmwdash.core.Track;
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
    }

    private static final String TAG = "CameraEngine", FOLDER = "Movies/BMWDash";
    private static final long SEGMENT_MS = 120_000;
    private static final Set<String> WANTED = new HashSet<>(Arrays.asList(
            "car", "truck", "bus", "motorcycle", "bicycle", "person", "traffic light", "stop sign"));

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
    private boolean wantRecording, lockCurrent, closed;
    private long frameCount, fpsStart;
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
                .setResolutionStrategy(new ResolutionStrategy(new Size(1280, 720),
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

            // every other frame zooms into the road ahead so distant cars are found too
            List<Detection> dets = new ArrayList<>();
            if ((frameCount++ & 1) == 0) detect(frame, 0, 0, w, h, w, h, dets);
            else {
                int cw = w / 2, ch = h / 2;
                int cx = clamp(Math.round(center * w) - cw / 2, 0, w - cw);
                int cy = clamp(Math.round(horizon * h) - ch * 2 / 5, 0, h - ch);
                Bitmap crop = Bitmap.createBitmap(frame, cx, cy, cw, ch);
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
            boolean[] yellow = new boolean[px.length];
            for (int i = 0; i < px.length; i++) {
                int r = (px[i] >> 16) & 255, g = (px[i] >> 8) & 255, b = px[i] & 255;
                gray[i] = (r * 77 + g * 150 + b * 29) >> 8;
                yellow[i] = r > 90 && g > 70 && r - b > 40 && g - b > 25 && r >= g - 10 && r - g < 80;
            }
            LaneDetector.Result res = laneDetector.detect(gray, yellow, lw, lh, horizon, hood, center);
            laneState.update(res, t, center, hood);
            List<Track> tracks = tracker.update(dets, t, laneState);

            fpsFrames++;
            if (t - fpsStart >= 1000) { fps = (int) Math.round(fpsFrames * 1000.0 / (t - fpsStart)); fpsFrames = 0; fpsStart = t; }
            final int fw = w, fh = h, rate = fps;
            main.post(() -> { if (!closed) listener.onVision(tracks, laneState, fw, fh, rate); });
        } catch (Exception e) {
            Log.e(TAG, "analyze", e);
        } finally {
            image.close();
        }
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
        if (!wantRecording || recording != null || video == null || closed) return;
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
    void pause() { stopSegment(); }
    void resume() { if (wantRecording) main.postDelayed(this::nextSegment, 1200); }

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
