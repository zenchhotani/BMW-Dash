package com.zen.bmwdash;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import com.zen.bmwdash.core.Geo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * All online feeds:
 * OpenStreetMap Overpass (road name, speed limit, lanes), TomTom (live speed, incidents, traffic routes),
 * OSRM (routes without a key), Nominatim (search), Open-Meteo (weather).
 */
final class RoadData {
    static final class Road { String name = ""; double limit = Double.NaN; int lanes; boolean oneway; }
    static final class Weather { String summary = "", hazard; double temp = Double.NaN; }
    static final class Incident { double lat, lon; String text; int severity; }
    static final class Place { String name; double lat, lon; }
    static final class Step { double along; String text; int direction; }
    static final class Route {
        double[][] points; double meters, seconds, delay; boolean liveTraffic; String via = "";
        List<Step> steps = new ArrayList<>();
    }
    interface Done<T> { void done(T value); }

    private static final String TAG = "RoadData", UA = "BMWDash/1.0 (personal Android dash app; github.com/zenchhotani/BMW-Dash)";
    private final ExecutorService roadPool = Executors.newSingleThreadExecutor(), livePool = Executors.newSingleThreadExecutor(),
            userPool = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Prefs prefs;

    private JSONArray ways;
    private double waysLat, waysLon;
    private long waysTime = -100000, roadTry = -100000, flowTime = -100000, incidentTime = -1000000, weatherTime = -10000000;
    private double flowLat, flowLon;

    Road road = new Road();
    double flow = Double.NaN, freeFlow = Double.NaN; // m/s
    Weather weather;
    List<Incident> incidents = new ArrayList<>();
    String trafficStatus = "";
    Runnable onChange = () -> {};

    RoadData(Prefs p) { prefs = p; }

    void refreshNow() { flowTime = incidentTime = -1000000; trafficStatus = ""; }

    /** Call on the main thread with each location fix; throttles itself. */
    void update(double lat, double lon, double heading, boolean headingValid) {
        long now = SystemClock.elapsedRealtime();
        boolean stale = ways == null || Geo.distance(lat, lon, waysLat, waysLon) > 220 || now - waysTime > 600_000;
        if (stale && now - roadTry > 12_000) {
            roadTry = now;
            roadPool.execute(() -> fetchWays(lat, lon, heading, headingValid));
        } else match(lat, lon, heading, headingValid);

        String key = prefs.tomtomKey();
        if (!key.isEmpty()) {
            double moved = Geo.distance(lat, lon, flowLat, flowLon);
            if (now - flowTime > 45_000 || (moved > 400 && now - flowTime > 15_000)) {
                flowTime = now; flowLat = lat; flowLon = lon;
                livePool.execute(() -> fetchFlow(lat, lon, key));
            }
            if (now - incidentTime > 120_000) { incidentTime = now; livePool.execute(() -> fetchIncidents(lat, lon, key)); }
        } else { flow = freeFlow = Double.NaN; incidents = new ArrayList<>(); }
        if (now - weatherTime > 600_000) { weatherTime = now; livePool.execute(() -> fetchWeather(lat, lon)); }
    }

    private void fetchWays(double lat, double lon, double heading, boolean valid) {
        String q = String.format(Locale.US, "[out:json][timeout:10];way(around:350,%.6f,%.6f)[highway~\"^(motorway|trunk|primary|secondary|tertiary|unclassified|residential|living_street|motorway_link|trunk_link|primary_link|secondary_link|tertiary_link)$\"];out tags geom;", lat, lon);
        for (String host : new String[]{"https://overpass-api.de/api/interpreter", "https://overpass.private.coffee/api/interpreter"}) {
            try {
                JSONArray a = new JSONObject(http(host, "data=" + URLEncoder.encode(q, "UTF-8"))).getJSONArray("elements");
                main.post(() -> {
                    ways = a; waysLat = lat; waysLon = lon; waysTime = SystemClock.elapsedRealtime();
                    match(lat, lon, heading, valid); onChange.run();
                });
                return;
            } catch (Exception e) { Log.w(TAG, "overpass " + e.getMessage()); }
        }
    }

    private void match(double lat, double lon, double heading, boolean headingValid) {
        if (ways == null) return;
        JSONObject best = null; double bestScore = 55;
        for (int i = 0; i < ways.length(); i++) {
            JSONObject w = ways.optJSONObject(i);
            JSONArray g = w == null ? null : w.optJSONArray("geometry");
            if (g == null) continue;
            for (int j = 0; j + 1 < g.length(); j++) {
                JSONObject a = g.optJSONObject(j), b = g.optJSONObject(j + 1);
                if (a == null || b == null) continue;
                double la = a.optDouble("lat"), lo = a.optDouble("lon"), lb = b.optDouble("lat"), ob = b.optDouble("lon");
                double score = Geo.toSegment(lat, lon, la, lo, lb, ob)[0];
                if (headingValid) score += Geo.axisDifference(heading, Geo.bearing(la, lo, lb, ob)) * 0.6;
                if (score < bestScore) { bestScore = score; best = w; }
            }
        }
        Road r = new Road();
        JSONObject t = best == null ? null : best.optJSONObject("tags");
        if (t != null) {
            String name = t.optString("name"), ref = t.optString("ref");
            r.name = name.isEmpty() ? ref : ref.isEmpty() ? name : name + "  •  " + ref;
            double kmh = Geo.parseMaxSpeedKmh(t.has("maxspeed") ? t.optString("maxspeed") : null);
            r.limit = kmh / 3.6;
            r.oneway = "yes".equals(t.optString("oneway")) || t.optString("highway").startsWith("motorway");
            try { r.lanes = Integer.parseInt(t.optString("lanes", "0")); } catch (NumberFormatException ignored) { }
        }
        road = r;
    }

    private void fetchFlow(double lat, double lon, String key) {
        try {
            String u = String.format(Locale.US, "https://api.tomtom.com/traffic/services/4/flowSegmentData/absolute/12/json?point=%.6f,%.6f&unit=KMPH&key=%s", lat, lon, enc(key));
            JSONObject d = new JSONObject(http(u, null)).getJSONObject("flowSegmentData");
            double c = d.getDouble("currentSpeed") / 3.6, f = d.getDouble("freeFlowSpeed") / 3.6;
            boolean closed = d.optBoolean("roadClosure");
            main.post(() -> { flow = c; freeFlow = f; trafficStatus = closed ? "Road closure reported here" : ""; onChange.run(); });
        } catch (Exception e) {
            Log.w(TAG, "flow " + e.getMessage());
            String msg = e.getMessage() != null && e.getMessage().contains("HTTP 403") ? "TomTom key was rejected" : "";
            main.post(() -> { flow = freeFlow = Double.NaN; trafficStatus = msg; onChange.run(); });
        }
    }

    private void fetchIncidents(double lat, double lon, String key) {
        try {
            double dLat = 0.045, dLon = 0.045 / Math.max(0.2, Math.cos(Math.toRadians(lat)));
            String fields = "{incidents{geometry{type,coordinates},properties{iconCategory,magnitudeOfDelay,events{description},from,to}}}";
            String u = String.format(Locale.US, "https://api.tomtom.com/traffic/services/5/incidentDetails?bbox=%.5f,%.5f,%.5f,%.5f&fields=%s&language=en-US&timeValidityFilter=present&key=%s",
                    lon - dLon, lat - dLat, lon + dLon, lat + dLat, enc(fields), enc(key));
            JSONArray a = new JSONObject(http(u, null)).getJSONArray("incidents");
            List<Incident> list = new ArrayList<>();
            for (int i = 0; i < a.length() && list.size() < 60; i++) {
                JSONObject o = a.getJSONObject(i), g = o.getJSONObject("geometry"), p = o.getJSONObject("properties");
                JSONArray c = g.getJSONArray("coordinates");
                if ("LineString".equals(g.optString("type"))) c = c.getJSONArray(0);
                Incident in = new Incident();
                in.lon = c.getDouble(0); in.lat = c.getDouble(1);
                JSONArray ev = p.optJSONArray("events");
                in.text = ev != null && ev.length() > 0 ? ev.getJSONObject(0).optString("description", "Incident") : "Incident";
                String from = p.optString("from");
                if (!from.isEmpty()) in.text += " near " + from;
                in.severity = p.optInt("magnitudeOfDelay");
                list.add(in);
            }
            main.post(() -> { incidents = list; onChange.run(); });
        } catch (Exception e) { Log.w(TAG, "incidents " + e.getMessage()); }
    }

    private void fetchWeather(double lat, double lon) {
        try {
            boolean mph = prefs.mph();
            String u = String.format(Locale.US, "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&current=temperature_2m,precipitation,weather_code,visibility,wind_gusts_10m&wind_speed_unit=mph%s",
                    lat, lon, mph ? "&temperature_unit=fahrenheit" : "");
            JSONObject c = new JSONObject(http(u, null)).getJSONObject("current");
            Weather w = new Weather();
            w.temp = c.optDouble("temperature_2m");
            int code = c.optInt("weather_code");
            double vis = c.optDouble("visibility", 99999), gust = c.optDouble("wind_gusts_10m", 0);
            double celsius = mph ? (w.temp - 32) / 1.8 : w.temp;
            String icon;
            if (code >= 95) { icon = "⛈ Storm"; w.hazard = "Thunderstorms nearby: slow down"; }
            else if ((code >= 71 && code <= 77) || code == 85 || code == 86) { icon = "❄ Snow"; w.hazard = "Snow: double your following distance"; }
            else if (code == 56 || code == 57 || code == 66 || code == 67) { icon = "❄ Ice"; w.hazard = "Freezing rain: roads may be icy"; }
            else if ((code >= 51 && code <= 65) || (code >= 80 && code <= 82)) { icon = "☂ Rain"; w.hazard = "Wet road: leave extra distance"; }
            else if (code == 45 || code == 48) { icon = "☁ Fog"; w.hazard = "Fog: reduce speed"; }
            else icon = code <= 1 ? "☀ Clear" : "☁ Cloudy";
            if (w.hazard == null && celsius <= 1.5) w.hazard = "Near freezing: watch for ice";
            if (w.hazard == null && vis < 1000) w.hazard = "Low visibility ahead";
            if (w.hazard == null && gust > 40) w.hazard = "Strong wind gusts";
            w.summary = icon + "  " + Math.round(w.temp) + "°";
            main.post(() -> { weather = w; onChange.run(); });
        } catch (Exception e) { weatherTime = SystemClock.elapsedRealtime() - 570_000; Log.w(TAG, "weather " + e.getMessage()); }
    }

    void search(String text, double lat, double lon, boolean hasFix, Done<List<Place>> done) {
        userPool.execute(() -> {
            List<Place> out = new ArrayList<>();
            try {
                String key = prefs.tomtomKey();
                if (!key.isEmpty()) {
                    try {
                        String u = "https://api.tomtom.com/search/2/search/" + enc(text).replace("+", "%20") + ".json?limit=8&key=" + enc(key)
                                + (hasFix ? "&lat=" + lat + "&lon=" + lon : "");
                        JSONArray a = new JSONObject(http(u, null)).getJSONArray("results");
                        for (int i = 0; i < a.length(); i++) {
                            JSONObject o = a.getJSONObject(i);
                            Place p = new Place();
                            String poi = o.optJSONObject("poi") != null ? o.getJSONObject("poi").optString("name") : "";
                            String addr = o.optJSONObject("address") != null ? o.getJSONObject("address").optString("freeformAddress") : "";
                            p.name = poi.isEmpty() ? addr : poi + ", " + addr;
                            p.lat = o.getJSONObject("position").getDouble("lat"); p.lon = o.getJSONObject("position").getDouble("lon");
                            out.add(p);
                        }
                    } catch (Exception e) { Log.w(TAG, "tomtom search " + e.getMessage()); }
                }
                if (out.isEmpty()) {
                    String u = "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=8&q=" + enc(text);
                    if (hasFix) u += String.format(Locale.US, "&viewbox=%.3f,%.3f,%.3f,%.3f", lon - 1, lat + 1, lon + 1, lat - 1);
                    JSONArray a = new JSONArray(http(u, null));
                    for (int i = 0; i < a.length(); i++) {
                        JSONObject o = a.getJSONObject(i);
                        Place p = new Place();
                        p.name = o.getString("display_name"); p.lat = o.getDouble("lat"); p.lon = o.getDouble("lon");
                        out.add(p);
                    }
                }
                main.post(() -> done.done(out));
            } catch (Exception e) { Log.w(TAG, "search " + e.getMessage()); main.post(() -> done.done(null)); }
        });
    }

    void routes(double lat, double lon, double toLat, double toLon, Done<List<Route>> done) {
        userPool.execute(() -> {
            List<Route> out = null;
            String key = prefs.tomtomKey();
            if (!key.isEmpty()) {
                try { out = tomtomRoutes(lat, lon, toLat, toLon, key); } catch (Exception e) { Log.w(TAG, "tomtom route " + e.getMessage()); }
            }
            if (out == null || out.isEmpty()) {
                try { out = osrmRoutes(lat, lon, toLat, toLon); } catch (Exception e) { Log.w(TAG, "osrm " + e.getMessage()); }
            }
            List<Route> result = out;
            main.post(() -> done.done(result));
        });
    }

    private List<Route> tomtomRoutes(double lat, double lon, double toLat, double toLon, String key) throws Exception {
        String u = String.format(Locale.US, "https://api.tomtom.com/routing/1/calculateRoute/%.6f,%.6f:%.6f,%.6f/json?traffic=true&maxAlternatives=2&instructionsType=text&language=en-US&key=%s",
                lat, lon, toLat, toLon, enc(key));
        JSONArray a = new JSONObject(http(u, null)).getJSONArray("routes");
        List<Route> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.getJSONObject(i), s = o.getJSONObject("summary");
            Route r = new Route();
            r.meters = s.getDouble("lengthInMeters"); r.seconds = s.getDouble("travelTimeInSeconds");
            r.delay = s.optDouble("trafficDelayInSeconds", 0); r.liveTraffic = true;
            List<double[]> pts = new ArrayList<>();
            JSONArray legs = o.getJSONArray("legs");
            for (int l = 0; l < legs.length(); l++) {
                JSONArray p = legs.getJSONObject(l).getJSONArray("points");
                for (int k = 0; k < p.length(); k++)
                    pts.add(new double[]{p.getJSONObject(k).getDouble("latitude"), p.getJSONObject(k).getDouble("longitude")});
            }
            r.points = pts.toArray(new double[0][]);
            JSONObject g = o.optJSONObject("guidance");
            JSONArray in = g == null ? null : g.optJSONArray("instructions");
            for (int k = 0; in != null && k < in.length(); k++) {
                JSONObject x = in.getJSONObject(k);
                Step st = new Step();
                st.along = x.optDouble("routeOffsetInMeters"); st.text = x.optString("message");
                String m = x.optString("maneuver");
                st.direction = m.contains("LEFT") ? -1 : m.contains("RIGHT") ? 1 : 0;
                String road = x.optString("street");
                if (r.via.isEmpty() && k > 0 && !road.isEmpty()) r.via = road;
                r.steps.add(st);
            }
            out.add(r);
        }
        return out;
    }

    private List<Route> osrmRoutes(double lat, double lon, double toLat, double toLon) throws Exception {
        String u = String.format(Locale.US, "https://router.project-osrm.org/route/v1/driving/%.6f,%.6f;%.6f,%.6f?alternatives=true&steps=true&geometries=geojson&overview=full",
                lon, lat, toLon, toLat);
        JSONArray a = new JSONObject(http(u, null)).getJSONArray("routes");
        List<Route> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.getJSONObject(i);
            Route r = new Route();
            r.meters = o.getDouble("distance"); r.seconds = o.getDouble("duration");
            JSONArray c = o.getJSONObject("geometry").getJSONArray("coordinates");
            r.points = new double[c.length()][];
            for (int k = 0; k < c.length(); k++) r.points[k] = new double[]{c.getJSONArray(k).getDouble(1), c.getJSONArray(k).getDouble(0)};
            double run = 0, longest = 0;
            JSONArray legs = o.getJSONArray("legs");
            for (int l = 0; l < legs.length(); l++) {
                JSONArray steps = legs.getJSONObject(l).getJSONArray("steps");
                for (int k = 0; k < steps.length(); k++) {
                    JSONObject s = steps.getJSONObject(k), m = s.getJSONObject("maneuver");
                    String type = m.optString("type"), mod = m.optString("modifier"), name = s.optString("name");
                    if (name.isEmpty()) name = s.optString("ref");
                    Step st = new Step();
                    st.along = run;
                    st.direction = mod.contains("left") ? -1 : mod.contains("right") ? 1 : 0;
                    String verb;
                    if (type.equals("depart")) verb = "Head out";
                    else if (type.equals("arrive")) verb = "Arrive at your destination";
                    else if (type.contains("roundabout") || type.equals("rotary")) verb = "Take the roundabout";
                    else if (type.equals("merge")) verb = "Merge " + mod;
                    else if (type.equals("off ramp")) verb = "Take the exit " + mod;
                    else if (type.equals("on ramp")) verb = "Take the ramp " + mod;
                    else if (type.equals("fork")) verb = "Keep " + mod.replace("slight ", "");
                    else if (mod.equals("straight") || mod.isEmpty()) verb = "Continue";
                    else if (mod.equals("uturn")) verb = "Make a U-turn";
                    else verb = "Turn " + mod;
                    st.text = name.isEmpty() || type.equals("arrive") ? verb : verb + " onto " + name;
                    r.steps.add(st);
                    double d = s.optDouble("distance");
                    if (d > longest && !name.isEmpty()) { longest = d; r.via = name; }
                    run += d;
                }
            }
            out.add(r);
        }
        return out;
    }

    private static String enc(String s) throws IOException { return URLEncoder.encode(s, "UTF-8"); }

    private static String http(String url, String form) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(8000); c.setReadTimeout(15000);
            c.setRequestProperty("User-Agent", UA);
            if (form != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                try (OutputStream o = c.getOutputStream()) { o.write(form.getBytes("UTF-8")); }
            }
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
                return b.toString("UTF-8");
            }
        } finally { c.disconnect(); }
    }

    void close() { roadPool.shutdownNow(); livePool.shutdownNow(); userPool.shutdownNow(); }
}
