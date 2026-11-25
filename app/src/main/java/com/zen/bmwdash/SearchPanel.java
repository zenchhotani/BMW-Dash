package com.zen.bmwdash;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognizerIntent;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.ViewGroup;
import android.view.Window;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;

/** Full-screen destination search: voice, live suggestions, nearby shortcuts, Home, Work and recents. */
final class SearchPanel {
    interface Go { void to(double lat, double lon); }
    static final int VOICE = 41;

    private final Activity activity;
    private final Prefs prefs;
    private final RoadData data;
    private final Go go;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Dialog dialog;
    private EditText box;
    private LinearLayout list;
    private double lat, lon;
    private boolean hasFix, quiet;
    private Runnable pending;

    SearchPanel(Activity a, Prefs p, RoadData d, Go g) { activity = a; prefs = p; data = d; go = g; }

    private int dp(float v) { return Ui.dp(activity, v); }

    void open(double la, double lo, boolean fix, boolean moving) {
        lat = la; lon = lo; hasFix = fix;
        dialog = new Dialog(activity, android.R.style.Theme_Material_NoActionBar);
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.setPadding(dp(14), dp(14), dp(14), dp(8));

        LinearLayout top = new LinearLayout(activity);
        box = new EditText(activity);
        box.setHint("Where to?");
        box.setTextSize(20);
        box.setSingleLine(true);
        box.setTextColor(Ui.TEXT);
        box.setHintTextColor(Ui.DIM);
        box.setInputType(InputType.TYPE_CLASS_TEXT);
        box.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        box.setPadding(dp(16), dp(12), dp(16), dp(12));
        box.setBackground(Ui.card(activity, Ui.CARD, 26, Ui.CYAN));
        top.addView(box, new LinearLayout.LayoutParams(0, -2, 1));
        add(top, Ui.button(activity, "🎤 Speak", Ui.VIOLET, this::speak));
        add(top, Ui.button(activity, "✕", Ui.RED, () -> dialog.dismiss()));
        root.addView(top);

        LinearLayout saved = new LinearLayout(activity);
        add(saved, saved("🏠 Home", prefs.home(), "home"));
        add(saved, saved("💼 Work", prefs.work(), "work"));
        String[][] kinds = {{"⛽ Gas", "gas station"}, {"⚡ EV", "charging station"}, {"☕ Coffee", "coffee"},
                {"🍔 Food", "restaurant"}, {"🅿 Parking", "parking"}, {"🏥 Hospital", "hospital"}, {"🚻 Rest area", "rest area"}};
        for (String[] k : kinds) add(saved, Ui.button(activity, k[0], Ui.BLUE, () -> run(k[1], true)));
        HorizontalScrollView chips = new HorizontalScrollView(activity);
        chips.setHorizontalScrollBarEnabled(false);
        chips.addView(saved);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
        clp.topMargin = dp(10);
        root.addView(chips, clp);
        if (moving) root.addView(Ui.text(activity, "Driving: use Speak or a shortcut. Leave typing to a passenger.", 13, Ui.AMBER, true));

        list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        box.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                if (quiet) return;
                if (pending != null) handler.removeCallbacks(pending);
                String q = s.toString().trim();
                if (q.length() < 3) { recents(); return; }
                // wait for a pause in typing to be kind to the free search services
                pending = () -> run(q, false);
                handler.postDelayed(pending, 450);
            }
        });
        box.setOnEditorActionListener((v, a, e) -> {
            String q = box.getText().toString().trim();
            if (!q.isEmpty()) run(q, false);
            return true;
        });
        dialog.setContentView(root);
        dialog.setOnDismissListener(d -> { if (pending != null) handler.removeCallbacks(pending); });
        dialog.show();
        Window w = dialog.getWindow();
        if (w != null) w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        recents();
    }

    private void add(LinearLayout row, TextView v) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
        p.leftMargin = row.getChildCount() == 0 ? 0 : dp(8);
        row.addView(v, p);
    }

    private TextView saved(String label, String value, String key) {
        TextView t = Ui.button(activity, label, value.isEmpty() ? Ui.DIM : Ui.GREEN, () -> {
            RoadData.Place p = decode(value);
            if (p != null) choose(p);
            else Toast.makeText(activity, "Search a place, then press and hold it to save", Toast.LENGTH_LONG).show();
        });
        return t;
    }

    private void speak() {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PROMPT, "Where to?");
        try { activity.startActivityForResult(i, VOICE); }
        catch (RuntimeException e) { Toast.makeText(activity, "Voice search is not available on this phone", Toast.LENGTH_SHORT).show(); }
    }

    void heard(String text) {
        if (dialog == null || !dialog.isShowing()) return;
        quiet = true; box.setText(text); quiet = false;
        run(text.replaceFirst("(?i)^(navigate|take me|drive|directions)( to)? ", ""), false);
    }

    private void run(String query, boolean category) {
        if (pending != null) handler.removeCallbacks(pending);
        note("Searching…");
        data.search(query, lat, lon, hasFix, category, places -> {
            if (dialog == null || !dialog.isShowing()) return;
            if (places == null) note("Search failed. Check your connection.");
            else if (places.isEmpty()) note("Nothing found");
            else show(places);
        });
    }

    private void note(String s) {
        list.removeAllViews();
        TextView t = Ui.text(activity, s, 15, Ui.DIM, false);
        t.setPadding(dp(8), dp(18), dp(8), dp(8));
        list.addView(t);
    }

    private void show(List<RoadData.Place> places) {
        list.removeAllViews();
        for (RoadData.Place p : places) row(p);
    }

    private void recents() {
        list.removeAllViews();
        try {
            JSONArray a = new JSONArray(prefs.recents());
            if (a.length() > 0) list.addView(Ui.text(activity, "\n  RECENT", 12, Ui.DIM, true));
            for (int i = 0; i < a.length(); i++) {
                RoadData.Place p = fromJson(a.getJSONObject(i));
                if (hasFix) p.meters = com.zen.bmwdash.core.Geo.distance(lat, lon, p.lat, p.lon);
                row(p);
            }
        } catch (JSONException e) { prefs.edit().remove("recents").apply(); }
    }

    private void row(RoadData.Place p) {
        String far = Double.isNaN(p.meters) ? "" : Ui.distance(p.meters, prefs.mph()) + (p.address.isEmpty() ? "" : "  •  ");
        String title = p.name, detail = far + p.address;
        android.text.SpannableString s = new android.text.SpannableString(title + "\n" + detail);
        s.setSpan(new android.text.style.RelativeSizeSpan(.75f), title.length() + 1, s.length(), 0);
        s.setSpan(new android.text.style.ForegroundColorSpan(Ui.CYAN), title.length() + 1, s.length(), 0);
        TextView t = Ui.text(activity, "", 18, Ui.TEXT, true);
        t.setText(s);
        t.setPadding(dp(16), dp(14), dp(16), dp(14));
        t.setBackground(Ui.card(activity, Ui.CARD, 18, Ui.STROKE));
        t.setOnClickListener(v -> choose(p));
        t.setOnLongClickListener(v -> {
            new AlertDialog.Builder(activity).setTitle("Save " + p.name)
                    .setItems(new String[]{"As Home", "As Work"}, (d, i) -> {
                        prefs.edit().putString(i == 0 ? "home" : "work", toJson(p).toString()).apply();
                        Toast.makeText(activity, "Saved", Toast.LENGTH_SHORT).show();
                    }).show();
            return true;
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(8);
        list.addView(t, lp);
    }

    private void choose(RoadData.Place p) {
        try {
            JSONArray old = new JSONArray(prefs.recents()), fresh = new JSONArray().put(toJson(p));
            for (int i = 0; i < old.length() && fresh.length() < 6; i++)
                if (!old.getJSONObject(i).optString("n").equals(p.name)) fresh.put(old.getJSONObject(i));
            prefs.edit().putString("recents", fresh.toString()).apply();
        } catch (JSONException ignored) { }
        dialog.dismiss();
        go.to(p.lat, p.lon);
    }

    private static JSONObject toJson(RoadData.Place p) {
        try { return new JSONObject().put("n", p.name).put("a", p.address).put("y", p.lat).put("x", p.lon); }
        catch (JSONException e) { throw new IllegalStateException(e); }
    }
    private static RoadData.Place fromJson(JSONObject o) {
        RoadData.Place p = new RoadData.Place();
        p.name = o.optString("n"); p.address = o.optString("a"); p.lat = o.optDouble("y"); p.lon = o.optDouble("x");
        return p;
    }
    private static RoadData.Place decode(String value) {
        if (value.isEmpty()) return null;
        try { return fromJson(new JSONObject(value)); } catch (JSONException e) { return null; }
    }
}
