package com.zen.bmwdash;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Make and model of the car ahead, from a vision AI using the driver's own key:
 * Google Gemini, or Claude when the key starts with "sk-ant-". Only a crop of the car is sent.
 */
final class CarId {
    static final class Result {
        String make = "", model = "", color = "", body = "", confidence = "low", emergency = "none";
        int px, attempts;
        String label() {
            String name = (make + " " + model).trim();
            if (name.isEmpty() || confidence.equals("low")) name = (color + " " + (body.isEmpty() ? "vehicle" : body)).trim();
            else {
                if (confidence.equals("medium")) name = "Likely " + name;
                if (!color.isEmpty()) name += "  •  " + color;
            }
            return name.isEmpty() ? "Vehicle" : Character.toUpperCase(name.charAt(0)) + name.substring(1);
        }
    }
    interface Done { void done(int id, Result r); }

    private static final String PROMPT = "This is a crop of a vehicle on the road, usually seen from behind. Identify it. "
            + "Do not read or report licence plates and do not describe people. Reply with only this JSON: "
            + "{\"make\":\"\",\"model\":\"\",\"color\":\"\",\"body\":\"sedan|SUV|pickup|van|truck|bus|motorcycle|coupe|hatchback\","
            + "\"confidence\":\"high|medium|low\",\"emergency\":\"none|police|ambulance|fire\"}. "
            + "Use high only when a badge or an unmistakable shape shows the model. Leave make and model empty if unsure. "
            + "Set emergency only when markings or a light bar are clearly visible.";

    private final Prefs prefs;
    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<Integer, Result> results = new HashMap<>();
    private long nextAsk;
    private boolean busy;
    private int asked;
    /** shown to the driver when the service refuses us */
    volatile String problem = "";

    CarId(Prefs p) { prefs = p; }

    synchronized Result get(int id) { return results.get(id); }
    synchronized void reset() { nextAsk = 0; problem = ""; }

    /** Thread safe. A true answer reserves the slot, so follow it with identify(). */
    synchronized boolean wants(int id, int widthPx) {
        long now = SystemClock.elapsedRealtime();
        if (prefs.aiKey().isEmpty() || busy || now < nextAsk || asked >= 300 || widthPx < 90) return false;
        Result r = results.get(id);
        if (r != null && !(r.confidence.equals("low") && r.attempts < 2 && widthPx > r.px * 1.4f)) return false;
        busy = true;
        nextAsk = now + 7000;
        asked++;
        return true;
    }

    void identify(int id, Bitmap crop, int widthPx, Done done) {
        String key = prefs.aiKey();
        pool.execute(() -> {
            Result r = null;
            try {
                float s = Math.min(1f, 512f / Math.max(crop.getWidth(), crop.getHeight()));
                Bitmap small = s < 1 ? Bitmap.createScaledBitmap(crop, Math.round(crop.getWidth() * s), Math.round(crop.getHeight() * s), true) : crop;
                ByteArrayOutputStream jpg = new ByteArrayOutputStream();
                small.compress(Bitmap.CompressFormat.JPEG, 85, jpg);
                String data = Base64.encodeToString(jpg.toByteArray(), Base64.NO_WRAP);
                r = parse(key.startsWith("sk-ant-") ? claude(key, data) : gemini(key, data));
                problem = "";
            } catch (Exception e) {
                String m = String.valueOf(e.getMessage());
                Log.w("CarId", m);
                boolean refused = m.contains("HTTP 400") || m.contains("HTTP 401") || m.contains("HTTP 403");
                problem = refused ? "AI key was rejected" : m.contains("HTTP 429") ? "AI limit reached, pausing" : "Car lookup failed";
                synchronized (this) { nextAsk = SystemClock.elapsedRealtime() + (refused ? 300_000 : 60_000); }
            }
            Result result = r;
            synchronized (this) {
                busy = false;
                if (result != null) {
                    Result old = results.get(id);
                    result.px = widthPx;
                    result.attempts = old == null ? 1 : old.attempts + 1;
                    results.put(id, result);
                }
            }
            if (result != null) main.post(() -> done.done(id, result));
        });
    }

    private static Result parse(String text) throws Exception {
        int a = text.indexOf('{'), b = text.lastIndexOf('}');
        if (a < 0 || b <= a) throw new IOException("no answer");
        JSONObject o = new JSONObject(text.substring(a, b + 1));
        Result r = new Result();
        r.make = clean(o.optString("make")); r.model = clean(o.optString("model")); r.color = clean(o.optString("color"));
        r.body = clean(o.optString("body")); r.confidence = o.optString("confidence", "low"); r.emergency = o.optString("emergency", "none");
        return r;
    }

    private static String clean(String s) {
        s = s.trim();
        if (s.equalsIgnoreCase("unknown") || s.equalsIgnoreCase("null") || s.contains("|")) return "";
        return s.length() > 28 ? s.substring(0, 28) : s;
    }

    private static String claude(String key, String data) throws Exception {
        JSONObject image = new JSONObject().put("type", "image").put("source",
                new JSONObject().put("type", "base64").put("media_type", "image/jpeg").put("data", data));
        JSONObject body = new JSONObject().put("model", "claude-haiku-5-5").put("max_tokens", 1024)
                .put("messages", new JSONArray().put(new JSONObject().put("role", "user")
                        .put("content", new JSONArray().put(image).put(new JSONObject().put("type", "text").put("text", PROMPT)))));
        JSONArray c = new JSONObject(post("https://api.anthropic.com/v1/messages", body.toString(),
                "x-api-key", key, "anthropic-version", "2023-06-01")).getJSONArray("content");
        for (int i = 0; i < c.length(); i++)
            if ("text".equals(c.getJSONObject(i).optString("type"))) return c.getJSONObject(i).getString("text");
        throw new IOException("no answer");
    }

    private static String gemini(String key, String data) throws Exception {
        JSONObject image = new JSONObject().put("inline_data", new JSONObject().put("mime_type", "image/jpeg").put("data", data));
        JSONObject body = new JSONObject().put("contents", new JSONArray().put(new JSONObject()
                .put("parts", new JSONArray().put(image).put(new JSONObject().put("text", PROMPT)))));
        IOException last = null;
        for (String model : new String[]{"gemini-3.5-flash-lite", "gemini-3.8-flash"}) {
            try {
                JSONArray parts = new JSONObject(post("https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent",
                        body.toString(), "x-goog-api-key", key)).getJSONArray("candidates").getJSONObject(0)
                        .getJSONObject("content").getJSONArray("parts");
                StringBuilder s = new StringBuilder();
                for (int i = 0; i < parts.length(); i++) s.append(parts.getJSONObject(i).optString("text"));
                return s.toString();
            } catch (IOException e) {
                last = e;
                if (!String.valueOf(e.getMessage()).contains("HTTP 404")) throw e;
            }
        }
        throw last;
    }

    private static String post(String url, String json, String... headers) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(8000); c.setReadTimeout(20000); c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            for (int i = 0; i + 1 < headers.length; i += 2) c.setRequestProperty(headers[i], headers[i + 1]);
            try (OutputStream o = c.getOutputStream()) { o.write(json.getBytes("UTF-8")); }
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
                return b.toString("UTF-8");
            }
        } finally { c.disconnect(); }
    }

    void close() { pool.shutdownNow(); }
}
