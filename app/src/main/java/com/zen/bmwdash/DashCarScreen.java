package com.zen.bmwdash;

import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.car.app.AppManager;
import androidx.car.app.CarContext;
import androidx.car.app.Screen;
import androidx.car.app.SurfaceCallback;
import androidx.car.app.SurfaceContainer;
import androidx.car.app.model.Action;
import androidx.car.app.model.ActionStrip;
import androidx.car.app.model.Template;
import androidx.car.app.navigation.model.NavigationTemplate;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

/** The car screen: a full-screen drawing surface with one button to change the view. */
final class DashCarScreen extends Screen implements SurfaceCallback, DefaultLifecycleObserver {
    private final CarRenderer renderer;
    private final Object lock = new Object();
    private HandlerThread thread;
    private Handler handler;
    private Surface surface;
    private int width, height;
    private Rect stable = new Rect();
    private boolean running;

    DashCarScreen(CarContext carContext) {
        super(carContext);
        renderer = new CarRenderer(carContext.getApplicationContext());
        getLifecycle().addObserver(this);
    }

    @Override public void onCreate(@NonNull LifecycleOwner owner) {
        getCarContext().getCarService(AppManager.class).setSurfaceCallback(this);
    }

    @Override public void onDestroy(@NonNull LifecycleOwner owner) {
        stop();
        getCarContext().getCarService(AppManager.class).setSurfaceCallback(null);
    }

    @NonNull @Override public Template onGetTemplate() {
        Action view = new Action.Builder()
                .setTitle(CarBridge.modeName())
                .setOnClickListener(() -> { CarBridge.nextMode(); invalidate(); })
                .build();
        return new NavigationTemplate.Builder()
                .setActionStrip(new ActionStrip.Builder().addAction(view).build())
                .build();
    }

    @Override public void onSurfaceAvailable(@NonNull SurfaceContainer container) {
        synchronized (lock) {
            surface = container.getSurface();
            width = container.getWidth(); height = container.getHeight();
        }
        start();
    }

    @Override public void onSurfaceDestroyed(@NonNull SurfaceContainer container) {
        stop();
        synchronized (lock) { surface = null; }
    }

    @Override public void onStableAreaChanged(@NonNull Rect area) { synchronized (lock) { stable = new Rect(area); } }
    @Override public void onVisibleAreaChanged(@NonNull Rect area) { synchronized (lock) { if (stable.isEmpty()) stable = new Rect(area); } }

    /** a tap on the picture changes the view as well */
    @Override public void onClick(float x, float y) { CarBridge.nextMode(); invalidate(); }

    private void start() {
        if (running) return;
        running = true;
        CarBridge.shown = true;
        thread = new HandlerThread("car-draw");
        thread.start();
        handler = new Handler(thread.getLooper());
        handler.post(new Runnable() {
            @Override public void run() {
                if (!running) return;
                long began = SystemClock.elapsedRealtime();
                drawOnce();
                if (running) handler.postDelayed(this, Math.max(10, renderer.intervalMs() - (SystemClock.elapsedRealtime() - began)));
            }
        });
    }

    private void stop() {
        running = false;
        CarBridge.shown = false;
        if (handler != null) handler.removeCallbacksAndMessages(null);
        if (thread != null) { thread.quitSafely(); thread = null; }
        handler = null;
    }

    private void drawOnce() {
        synchronized (lock) {
            if (surface == null || !surface.isValid() || width == 0) return;
            Canvas c = null;
            try {
                c = surface.lockCanvas(null);
                if (c != null) renderer.draw(c, width, height, stable);
            } catch (RuntimeException e) {
                // the surface went away between the check and the draw
            } finally {
                if (c != null) {
                    try { surface.unlockCanvasAndPost(c); } catch (RuntimeException ignored) { }
                }
            }
        }
    }
}
