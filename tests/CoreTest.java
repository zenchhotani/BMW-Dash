import com.zen.bmwdash.core.*;
import java.util.*;
public class CoreTest {
    static int fails = 0;
    static void check(String n, boolean ok) { System.out.println((ok ? "ok   " : "FAIL ") + n); if (!ok) fails++; }
    static final int W = 480, H = 270;
    static int[] g; static boolean[] y;
    static Random rnd = new Random(1);
    static void road() { g = new int[W*H]; y = new boolean[W*H]; for (int i = 0; i < g.length; i++) g[i] = 70 + rnd.nextInt(14); }
    static void line(float xb, float xv, boolean yel, boolean dashed) {
        float hz = .5f, yb = .9f;
        for (int r = (int)(H*hz)+2; r < H; r++) {
            float yn = (float)r/H, x = xv + (xb-xv)*(yn-hz)/(yb-hz);
            float depth = 1f/(yn-hz);
            if (dashed && ((int)(depth*1.6f)) % 2 == 1) continue;
            int half = Math.max(1, (int)((yn-hz)*7));
            for (int c = (int)(x*W)-half; c <= (int)(x*W)+half; c++) if (c >= 0 && c < W) { g[r*W+c] = yel ? 150 : 205; y[r*W+c] = yel; }
        }
    }
    static int[] lamp(int color, int row) {
        int[] a = new int[20*50]; java.util.Arrays.fill(a, 0xFF181818);
        for (int r = row; r < row + 6; r++) for (int c = 7; c < 13; c++) a[r*20+c] = color;
        return a;
    }
    static void faint(float xb) {
        float hz = .5f, yb = .9f;
        for (int r = (int)(H*hz)+2; r < H; r++) {
            float yn = (float)r/H, x = .5f + (xb-.5f)*(yn-hz)/(yb-hz);
            int half = Math.max(1, (int)((yn-hz)*7));
            for (int c = (int)(x*W)-half; c <= (int)(x*W)+half; c++) if (c >= 0 && c < W) g[r*W+c] += 20;
        }
    }
    /** box of a 1.85 x 4.5 x 1.55 m car, x right and z ahead, with the default camera, side included */
    static Detection seen(float x, float z) {
        float f = .65f, a = 9f / 16f, hz = .5f, c = .5f, h = 1.25f;
        float l = 9, r = -9;
        for (float dx : new float[]{-.925f, .925f}) for (float dz : new float[]{0, 4.5f}) {
            float u = c + f * (x + dx) / (z + dz); l = Math.min(l, u); r = Math.max(r, u);
        }
        float bottom = hz + f * h / z / a, top = bottom - f * 1.55f / z / a;
        return new Detection("car", .8f, l, top, r, bottom);
    }

    static Track car(int id, float distance, float lateral, float rate) {
        Track c = new Track(); c.id = id; c.label = "car"; c.hits = 20; c.distance = distance; c.lateral = lateral; c.rangeRate = rate; c.motion = Track.MOVING; c.pathX = lateral; c.inPath = Math.abs(lateral) < 1.5f; return c;
    }
    public static void main(String[] a) {
        LaneDetector d = new LaneDetector();
        for (float shift : new float[]{0f, -.12f, .12f}) {
            road(); line(.18f+shift, .5f, true, false); line(.82f+shift, .5f, false, false);
            line(-.45f+shift, .5f, false, true); line(1.45f+shift, .5f, false, true);
            LaneDetector.Result r = d.detect(g, y, W, H, .5f, .9f, .5f);
            check("both lines shift " + shift, r.left != null && r.right != null);
            if (r.left != null && r.right != null) {
                System.out.printf("     L %.3f R %.3f yellow %.2f support %.2f/%.2f%n", r.left.x(.9f), r.right.x(.9f), r.left.yellowFraction, r.left.support, r.right.support);
                check("left accurate", Math.abs(r.left.x(.9f) - (.18f+shift)) < .04);
                check("right accurate", Math.abs(r.right.x(.9f) - (.82f+shift)) < .04);
                check("left yellow", r.left.yellowFraction > .5 && r.right.yellowFraction < .2);
            }
            LaneState s = new LaneState();
            for (int t = 0; t <= 5000; t += 100) s.update(d.detect(g, y, W, H, .5f, .9f, .5f), t, .5f, .9f);
            LaneState.Position want = shift == 0 ? LaneState.Position.CENTERED : shift < 0 ? LaneState.Position.DRIFT_RIGHT : LaneState.Position.DRIFT_LEFT;
            // negative shift: lane moved left in frame, so car sits right of centre
            if (Math.abs(shift) == .12f) check("position " + s.position + " offset " + s.offset, Math.abs(s.offset - (-shift/.64f)) < .08);
            else check("centered", s.position == want);
            check("leftmost " + s.type, s.type == LaneState.LaneType.LEFTMOST);
        }
        road(); line(.18f, .5f, false, true); line(.82f, .5f, false, false);
        LaneState s = new LaneState();
        for (int t = 0; t <= 5000; t += 100) s.update(d.detect(g, y, W, H, .5f, .9f, .5f), t, .5f, .9f);
        System.out.println("     dashed/solid support " + s.leftSolid + " " + s.rightSolid);
        check("rightmost " + s.type, s.type == LaneState.LaneType.RIGHTMOST);
        road(); line(.18f, .5f, false, true); line(.82f, .5f, false, true);
        s = new LaneState();
        for (int t = 0; t <= 5000; t += 100) s.update(d.detect(g, y, W, H, .5f, .9f, .5f), t, .5f, .9f);
        check("middle " + s.type, s.type == LaneState.LaneType.MIDDLE);
        road(); int found = 0; for (int i = 0; i < 20; i++) { road(); LaneDetector.Result r = d.detect(g, y, W, H, .5f, .9f, .5f); if (r.left != null || r.right != null) found++; }
        check("no lanes on blank road " + found, found == 0);

        // car approaching from 30 m at 5 m/s
        Tracker tk = new Tracker(); List<Track> out = null;
        for (int t = 0; t <= 3000; t += 100) {
            float m = 30 - 5*t/1000f, w = tk.focal*1.85f/m, b = .5f + tk.focal*1.25f/m/tk.aspect;
            out = tk.update(List.of(new Detection("car", .9f, .5f-w/2, b-w*1.4f, .5f+w/2, b)), t, null);
        }
        Track l = out.get(0);
        System.out.println("     dist " + l.distance + " rate " + l.rangeRate);
        check("lead", l.lead && out.size() == 1);
        check("distance", Math.abs(l.distance - 15) < 2.5);
        check("rate", Math.abs(l.rangeRate + 5) < 1.2);
        Advisor.Input in = new Advisor.Input(); in.speed = 20; in.lead = l; l.distance = 12; l.rangeRate = -6;
        Safety safe = new Safety(); Safety.Event ev = null; l.hits = 20;
        for (int t = 0; t <= 600; t += 100) { Safety.Input si = new Safety.Input(); si.t = t; si.speed = 20; si.seeing = true; si.tracks = List.of(l); ev = safe.update(si); }
        check("brake", ev != null && ev.code.equals("brake") && ev.level == Advisor.DANGER);
        l.rangeRate = 0; l.distance = 40;
        for (int t = 5000; t <= 8000; t += 100) { Safety.Input si = new Safety.Input(); si.t = t; si.speed = 20; si.seeing = true; si.tracks = List.of(l); ev = safe.update(si); }
        check("quiet when steady", ev == null);
        Safety s2 = new Safety(); l.distance = 6;
        for (int t = 0; t <= 9000; t += 100) { Safety.Input si = new Safety.Input(); si.t = t; si.speed = 0; si.seeing = true; si.tracks = List.of(l); l.distance = t < 6000 ? 6 : 11; ev = s2.update(si); if (ev != null) break; }
        check("traffic moving", ev != null && ev.code.equals("go"));
        Track person = new Track(); person.label = "person"; person.hits = 5; person.confirmed = true; person.distance = 12; person.lateral = .3f; person.pathX = .3f;
        Safety.Input pi = new Safety.Input(); pi.t = 100; pi.speed = 12; pi.seeing = true; pi.tracks = List.of(person);
        ev = new Safety().update(pi); check("person in path", ev != null && ev.level == Advisor.DANGER);
        person.confirmed = false;
        check("unconfirmed person is quiet", new Safety().update(pi) == null);
        Detection walker = new Detection("person", .72f, .48f, .45f, .53f, .70f);
        check("real person shape", RoadUserFilter.plausible(walker, .5f, 9f / 16f, true));
        check("huge person box rejected", !RoadUserFilter.plausible(new Detection("person", .8f, .2f, .2f, .55f, .9f), .5f, 9f / 16f, true));
        check("weak person rejected", !RoadUserFilter.plausible(new Detection("person", .4f, .48f, .45f, .53f, .70f), .5f, 9f / 16f, false));
        check("floating dog rejected", !RoadUserFilter.plausible(new Detection("dog", .8f, .4f, .2f, .46f, .3f), .5f, 9f / 16f, false));
        check("cars not filtered", RoadUserFilter.plausible(new Detection("car", .34f, .4f, .2f, .46f, .3f), .5f, 9f / 16f, true));
        int[] night = new int[100 * 100]; java.util.Arrays.fill(night, 0xFF202020);
        check("dark box is not glare", !RoadUserFilter.glare(night, 100, 100, walker));
        for (int r = 45; r < 58; r++) for (int c = 48; c < 53; c++) night[r * 100 + c] = 0xFFFFFFFF;
        check("headlight is glare", RoadUserFilter.glare(night, 100, 100, walker));
        Tracker blink = new Tracker();
        blink.update(List.of(walker), 0, null); List<Track> two = blink.update(List.of(walker), 250, null);
        check("brief person hidden", two.isEmpty());
        blink.update(List.of(walker), 500, null); List<Track> held = blink.update(List.of(walker), 750, null);
        check("steady person shown", held.size() == 1 && held.get(0).confirmed);
        int R = 0xFFFF2010, G = 0xFF20F0C0, Y = 0xFFFFC020;
        check("red lamp", SignalColor.classify(lamp(R, 6), 20, 50) == SignalColor.RED);
        check("green lamp", SignalColor.classify(lamp(G, 40), 20, 50) == SignalColor.GREEN);
        check("yellow lamp", SignalColor.classify(lamp(Y, 23), 20, 50) == SignalColor.YELLOW);
        check("dark housing", SignalColor.classify(lamp(0xFF202020, 6), 20, 50) == SignalColor.NONE);
        check("grey sky", SignalColor.classify(lamp(0xFFC8C8D0, 6), 20, 50) == SignalColor.NONE);
        // fast lamp finder
        {
            int[] f = new int[W*H]; java.util.Arrays.fill(f, 0xFFB0B8C8);
            for (int r = 40; r < 80; r++) for (int c = 225; c < 245; c++) f[r*W+c] = 0xFF181818;
            for (int r = 46; r < 52; r++) for (int c = 232; c < 238; c++) f[r*W+c] = 0xFFFF2A20;
            LampFinder lf = new LampFinder();
            LampFinder.Lamp a1 = lf.find(f, W, H, .5f, .5f, null);
            check("lamp seen at once", a1 != null && a1.color == SignalColor.RED && !a1.confirmed);
            lf.find(f, W, H, .5f, .5f, null);
            check("lamp confirmed on third look", lf.find(f, W, H, .5f, .5f, null).confirmed);
            LampFinder inCar = new LampFinder(); List<float[]> cars = new ArrayList<>(); cars.add(new float[]{.4f,.1f,.6f,.45f});
            for (int i = 0; i < 4; i++) check("lamp inside a vehicle ignored " + i, inCar.find(f, W, H, .5f, .5f, cars) == null);
            int[] sky = new int[W*H]; java.util.Arrays.fill(sky, 0xFFB0B8C8);
            for (int r = 46; r < 52; r++) for (int c = 232; c < 238; c++) sky[r*W+c] = 0xFFFF2A20;
            check("lamp without a housing ignored", new LampFinder().find(sky, W, H, .5f, .5f, null) == null);
            int[] sign = new int[W*H]; java.util.Arrays.fill(sign, 0xFF181818);
            for (int r = 30; r < 90; r++) for (int c = 200; c < 290; c++) sign[r*W+c] = 0xFF10A040;
            check("big green sign ignored", new LampFinder().find(sign, W, H, .5f, .5f, null) == null);
            int[] low = f.clone();
            check("lamp below the horizon ignored", new LampFinder().find(low, W, H, .15f, .5f, null) == null);
            for (int r = 46; r < 52; r++) for (int c = 232; c < 238; c++) f[r*W+c] = 0xFF20E060;
            LampFinder sw = new LampFinder(); for (int i = 0; i < 3; i++) sw.find(f, W, H, .5f, .5f, null);
            check("green needs five looks", !sw.find(f, W, H, .5f, .5f, null).confirmed);
            check("green lamp read", sw.find(f, W, H, .5f, .5f, null).color == SignalColor.GREEN);
        }
        SignalState st = new SignalState();
        check("one read ignored", st.update(SignalColor.RED, 0) == SignalColor.NONE);
        check("two reads show", st.update(SignalColor.RED, 300) == SignalColor.RED);
        check("flicker held", st.update(SignalColor.NONE, 900) == SignalColor.RED);
        st.update(SignalColor.GREEN, 1000);
        check("changes to green", st.update(SignalColor.GREEN, 1300) == SignalColor.GREEN);
        check("expires", st.update(SignalColor.NONE, 3200) == SignalColor.NONE);
        double[][] cut = Geo.slice(new double[][]{{40,-75},{40.01,-75}}, 100, 200);
        check("slice", cut.length == 2 && Math.abs(Geo.distance(cut[0][0],cut[0][1],cut[1][0],cut[1][1]) - 100) < 1);
        check("tomtom limit", Math.abs(Geo.parseMaxSpeedKmh("35.00mph") - 56.33) < .1);
        check("mph", Math.abs(Geo.parseMaxSpeedKmh("55 mph") - 88.5) < .1 && Double.isNaN(Geo.parseMaxSpeedKmh("signals")));
        double[] p = Geo.project(new double[][]{{40,-75},{40.01,-75}}, 40.005, -75.0001);
        check("project", p[0] < 12 && Math.abs(p[1]-556) < 8);
        double[][] corner = {{40,-75},{40.0005,-75},{40.0005,-74.999}}; // 55 m north, then right (east)
        List<float[]> ahead = ArPath.build(corner, 0, ArPath.viewHeading(corner, 0, 0, Double.NaN), 120, 5);
        float[] near = ahead.get(4), far = ahead.get(ahead.size()-1);
        check("ar straight", Math.abs(near[0]) < .5 && Math.abs(near[1]-20) < .5);
        check("ar right turn " + far[0], far[0] > 30 && Math.abs(far[1]-55.6) < 1);
        double[][] leftward = {{40,-75},{40.0005,-75},{40.0005,-75.001}};
        List<float[]> lf = ArPath.build(leftward, 0, 0, 120, 5);
        check("ar left turn", lf.get(lf.size()-1)[0] < -30);
        double rd = ArPath.bearingAt(corner, 0);
        check("ar follows road", Math.abs(ArPath.viewHeading(corner, 0, 0, rd + 18) - rd) < .01);
        check("ar honours real turn-off", Math.abs(ArPath.turn(rd, ArPath.viewHeading(corner, 0, 0, rd + 40)) - 40) < .01);
        List<float[]> wild = new ArrayList<>(); wild.add(new float[]{0, 5, 0}); wild.add(new float[]{2, 15, 10}); wild.add(new float[]{40, 18, 50});
        check("ar stays in view", ArPath.inView(wild).size() == 2);
        LaneState kept = new LaneState(); kept.horizon = .5f; LaneDetector xd = new LaneDetector();
        for (int ms = 0; ms <= 3000; ms += 100) { road(); line(.18f, .5f, false, false); line(.82f, .5f, false, false); kept.update(xd.detect(g, y, W, H, .5f, .9f, .5f), ms, .5f, .9f); }
        check("good pair kept", kept.hasLeft && kept.hasRight);
        // the same highway false alarm seen on the road: a screen-sized "animal" and "person"
        check("highway glare animal rejected", !RoadUserFilter.plausible(new Detection("bear", .75f, .3f, .25f, .75f, .7f), .5f, 9f / 16f, true));
        // faded paint
        LaneDetector fd = new LaneDetector();
        road(); faint(.18f); faint(.82f);
        LaneDetector.Result fr = fd.detect(g, y, W, H, .5f, .9f, .5f);
        check("faded lines", fr.left != null && fr.right != null && Math.abs(fr.left.x(.9f) - .18f) < .04);
        LaneState ls = new LaneState();
        LaneDetector gd = new LaneDetector();
        for (int ms = 0; ms <= 3000; ms += 100) { road(); line(.18f, .5f, false, false); line(.82f, .5f, false, false); ls.update(gd.detect(g, y, W, H, .5f, .9f, .5f), ms, .5f, .9f); }
        for (int ms = 3100; ms <= 6000; ms += 100) { road(); line(.18f, .5f, false, false); ls.update(gd.detect(g, y, W, H, .5f, .9f, .5f), ms, .5f, .9f); }
        check("missing side estimated", ls.hasRight && ls.estimatedRight && Math.abs(ls.ra + ls.rb * .9f - .82f) < .05);

        // neighbour drifting into our lane
        Predictor pr = new Predictor(); Safety.Event pe = null; String intent = "";
        for (int ms = 0; ms <= 2500; ms += 100) {
            Track c = car(1, 25, 3.5f - ms / 1000f, 0);
            Safety.Event x = pr.update(ms, 20, List.of(c)); if (x != null) pe = x; if (!c.intent.isEmpty()) intent = c.intent;
        }
        check("cut-in predicted " + intent, pe != null && pe.code.equals("p-cut"));
        pr = new Predictor(); pe = null;
        for (int ms = 0; ms <= 4000; ms += 100) { Safety.Event x = pr.update(ms, 20, List.of(car(2, 25, 3.5f, 0))); if (x != null) pe = x; }
        check("steady neighbour quiet", pe == null);
        pr = new Predictor(); pe = null;
        for (int ms = 0; ms <= 3000; ms += 100) { Track c = car(3, 30, 0, -3f * ms / 1000f); c.lead = true; Safety.Event x = pr.update(ms, 20, List.of(c)); if (x != null) pe = x; }
        check("slowing predicted", pe != null && pe.code.equals("p-slow"));
        pr = new Predictor(); pe = null;
        for (int ms = 0; ms <= 9000; ms += 100) { Safety.Event x = pr.update(ms, 20, List.of(car(4, 30, (float) Math.sin(ms / 600.0), 0))); if (x != null) pe = x; }
        check("swerving neighbour no longer nags", pe == null || !pe.code.equals("p-weave"));
        pr = new Predictor(); pe = null;
        for (int ms = 0; ms <= 3000; ms += 100) { Safety.Event x = pr.update(ms, 20, List.of(car(5, 25, 3.5f - ms / 1000f, 0), car(6, 25, -2f - ms / 1000f, 0))); if (x != null) pe = x; }
        check("own turn ignored", pe == null);
        check("weather kinds", Conditions.kind(0) == Conditions.CLEAR && Conditions.kind(63) == Conditions.RAIN && Conditions.kind(73) == Conditions.SNOW
                && Conditions.kind(66) == Conditions.ICE && Conditions.kind(45) == Conditions.FOG && Double.isNaN(Conditions.suggested(25, Conditions.CLEAR)));
        // closing gently: quiet when dry, warns earlier in snow
        Safety.Event dry = null, snowy = null;
        for (double caution : new double[]{1, 1.5}) {
            Safety sf = new Safety(); Safety.Event got = null; Track c = car(9, 38, 0, -10); c.lead = true;
            for (int ms = 0; ms <= 600; ms += 100) { Safety.Input si = new Safety.Input(); si.t = ms; si.speed = 20; si.seeing = true; si.tracks = List.of(c); si.caution = caution; got = sf.update(si); }
            if (caution == 1) dry = got; else snowy = got;
        }
        check("earlier warning in snow", dry == null && snowy != null);
        ViewQuality vq = new ViewQuality(); int[] fog = new int[W*H]; java.util.Arrays.fill(fog, 150); boolean blind = false;
        for (int ms = 0; ms <= 8000; ms += 200) blind = vq.update(fog, W, H, 150, .9f, ms);
        check("blocked view", blind);
        int[] busy = new int[W*H]; for (int i = 0; i < busy.length; i++) busy[i] = (i % W / 6) % 2 == 0 ? 60 : 180;
        for (int ms = 8200; ms <= 12000; ms += 200) blind = vq.update(busy, W, H, 120, .9f, ms);
        check("view recovers", !blind);
        // camera model: a car x metres right of us, z ahead, as the detector would box it, side included
        // parked car beside the road while we drive at 12 m/s: parked, never the lead, no brake warning
        Tracker tp = new Tracker(); tp.ego = 12; Safety sp = new Safety(); Safety.Event pev = null; Track pk = null;
        for (int ms = 0; ms <= 2600; ms += 130) {
            List<Track> o = tp.update(List.of(seen(3.0f, 40 - 12 * ms / 1000f)), ms, null);
            pk = o.isEmpty() ? null : o.get(0);
            Safety.Input si = new Safety.Input(); si.t = ms; si.speed = 12; si.seeing = true; si.tracks = o; Safety.Event x = sp.update(si); if (x != null) pev = x;
        }
        check("parked quiet", pk != null && pk.motion == Track.PARKED && !pk.lead && !pk.inPath && pev == null);
        Tracker tm = new Tracker(); tm.ego = 12; Track mv = null;
        for (int ms = 0; ms <= 2000; ms += 130) { List<Track> o = tm.update(List.of(seen(0.1f, 20)), ms, null); mv = o.isEmpty() ? null : o.get(0); }
        check("moving car", mv != null && mv.motion == Track.MOVING && mv.lead && mv.inPath);
        Tracker tn = new Tracker(); tn.ego = 12; Track nx = null;
        for (int ms = 0; ms <= 2000; ms += 130) { List<Track> o = tn.update(List.of(seen(3.6f, 14)), ms, null); nx = o.isEmpty() ? null : o.get(0); }
        check("next lane car is not ours", nx != null && !nx.lead && !nx.inPath && Math.abs(nx.lateral - 3.6f) < 0.8f);
        Tracker ts = new Tracker(); ts.ego = 12; sp = new Safety(); pev = null; Track stc = null;
        for (int ms = 0; ms <= 2600; ms += 130) {
            List<Track> o = ts.update(List.of(seen(0.2f, 45 - 12 * ms / 1000f)), ms, null); stc = o.isEmpty() ? null : o.get(0);
            Safety.Input si = new Safety.Input(); si.t = ms; si.speed = 12; si.seeing = true; si.tracks = o; Safety.Event x = sp.update(si); if (x != null && x.code.equals("brake")) pev = x;
        }
        check("stopped car dead ahead still warns", pev != null && stc.motion == Track.STOPPED && stc.lead);
        Tracker tc = new Tracker(); tc.ego = 15; tc.curvature = 1 / 150.0; List<Track> bend = null;
        for (int ms = 0; ms <= 1500; ms += 130) bend = tc.update(List.of(seen(-3f, 30), seen(0.5f, 30)), ms, null);
        Track onBend = bend.get(0).lateral < 0 ? bend.get(0) : bend.get(1), straight = onBend == bend.get(0) ? bend.get(1) : bend.get(0);
        check("path follows the bend", onBend.inPath && onBend.lead && !straight.inPath);
        Tracker tg = new Tracker(); tg.ego = 0; Track go = null;
        for (int ms = 0; ms <= 2000; ms += 130) { List<Track> o = tg.update(List.of(seen(0f, 8 + 4 * ms / 1000f)), ms, null); go = o.isEmpty() ? null : o.get(0); }
        check("car pulling away while we wait", go != null && go.motion == Track.MOVING);
        Track side = new Track(); side.id = 1; side.label = "traffic light"; side.left = .8f; side.right = .84f; side.top = .2f; side.bottom = .3f;
        Track mid = new Track(); mid.id = 2; mid.label = "traffic light"; mid.left = .52f; mid.right = .54f; mid.top = .2f; mid.bottom = .28f;
        check("our light only", new SignalPicker().pick(List.of(side, mid), .5f, .5f, 1920) == mid && !side.ours);
        SignReader sr = new SignReader();
        List<SignReader.Line> sign = List.of(new SignReader.Line("SPEED", 10, 10, 60, 22), new SignReader.Line("LIMIT", 12, 24, 58, 36), new SignReader.Line("15", 20, 40, 50, 66));
        check("one read waits", sr.read(sign, 1000).isEmpty());
        List<SignReader.Sign> got = sr.read(sign, 1700);
        check("limit sign", got.size() == 1 && got.get(0).limit == 15);
        SignReader sr2 = new SignReader(); List<SignReader.Line> st2 = List.of(new SignReader.Line("STOP", 0, 0, 40, 20), new SignReader.Line("Bus Stop Cafe", 0, 80, 90, 95));
        sr2.read(st2, 1000); got = sr2.read(st2, 1500);
        check("stop word", got.size() == 1 && got.get(0).code.equals("stop"));
        check("road check never slowed when moving or warm", Pace.detectMs(0, 28, 0) <= 100 && Pace.detectMs(2, 12, 0) <= 100);
        check("rests when parked", Pace.detectMs(0, 0, 10_000) == 300 && Pace.detectMs(0, 0, 200_000) == 600);
        check("backs off only when hot", Pace.detectMs(3, 28, 0) == 160 && Pace.detectMs(4, 28, 0) == 260 && Pace.frameMs(3) > Pace.frameMs(0));
        int farN = 0, farSlow = 0; for (int i = 0; i < 6; i++) { if (Pace.pass(i, 28) == 4) farN++; if (Pace.pass(i, 12) == 4) farSlow++; }
        check("far road at highway speed", farN == 2 && farSlow == 0);
        check("sign reading gives way first", Pace.signMs(2, 28) > Pace.signMs(0, 28) && Pace.signMs(0, 28) <= 1200 && Pace.signMs(3, 12) >= 2000);
        // two red lamps level with each other, a little below the horizon: one car; a red wash: nothing
        int TW = 480, TH = 270; int[] dark = new int[TW * TH];
        for (int y = 150; y < 155; y++) { for (int x = 220; x < 226; x++) dark[y * TW + x] = 0xFFE02020; for (int x = 262; x < 268; x++) dark[y * TW + x] = 0xFFE02020; }
        List<Detection> lamps = Taillights.find(dark, TW, TH, .5f, .85f, TH / (float) TW);
        check("tail lamps make a car", lamps.size() == 1 && lamps.get(0).lamps && lamps.get(0).left < 220 / 480f && lamps.get(0).right > 268 / 480f);
        int[] wash = new int[TW * TH]; java.util.Arrays.fill(wash, 0xFFD01818);
        check("red wash is not a car", Taillights.find(wash, TW, TH, .5f, .85f, TH / (float) TW).isEmpty());
        int[] sky = new int[TW * TH];
        for (int y = 60; y < 65; y++) { for (int x = 220; x < 226; x++) sky[y * TW + x] = 0xFFE02020; for (int x = 262; x < 268; x++) sky[y * TW + x] = 0xFFE02020; }
        check("red lights in the sky are not a car", Taillights.find(sky, TW, TH, .5f, .85f, TH / (float) TW).isEmpty());
        Scene sc = new Scene(); Track a1 = car(40, 30, 1, 0), noRange = car(41, Float.NaN, 0, 0), nearer = car(42, 12, -3, 0);
        sc.feed(List.of(a1, noRange, nearer), 1000); List<Scene.Thing> th = sc.step(1000);
        check("3d places only measured things, farN first", th.size() == 2 && th.get(0).id == 40);
        a1.distance = 20; sc.feed(List.of(a1), 1200); th = sc.step(1200);
        check("3d glides", th.get(0).z < 30 && th.get(0).z > 20);
        for (int ms = 1400; ms <= 3000; ms += 100) th = sc.step(ms);
        check("3d drops lost things", th.isEmpty());
        Track ghost = car(43, 15, 0, 0); ghost.label = "person"; sc.feed(List.of(ghost), 3100);
        check("3d hides unproven people", sc.step(3100).isEmpty());
        float[] nearP = Scene.project(2, 5, 0, 1000, 600), away = Scene.project(2, 50, 0, 1000, 600);
        check("3d perspective", nearP[0] > away[0] && nearP[1] > away[1] && away[0] > 500 && nearP[1] < 600);
        LaneState laneX = new LaneState(); laneX.hasLeft = laneX.hasRight = true; laneX.la = 1.1f; laneX.lb = -1f; laneX.ra = -.1f; laneX.rb = 1f;
        float top = laneX.drawTop(.55f);
        check("lanes stop below where they meet", top > .6f && laneX.ra + laneX.rb * top > laneX.la + laneX.lb * top);
        Tracker trkX = new Tracker(); List<Detection> d1 = List.of(new Detection("car", .6f, .50f, .50f, .53f, .53f));
        trkX.update(d1, 0, null); List<Track> tr1 = trkX.update(List.of(new Detection("car", .6f, .525f, .50f, .555f, .53f)), 200, null);
        check("small farN car followed", tr1.size() == 1 && tr1.get(0).hits == 2);
        Advisor.Input pv = new Advisor.Input(); pv.speed = 28; pv.poorView = true;
        check("no all clear when blind", Advisor.advise(pv).text.startsWith("Low camera"));
        System.out.println(fails + " failures"); System.exit(fails == 0 ? 0 : 1);
    }
}
