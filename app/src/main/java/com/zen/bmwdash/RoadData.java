package com.zen.bmwdash;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import com.zen.bmwdash.core.Conditions;
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
import java.util.Collections;
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
    static final class Weather { String summary = "", hazard; double temp = Double.NaN, gap = 2; int kind; boolean ice; }
    /** an official National Weather Service alert */
    static final class Alert { String event = "", headline = ""; int severity; }
    static final class Incident { double lat, lon; String text, title; int severity, category; }
    static final class Camera { double lat, lon; }
    /** slow traffic found further along; speeds in m/s */
    static final class Jam { double lat, lon, speed, free; }
    static final class Place { String name = "", address = ""; double lat, lon, meters = Double.NaN; }
    static final class Step { double along; String text; int direction; }
    static final class Route {
        double[][] points; double meters, seconds, delay; boolean liveTraffic; String via = "";
        List<Step> steps = new ArrayList<>();
    }
    interface Done<T> { void done(T value); }

    private static final String TAG = "RoadData", UA = "BMWDash/1.0 (personal Android dash app; github.com/zenchhotani/BMW-Dash)";
    private final ExecutorService roadPool = Executors.newSingleThreadExecutor(), livePool = Executors.newSingleThreadExecutor(),
            userPool = Executors.newSingleThreadExecutor(), searchPool = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Prefs prefs;

    private JSONArray ways;
    private double waysLat, waysLon;
    private long waysTime = -100000, roadTry = -100000, flowTime = -100000, incidentTime = -1000000, weatherTime = -10000000;
    private double flowLat, flowLon;

    Road road = new Road();
    private Road tom = new Road(), osm = new Road(), nom = new Road();
    private long tomTry = -100000, nomTry = -100000;
    private double tomLat, tomLon, tomAtLat, tomAtLon, nomLat, nomLon, nomAtLat, nomAtLon;
    private final long[] blockedUntil = new long[2];
    /** why the last road lookup failed, empty when fine */
    String roadProblem = "";
    private String nearName = "";
    double flow = Double.NaN, freeFlow = Double.NaN; // m/s
    Weather weather;
    List<Incident> incidents = new ArrayList<>();
    List<Camera> cameras = new ArrayList<>();
    double aheadFlow = Double.NaN; // m/s, slow traffic within about 800 m
    List<Jam> jams = new ArrayList<>();
    /** active route, or null; traffic is sampled along it */
    double[][] path;
    private long aheadTime = -1000000, alertTime = -1000000, outlookTime = -1000000;
    List<Alert> alerts = new ArrayList<>();
    /** first change in weather further along the route, empty when none */
    String outlook = "";
    private double[][] outlookRoute;
    String trafficStatus = "";
    Runnable onChange = () -> {};

    RoadData(Prefs p) { prefs = p; }

    void refreshNow() { flowTime = incidentTime = tomTry = nomTry = aheadTime = alertTime = outlookTime = -1000000; trafficStatus = roadProblem = ""; }

    /** Call on the main thread with each location fix; throttles itself. */
    void update(double lat, double lon, double heading, boolean headingValid) {
        long now = SystemClock.elapsedRealtime();
        String roadKey = prefs.tomtomKey();
        // 1) TomTom (with a key): road name and posted limit
        double fromTom = Geo.distance(lat, lon, tomLat, tomLon);
        if (!roadKey.isEmpty() && now - tomTry > 6_000 && (fromTom > 60 || now - tomTry > 60_000)) {
            tomTry = now; tomLat = lat; tomLon = lon;
            livePool.execute(() -> fetchTomTomRoad(lat, lon, heading, headingValid, roadKey));
        }
        // 2) OpenStreetMap Overpass: limit and lane count
        boolean stale = ways == null || Geo.distance(lat, lon, waysLat, waysLon) > 220 || now - waysTime > 600_000;
        if (stale && now - roadTry > 15_000) {
            roadTry = now;
            roadPool.execute(() -> fetchWays(lat, lon, heading, headingValid));
        }
        // 3) Nominatim: road name when neither of the above has one (their policy: go gently)
        boolean unnamed = tom.name.isEmpty() && osm.name.isEmpty();
        if (unnamed && now - nomTry > 15_000 && (nom.name.isEmpty() || Geo.distance(lat, lon, nomLat, nomLon) > 80)) {
            nomTry = now; nomLat = lat; nomLon = lon;
            userPool.execute(() -> fetchNominatimRoad(lat, lon));
        }
        // never show a name or limit from a place we have left
        if (Geo.distance(lat, lon, tomAtLat, tomAtLon) > 300) tom = new Road();
        if (Geo.distance(lat, lon, nomAtLat, nomAtLon) > 300) nom = new Road();
        match(lat, lon, heading, headingValid);

        String key = prefs.tomtomKey();
        if (!key.isEmpty()) {
            double moved = Geo.distance(lat, lon, flowLat, flowLon);
            if (now - flowTime > 45_000 || (moved > 400 && now - flowTime > 15_000)) {
                flowTime = now; flowLat = lat; flowLon = lon;
                livePool.execute(() -> fetchFlow(lat, lon, key));
            }
            if ((headingValid || path != null) && now - aheadTime > 75_000) {
                aheadTime = now;
                double[][] along = path;
                livePool.execute(() -> fetchAhead(lat, lon, heading, headingValid, along, key));
            }
            if (now - incidentTime > 120_000) { incidentTime = now; livePool.execute(() -> fetchIncidents(lat, lon, key)); }
        } else { flow = freeFlow = aheadFlow = Double.NaN; incidents = new ArrayList<>(); jams = new ArrayList<>(); }
        if (now - alertTime > 300_000) { alertTime = now; userPool.execute(() -> fetchAlerts(lat, lon)); }
        double[][] route = path;
        if (route == null || !prefs.outlook()) { outlook = ""; outlookRoute = null; }
        else if (route != outlookRoute || now - outlookTime > 900_000) {
            if (route != outlookRoute) outlook = "";
            outlookRoute = route; outlookTime = now;
            livePool.execute(() -> fetchOutlook(lat, lon, route));
        }
        if (now - weatherTime > 600_000) { weatherTime = now; livePool.execute(() -> fetchWeather(lat, lon)); }
    }

    private void fetchTomTomRoad(double lat, double lon, double heading, boolean valid, String key) {
        try {
            String u = String.format(Locale.US, "https://api.tomtom.com/search/2/reverseGeocode/%.6f,%.6f.json?returnSpeedLimit=true&radius=120&key=%s", lat, lon, enc(key));
            if (valid) u += "&heading=" + Math.round(heading);
            JSONArray a = new JSONObject(http(u, null)).getJSONArray("addresses");
            Road found = new Road();
            if (a.length() > 0) {
                JSONObject ad = a.getJSONObject(0).getJSONObject("address");
                found.name = ad.optString("streetName", ad.optString("street"));
                String limit = ad.optString("speedLimit").toLowerCase(Locale.US);
                found.limit = Geo.parseMaxSpeedKmh(limit) / 3.6;
            }
            main.post(() -> { tom = found; tomAtLat = lat; tomAtLon = lon; roadProblem = ""; compose(); onChange.run(); });
        } catch (Exception e) {
            Log.w(TAG, "tomtom road " + e.getMessage());
            String why = describe("TomTom", e);
            main.post(() -> { roadProblem = why; onChange.run(); });
        }
    }

    private void fetchNominatimRoad(double lat, double lon) {
        try {
            String u = String.format(Locale.US, "https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=17&extratags=1&lat=%.6f&lon=%.6f", lat, lon);
            JSONObject o = new JSONObject(http(u, null));
            Road found = new Road();
            JSONObject ad = o.optJSONObject("address"), extra = o.optJSONObject("extratags");
            if (ad != null) found.name = ad.optString("road");
            if (extra != null && extra.has("maxspeed")) found.limit = Geo.parseMaxSpeedKmh(extra.optString("maxspeed")) / 3.6;
            main.post(() -> { nom = found; nomAtLat = lat; nomAtLon = lon; compose(); onChange.run(); });
        } catch (Exception e) {
            Log.w(TAG, "nominatim " + e.getMessage());
            String why = describe("Road lookup", e);
            main.post(() -> { roadProblem = why; onChange.run(); });
        }
    }

    private void fetchWays(double lat, double lon, double heading, boolean valid) {
        String q = String.format(Locale.US, "[out:json][timeout:10];(way(around:350,%.6f,%.6f)[highway~\"^(motorway|trunk|primary|secondary|tertiary|unclassified|residential|living_street|motorway_link|trunk_link|primary_link|secondary_link|tertiary_link)$\"];node(around:1500,%.6f,%.6f)[highway=speed_camera];);out geom;", lat, lon, lat, lon);
        String[] hosts = {"https://overpass-api.de/api/interpreter", "https://overpass.private.coffee/api/interpreter"};
        for (int h = 0; h < hosts.length; h++) {
            if (SystemClock.elapsedRealtime() < blockedUntil[h]) continue;
            try {
                JSONArray a = new JSONObject(http(hosts[h], "data=" + URLEncoder.encode(q, "UTF-8"))).getJSONArray("elements");
                List<Camera> found = new ArrayList<>();
                for (int i = 0; i < a.length(); i++) {
                    JSONObject n = a.getJSONObject(i);
                    if (!"node".equals(n.optString("type"))) continue;
                    Camera c = new Camera(); c.lat = n.optDouble("lat"); c.lon = n.optDouble("lon"); found.add(c);
                }
                main.post(() -> {
                    cameras = found;
                    ways = a; waysLat = lat; waysLon = lon; waysTime = SystemClock.elapsedRealtime();
                    match(lat, lon, heading, valid); onChange.run();
                });
                return;
            } catch (Exception e) {
                Log.w(TAG, "overpass " + e.getMessage());
                String m = String.valueOf(e.getMessage());
                // back off hard when a server refuses us, so we do not get banned
                long hold = m.contains("HTTP 406") || m.contains("HTTP 403") ? 3_600_000 : m.contains("HTTP 429") ? 120_000 : 30_000;
                blockedUntil[h] = SystemClock.elapsedRealtime() + hold;
            }
        }
    }

    private static String describe(String who, Exception e) {
        String m = String.valueOf(e.getMessage());
        if (m.contains("HTTP 403") || m.contains("HTTP 401")) return who + ": key rejected or limit reached";
        if (m.contains("HTTP 429")) return who + ": too many requests";
        if (e instanceof java.net.UnknownHostException || e instanceof java.net.SocketTimeoutException) return "No internet connection";
        return who + " failed: " + m;
    }

    private void compose() {
        Road r = new Road();
        r.name = !tom.name.isEmpty() ? tom.name : !osm.name.isEmpty() ? osm.name : !nom.name.isEmpty() ? nom.name
                : nearName.isEmpty() ? "" : "Near " + nearName;
        r.limit = !Double.isNaN(tom.limit) ? tom.limit : !Double.isNaN(osm.limit) ? osm.limit : nom.limit;
        r.lanes = osm.lanes; r.oneway = osm.oneway;
        road = r;
    }

    private void match(double lat, double lon, double heading, boolean headingValid) {
        if (ways == null) { compose(); return; }
        JSONObject best = null; double bestScore = 55, nearest = 150;
        String near = "";
        for (int i = 0; i < ways.length(); i++) {
            JSONObject w = ways.optJSONObject(i);
            JSONArray g = w == null ? null : w.optJSONArray("geometry");
            if (g == null) continue;
            for (int j = 0; j + 1 < g.length(); j++) {
                JSONObject a = g.optJSONObject(j), b = g.optJSONObject(j + 1);
                if (a == null || b == null) continue;
                double la = a.optDouble("lat"), lo = a.optDouble("lon"), lb = b.optDouble("lat"), ob = b.optDouble("lon");
                double score = Geo.toSegment(lat, lon, la, lo, lb, ob)[0];
                JSONObject tags = w.optJSONObject("tags");
                if (tags != null && score < nearest && !tags.optString("name").isEmpty()) { nearest = score; near = tags.optString("name"); }
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
        osm = r;
        nearName = near;
        compose();
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

    /** Samples live speed at several points further along the route, or straight ahead without one. */
    private void fetchAhead(double lat, double lon, double heading, boolean valid, double[][] route, String key) {
        List<Jam> found = new ArrayList<>();
        double near = Double.NaN;
        double start = route == null ? 0 : Geo.project(route, lat, lon)[1];
        for (double d : new double[]{600, 1500, 3000, 5000}) {
            try {
                double aLat, aLon, course;
                if (route != null) {
                    double[][] bit = Geo.slice(route, start + d, start + d + 40);
                    if (bit.length < 2) break;
                    aLat = bit[0][0]; aLon = bit[0][1];
                    course = Geo.bearing(aLat, aLon, bit[bit.length - 1][0], bit[bit.length - 1][1]);
                } else {
                    // a straight guess is only good for a short way
                    if (!valid || d > 3000) break;
                    aLat = lat + d * Math.cos(Math.toRadians(heading)) / 111_320;
                    aLon = lon + d * Math.sin(Math.toRadians(heading)) / (111_320 * Math.max(0.2, Math.cos(Math.toRadians(lat))));
                    course = heading;
                }
                String u = String.format(Locale.US, "https://api.tomtom.com/traffic/services/4/flowSegmentData/absolute/12/json?point=%.6f,%.6f&unit=KMPH&key=%s", aLat, aLon, enc(key));
                JSONObject o = new JSONObject(http(u, null)).getJSONObject("flowSegmentData");
                double c = o.getDouble("currentSpeed") / 3.6, f = o.getDouble("freeFlowSpeed") / 3.6;
                if (c >= f * 0.65 || o.optDouble("confidence", 1) <= 0.5) continue;
                // ignore side streets and the opposite carriageway
                if (!sameWay(o.getJSONObject("coordinates").getJSONArray("coordinate"), aLat, aLon, course)) continue;
                Jam j = new Jam(); j.lat = aLat; j.lon = aLon; j.speed = c; j.free = f; found.add(j);
                if (d <= 600) near = c;
            } catch (Exception e) { Log.w(TAG, "ahead " + e.getMessage()); }
        }
        double value = near;
        main.post(() -> { aheadFlow = value; jams = found; onChange.run(); });
    }

    private static boolean sameWay(JSONArray c, double lat, double lon, double course) {
        for (int i = 0; i + 1 < c.length(); i++) {
            JSONObject a = c.optJSONObject(i), b = c.optJSONObject(i + 1);
            if (a == null || b == null) continue;
            double la = a.optDouble("latitude"), lo = a.optDouble("longitude"), lb = b.optDouble("latitude"), ob = b.optDouble("longitude");
            if (Geo.toSegment(lat, lon, la, lo, lb, ob)[0] > 80) continue;
            double turn = Math.abs(((Geo.bearing(la, lo, lb, ob) - course + 540) % 360) - 180);
            if (turn < 50) return true;
        }
        return false;
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
                in.category = p.optInt("iconCategory");
                in.title = title(in.category);
                list.add(in);
            }
            main.post(() -> { incidents = list; onChange.run(); });
        } catch (Exception e) { Log.w(TAG, "incidents " + e.getMessage()); }
    }

    private static String title(int category) {
        switch (category) {
            case 1: return "Accident";
            case 2: return "Fog";
            case 3: return "Dangerous conditions";
            case 4: return "Heavy rain";
            case 5: return "Ice";
            case 6: return "Traffic jam";
            case 7: return "Lane closed";
            case 8: return "Road closed";
            case 9: return "Road work";
            case 10: return "Strong wind";
            case 11: return "Flooding";
            case 14: return "Broken down vehicle";
            default: return "Road incident";
        }
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
            w.kind = Conditions.kind(code);
            if (w.kind == Conditions.CLEAR && vis < 1000) w.kind = Conditions.FOG;
            w.ice = Conditions.iceRisk(celsius);
            if (w.hazard == null && celsius <= 1.5) w.hazard = "Near freezing: watch for ice";
            if (w.hazard == null && vis < 1000) w.hazard = "Low visibility ahead";
            if (w.hazard == null && gust > 40) w.hazard = "Strong wind gusts";
            w.gap = Conditions.gap(w.kind, w.ice);
            w.summary = icon + "  " + Math.round(w.temp) + "°";
            main.post(() -> { weather = w; onChange.run(); });
        } catch (Exception e) { weatherTime = SystemClock.elapsedRealtime() - 570_000; Log.w(TAG, "weather " + e.getMessage()); }
    }

    /** National Weather Service alerts for this spot (United States only, no key needed). */
    private void fetchAlerts(double lat, double lon) {
        try {
            String u = String.format(Locale.US, "https://api.weather.gov/alerts/active?point=%.4f,%.4f", lat, lon);
            JSONArray a = new JSONObject(http(u, null)).getJSONArray("features");
            List<Alert> found = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) {
                JSONObject p = a.getJSONObject(i).getJSONObject("properties");
                if (!"Actual".equals(p.optString("status", "Actual"))) continue;
                Alert al = new Alert();
                al.event = p.optString("event"); al.headline = p.optString("headline");
                String s = p.optString("severity");
                al.severity = s.equals("Extreme") ? 4 : s.equals("Severe") ? 3 : s.equals("Moderate") ? 2 : 1;
                // alerts that change how you should drive come first
                if (al.event.matches("(?i).*(snow|ice|winter|blizzard|flood|fog|tornado|thunderstorm|wind|freez|squall|hurricane).*")) al.severity += 4;
                found.add(al);
            }
            Collections.sort(found, (x, y) -> y.severity - x.severity);
            main.post(() -> { alerts = found; onChange.run(); });
        } catch (Exception e) { Log.w(TAG, "alerts " + e.getMessage()); }
    }

    /** Forecast at points along the route for about the time we should reach them. */
    private void fetchOutlook(double lat, double lon, double[][] route) {
        try {
            double start = Geo.project(route, lat, lon)[1];
            StringBuilder la = new StringBuilder(String.format(Locale.US, "%.3f", lat)), lo = new StringBuilder(String.format(Locale.US, "%.3f", lon));
            List<Double> at = new ArrayList<>();
            at.add(0.0);
            for (double d : new double[]{8000, 20000, 40000, 70000, 110000, 160000}) {
                double[][] bit = Geo.slice(route, start + d, start + d + 100);
                if (bit.length < 2) break;
                la.append(String.format(Locale.US, ",%.3f", bit[0][0])); lo.append(String.format(Locale.US, ",%.3f", bit[0][1]));
                at.add(d);
            }
            String text = "";
            if (at.size() > 1) {
                String u = "https://api.open-meteo.com/v1/forecast?latitude=" + la + "&longitude=" + lo
                        + "&hourly=weather_code,temperature_2m&forecast_hours=8&timeformat=unixtime";
                JSONArray a = new JSONArray(http(u, null));
                int here = Conditions.CLEAR;
                for (int i = 0; i < a.length() && i < at.size(); i++) {
                    JSONObject h = a.getJSONObject(i).getJSONObject("hourly");
                    JSONArray time = h.getJSONArray("time"), code = h.getJSONArray("weather_code"), temp = h.getJSONArray("temperature_2m");
                    // rough arrival time at about 70 km/h
                    long eta = System.currentTimeMillis() / 1000 + (long) (at.get(i) / 19.5);
                    int best = 0;
                    for (int k = 1; k < time.length(); k++) if (Math.abs(time.getLong(k) - eta) < Math.abs(time.getLong(best) - eta)) best = k;
                    int kind = Conditions.kind(code.optInt(best));
                    if (kind == Conditions.CLEAR && Conditions.iceRisk(temp.optDouble(best))) kind = Conditions.ICE;
                    if (i == 0) { here = kind; continue; }
                    if (kind == Conditions.CLEAR || kind == here) continue;
                    String what = kind == Conditions.SNOW ? "Snow" : kind == Conditions.RAIN ? "Rain" : kind == Conditions.FOG ? "Fog" : "Icy conditions";
                    String miles = prefs.mph() ? Math.round(at.get(i) / 1609.344) + " miles" : Math.round(at.get(i) / 1000) + " kilometres";
                    text = what + " likely in about " + miles + ", around " + new java.text.SimpleDateFormat("h:mm a", Locale.US).format(new java.util.Date(eta * 1000));
                    break;
                }
            }
            String result = text;
            main.post(() -> { if (outlookRoute == route) { outlook = result; onChange.run(); } });
        } catch (Exception e) { Log.w(TAG, "outlook " + e.getMessage()); }
    }

    /** True when a mapped bridge lies just ahead. */
    boolean bridgeAhead(double lat, double lon, double heading) {
        if (ways == null) return false;
        for (int i = 0; i < ways.length(); i++) {
            JSONObject w = ways.optJSONObject(i), t = w == null ? null : w.optJSONObject("tags");
            if (t == null || !(t.optString("bridge").equals("yes") || t.optString("bridge").equals("viaduct"))) continue;
            JSONArray g = w.optJSONArray("geometry");
            for (int j = 0; g != null && j < g.length(); j++) {
                JSONObject n = g.optJSONObject(j);
                if (n == null) continue;
                double d = Geo.distance(lat, lon, n.optDouble("lat"), n.optDouble("lon"));
                if (d > 20 && d < 250 && Math.abs(((Geo.bearing(lat, lon, n.optDouble("lat"), n.optDouble("lon")) - heading + 540) % 360) - 180) < 15) return true;
            }
        }
        return false;
    }

    private int searchSeq;

    /**
     * Places for what the driver typed or said. TomTom with a key; otherwise Photon (made for
     * search-as-you-type) or, for nearby categories, Nominatim.
     */
    void search(String text, double lat, double lon, boolean hasFix, boolean category, Done<List<Place>> done) {
        int seq = ++searchSeq;
        searchPool.execute(() -> {
            if (seq != searchSeq) return; // a newer keystroke is waiting
            List<Place> out = new ArrayList<>();
            boolean failed = false;
            String key = prefs.tomtomKey();
            if (!key.isEmpty()) {
                try {
                    String path = enc(text).replace("+", "%20");
                    String u = "https://api.tomtom.com/search/2/" + (category ? "categorySearch/" : "search/") + path
                            + ".json?limit=10&key=" + enc(key) + (category ? "&radius=30000" : "&typeahead=true")
                            + (hasFix ? "&lat=" + lat + "&lon=" + lon : "");
                    JSONArray a = new JSONObject(http(u, null)).getJSONArray("results");
                    for (int i = 0; i < a.length(); i++) {
                        JSONObject o = a.getJSONObject(i);
                        JSONObject poi = o.optJSONObject("poi"), ad = o.optJSONObject("address");
                        String name = poi != null ? poi.optString("name") : "";
                        String addr = ad != null ? ad.optString("freeformAddress") : "";
                        JSONObject pos = o.getJSONObject("position");
                        out.add(place(name, addr, pos.getDouble("lat"), pos.getDouble("lon")));
                    }
                } catch (Exception e) { Log.w(TAG, "tomtom search " + e.getMessage()); }
            }
            if (out.isEmpty() && category && hasFix) {
                try {
                    String u = String.format(Locale.US, "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=10&bounded=1&viewbox=%.3f,%.3f,%.3f,%.3f&q=",
                            lon - .15, lat + .12, lon + .15, lat - .12) + enc(text);
                    JSONArray a = new JSONArray(http(u, null));
                    for (int i = 0; i < a.length(); i++) {
                        JSONObject o = a.getJSONObject(i);
                        String full = o.getString("display_name"), name = o.optString("name");
                        if (!name.isEmpty() && full.startsWith(name)) full = full.substring(name.length()).replaceFirst("^,\\s*", "");
                        out.add(place(name, full, o.getDouble("lat"), o.getDouble("lon")));
                    }
                } catch (Exception e) { failed = true; Log.w(TAG, "nominatim search " + e.getMessage()); }
            } else if (out.isEmpty()) {
                try {
                    String u = "https://photon.komoot.io/api/?limit=10&q=" + enc(text) + (hasFix ? "&lat=" + lat + "&lon=" + lon : "");
                    JSONArray a = new JSONObject(http(u, null)).getJSONArray("features");
                    for (int i = 0; i < a.length(); i++) {
                        JSONObject f = a.getJSONObject(i), p = f.getJSONObject("properties");
                        JSONArray c = f.getJSONObject("geometry").getJSONArray("coordinates");
                        String street = (p.optString("housenumber") + " " + p.optString("street")).trim();
                        StringBuilder addr = new StringBuilder();
                        for (String part : new String[]{street, p.optString("city"), p.optString("state")}) {
                            if (part.isEmpty()) continue;
                            if (addr.length() > 0) addr.append(", ");
                            addr.append(part);
                        }
                        out.add(place(p.optString("name"), addr.toString(), c.getDouble(1), c.getDouble(0)));
                    }
                } catch (Exception e) { failed = true; Log.w(TAG, "photon " + e.getMessage()); }
            }
            if (hasFix) {
                for (Place p : out) p.meters = Geo.distance(lat, lon, p.lat, p.lon);
                if (category) java.util.Collections.sort(out, (x, y) -> Double.compare(x.meters, y.meters));
            }
            List<Place> result = out.isEmpty() && failed ? null : out;
            main.post(() -> { if (seq == searchSeq) done.done(result); });
        });
    }

    private static Place place(String name, String address, double lat, double lon) {
        Place p = new Place();
        p.name = name.isEmpty() ? address : name;
        p.address = name.isEmpty() ? "" : address;
        p.lat = lat; p.lon = lon;
        return p;
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
            c.setRequestProperty("Accept", "application/json,*/*");
            c.setRequestProperty("Accept-Language", "en-US,en");
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

    void close() { roadPool.shutdownNow(); livePool.shutdownNow(); userPool.shutdownNow(); searchPool.shutdownNow(); }
}
