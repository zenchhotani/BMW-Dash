package com.zen.bmwdash;

import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.maplibre.android.MapLibre;
import org.maplibre.android.camera.CameraPosition;
import org.maplibre.android.camera.CameraUpdateFactory;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.geometry.LatLngBounds;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.MapLibreMapOptions;
import org.maplibre.android.maps.MapView;
import org.maplibre.android.maps.Style;
import org.maplibre.android.style.layers.CircleLayer;
import org.maplibre.android.style.layers.FillExtrusionLayer;
import org.maplibre.android.style.expressions.Expression;
import org.maplibre.android.style.layers.LineLayer;
import org.maplibre.android.style.layers.Property;
import org.maplibre.android.style.layers.PropertyFactory;
import org.maplibre.android.style.layers.RasterLayer;
import org.maplibre.android.style.sources.GeoJsonSource;
import org.maplibre.android.style.sources.RasterSource;
import org.maplibre.android.style.sources.TileSet;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Live 3D follow map with traffic, incidents and route choices. */
final class MapPanel extends FrameLayout {
    interface Pick { void destination(double lat, double lon); }

    final MapView view;
    private MapLibreMap map;
    private Style style;
    private final Prefs prefs;
    private final TextView recenter;
    private boolean follow = true, hasFix;
    private double lat = 39.9526, lon = -75.1652, bearing, speed;
    private List<RoadData.Route> routes = new ArrayList<>();
    private int selected;
    private List<RoadData.Incident> incidents = new ArrayList<>();
    private double[] destination;
    Pick pick;
    private boolean navigating;
    private double turnMeters = Double.NaN;
    private double[][] turn = new double[0][];

    MapPanel(Context c, Prefs p, Bundle state) {
        super(c);
        prefs = p;
        MapLibre.getInstance(c);
        MapLibreMapOptions o = MapLibreMapOptions.createFromAttributes(c)
                .textureMode(true).compassEnabled(false).logoEnabled(false)
                .camera(new CameraPosition.Builder().target(new LatLng(lat, lon)).zoom(11).build());
        view = new MapView(c, o);
        view.onCreate(state);
        addView(view, new LayoutParams(-1, -1));
        recenter = Ui.button(c, "◎  Follow", Ui.CYAN, () -> { follow = true; showRecenter(); move(); });
        LayoutParams lp = new LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        lp.setMargins(0, 0, Ui.dp(c, 10), Ui.dp(c, 34));
        addView(recenter, lp);
        showRecenter();
        view.getMapAsync(m -> {
            map = m;
            m.addOnCameraMoveStartedListener(reason -> {
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) { follow = false; showRecenter(); }
            });
            m.addOnMapLongClickListener(ll -> { if (pick != null) pick.destination(ll.getLatitude(), ll.getLongitude()); return true; });
            loadStyle();
        });
    }

    @Override public boolean dispatchTouchEvent(MotionEvent e) {
        getParent().requestDisallowInterceptTouchEvent(true);
        return super.dispatchTouchEvent(e);
    }

    private void showRecenter() { recenter.setVisibility(follow ? GONE : VISIBLE); }

    void loadStyle() {
        if (map == null) return;
        style = null;
        String url = "https://tiles.openfreemap.org/styles/" + (prefs.mapDark() ? "fiord" : "liberty");
        map.setStyle(new Style.Builder().fromUri(url), s -> {
            style = s;
            String key = prefs.tomtomKey();
            if (!key.isEmpty() && prefs.mapTraffic()) {
                TileSet ts = new TileSet("2.2.0", "https://api.tomtom.com/traffic/map/4/tile/flow/relative0/{z}/{x}/{y}.png?tileSize=256&key=" + android.net.Uri.encode(key));
                ts.setMaxZoom(20);
                s.addSource(new RasterSource("traffic", ts, 256));
                RasterLayer r = new RasterLayer("traffic", "traffic");
                r.setProperties(PropertyFactory.rasterOpacity(0.85f));
                s.addLayer(r);
            }
            if (s.getSource("openmaptiles") != null) {
                FillExtrusionLayer b = new FillExtrusionLayer("dash-3d", "openmaptiles");
                b.setSourceLayer("building");
                b.setMinZoom(14f);
                b.setProperties(PropertyFactory.fillExtrusionColor(Color.parseColor(prefs.mapDark() ? "#3B4A73" : "#C9CFDD")),
                        PropertyFactory.fillExtrusionHeight(Expression.get("render_height")),
                        PropertyFactory.fillExtrusionBase(Expression.get("render_min_height")),
                        PropertyFactory.fillExtrusionOpacity(0.75f));
                s.addLayer(b);
            }
            addRadar(s);
            s.addSource(new GeoJsonSource("turn"));
            s.addSource(new GeoJsonSource("alt"));
            s.addSource(new GeoJsonSource("route"));
            s.addSource(new GeoJsonSource("incidents"));
            s.addSource(new GeoJsonSource("dest"));
            s.addSource(new GeoJsonSource("car"));
            s.addLayer(line("alt", "alt", Color.parseColor("#7C89A8"), 6f));
            s.addLayer(line("routeGlow", "route", Color.parseColor("#661C9BFF"), 15f));
            s.addLayer(line("route", "route", Color.parseColor("#35F0E0"), 6f));
            s.addLayer(line("turnGlow", "turn", Color.parseColor("#667A5CFF"), 22f));
            s.addLayer(line("turn", "turn", Color.WHITE, 8f));
            s.addLayer(dot("incidents", "incidents", "#FF3B5C", 7f));
            s.addLayer(dot("dest", "dest", "#7A5CFF", 11f));
            s.addLayer(dot("carGlow", "car", "#1C9BFF", 22f).withProperties(PropertyFactory.circleOpacity(0.28f), PropertyFactory.circleStrokeWidth(0f)));
            s.addLayer(dot("car", "car", "#1C9BFF", 9f));
            drawAll();
        });
    }

    private long radarAt;

    /** Rain and snow radar (US), from the Iowa Environmental Mesonet. */
    private void addRadar(Style s) {
        radarAt = android.os.SystemClock.elapsedRealtime();
        if (!prefs.radar()) return;
        // the changing query makes the map fetch fresh pictures
        TileSet ts = new TileSet("2.2.0", "https://mesonet.agron.iastate.edu/cache/tile.py/1.0.0/nexrad-n0q-900913/{z}/{x}/{y}.png?t=" + System.currentTimeMillis() / 300_000);
        ts.setMaxZoom(10);
        s.addSource(new RasterSource("radar", ts, 256));
        RasterLayer r = new RasterLayer("radar", "radar");
        r.setProperties(PropertyFactory.rasterOpacity(0.5f));
        if (s.getLayer("alt") != null) s.addLayerBelow(r, "alt"); else s.addLayer(r);
    }

    void refreshRadar() {
        if (style == null || !prefs.radar() || android.os.SystemClock.elapsedRealtime() - radarAt < 300_000) return;
        style.removeLayer("radar");
        style.removeSource("radar");
        addRadar(style);
    }

    private static LineLayer line(String id, String source, int color, float width) {
        LineLayer l = new LineLayer(id, source);
        l.setProperties(PropertyFactory.lineColor(color), PropertyFactory.lineWidth(width),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND));
        return l;
    }

    private static CircleLayer dot(String id, String source, String color, float radius) {
        CircleLayer l = new CircleLayer(id, source);
        l.setProperties(PropertyFactory.circleColor(Color.parseColor(color)), PropertyFactory.circleRadius(radius),
                PropertyFactory.circleStrokeColor(Color.WHITE), PropertyFactory.circleStrokeWidth(2.5f));
        return l;
    }

    void location(double la, double lo, double brg, double spd) {
        boolean first = !hasFix;
        hasFix = true; lat = la; lon = lo; bearing = brg; speed = spd;
        set("car", points(new double[][]{{la, lo}}));
        if (follow) { if (first && map != null) map.moveCamera(CameraUpdateFactory.newCameraPosition(camera())); else move(); }
    }

    private CameraPosition camera() {
        double zoom = speed > 25 ? 15 : speed > 13 ? 15.8 : 16.6;
        double tilt = 50, top = 0;
        if (navigating) {
            // chase view: car low on the screen, closer in as a turn approaches
            zoom = !Double.isNaN(turnMeters) && turnMeters < 220 ? 17.8 : speed > 25 ? 15.8 : speed > 13 ? 16.5 : 17.2;
            tilt = 60;
            top = getHeight() * 0.38;
        }
        return new CameraPosition.Builder().target(new LatLng(lat, lon)).zoom(zoom).bearing(bearing).tilt(tilt)
                .padding(0, top, 0, 0).build();
    }
    private void move() { if (map != null && hasFix) map.animateCamera(CameraUpdateFactory.newCameraPosition(camera()), 900); }

    void incidents(List<RoadData.Incident> list) {
        if (list == incidents) return;
        incidents = list; drawAll();
    }

    void routes(List<RoadData.Route> list, int sel, double[] dest, boolean overview) {
        routes = list; selected = sel; destination = dest; drawAll();
        if (overview && map != null && !list.isEmpty()) {
            LatLngBounds.Builder b = new LatLngBounds.Builder();
            for (RoadData.Route r : list) for (double[] p : r.points) b.include(new LatLng(p[0], p[1]));
            follow = false; showRecenter();
            try { map.animateCamera(CameraUpdateFactory.newLatLngBounds(b.build(), Ui.dp(getContext(), 46)), 900); }
            catch (Exception ignored) { }
        }
    }

    void navigation(boolean on) {
        if (navigating == on) return;
        navigating = on;
        if (!on) { turn = new double[0][]; turnMeters = Double.NaN; set("turn", lines(new ArrayList<>())); }
        if (follow) move();
    }

    /** highlights the stretch of road where the next turn happens */
    void nextTurn(double[][] stretch, double meters) {
        turnMeters = meters;
        turn = stretch;
        List<double[][]> l = new ArrayList<>();
        if (stretch.length > 1) l.add(stretch);
        set("turn", lines(l));
    }

    void followCar() { follow = true; showRecenter(); move(); }

    private void drawAll() {
        if (style == null) return;
        List<double[][]> alt = new ArrayList<>(), main = new ArrayList<>();
        for (int i = 0; i < routes.size(); i++) (i == selected ? main : alt).add(routes.get(i).points);
        set("alt", lines(alt));
        set("route", lines(main));
        double[][] in = new double[incidents.size()][];
        for (int i = 0; i < in.length; i++) in[i] = new double[]{incidents.get(i).lat, incidents.get(i).lon};
        set("incidents", points(in));
        set("dest", points(destination == null ? new double[0][] : new double[][]{destination}));
        if (hasFix) set("car", points(new double[][]{{lat, lon}}));
    }

    private void set(String id, String json) {
        if (style == null || !style.isFullyLoaded()) return;
        GeoJsonSource s = style.getSourceAs(id);
        if (s != null) s.setGeoJson(json);
    }

    private static String collection(JSONArray features) {
        try { return new JSONObject().put("type", "FeatureCollection").put("features", features).toString(); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    private static JSONObject feature(String type, JSONArray coords) throws org.json.JSONException {
        return new JSONObject().put("type", "Feature").put("properties", new JSONObject())
                .put("geometry", new JSONObject().put("type", type).put("coordinates", coords));
    }
    private static String points(double[][] pts) {
        JSONArray f = new JSONArray();
        try { for (double[] p : pts) f.put(feature("Point", new JSONArray().put(p[1]).put(p[0]))); }
        catch (org.json.JSONException e) { throw new IllegalStateException(e); }
        return collection(f);
    }
    private static String lines(List<double[][]> ls) {
        JSONArray f = new JSONArray();
        try {
            for (double[][] l : ls) {
                JSONArray c = new JSONArray();
                for (double[] p : l) c.put(new JSONArray().put(p[1]).put(p[0]));
                f.put(feature("LineString", c));
            }
        } catch (org.json.JSONException e) { throw new IllegalStateException(e); }
        return collection(f);
    }
}
