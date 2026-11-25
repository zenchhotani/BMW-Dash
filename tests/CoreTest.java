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
        Track person = new Track(); person.label = "person"; person.hits = 5; person.distance = 12; person.lateral = .3f;
        Safety.Input pi = new Safety.Input(); pi.t = 100; pi.speed = 12; pi.seeing = true; pi.tracks = List.of(person);
        ev = new Safety().update(pi); check("person in path", ev != null && ev.level == Advisor.DANGER);
        int[] red = new int[600]; java.util.Arrays.fill(red, 0xFF202020); for (int i = 0; i < 40; i++) red[i] = 0xFFFF2010;
        check("red light", SignalColor.classify(red) == SignalColor.RED && SignalColor.classify(new int[600]) == SignalColor.NONE);
        double[][] cut = Geo.slice(new double[][]{{40,-75},{40.01,-75}}, 100, 200);
        check("slice", cut.length == 2 && Math.abs(Geo.distance(cut[0][0],cut[0][1],cut[1][0],cut[1][1]) - 100) < 1);
        check("tomtom limit", Math.abs(Geo.parseMaxSpeedKmh("35.00mph") - 56.33) < .1);
        check("mph", Math.abs(Geo.parseMaxSpeedKmh("55 mph") - 88.5) < .1 && Double.isNaN(Geo.parseMaxSpeedKmh("signals")));
        double[] p = Geo.project(new double[][]{{40,-75},{40.01,-75}}, 40.005, -75.0001);
        check("project", p[0] < 12 && Math.abs(p[1]-556) < 8);
        double[][] corner = {{40,-75},{40.0005,-75},{40.0005,-74.999}}; // 55 m north, then right (east)
        List<float[]> ahead = ArPath.build(corner, 0, ArPath.viewHeading(corner, 0, Double.NaN), 120, 5);
        float[] near = ahead.get(4), far = ahead.get(ahead.size()-1);
        check("ar straight", Math.abs(near[0]) < .5 && Math.abs(near[1]-20) < .5);
        check("ar right turn " + far[0], far[0] > 30 && Math.abs(far[1]-55.6) < 1);
        double[][] leftward = {{40,-75},{40.0005,-75},{40.0005,-75.001}};
        List<float[]> lf = ArPath.build(leftward, 0, 0, 120, 5);
        check("ar left turn", lf.get(lf.size()-1)[0] < -30);
        check("ar snap", Math.abs(ArPath.viewHeading(corner, 0, 4) - ArPath.bearingAt(corner, 0)) < .01 && ArPath.viewHeading(corner, 0, 40) == 40);
        System.out.println(fails + " failures"); System.exit(fails == 0 ? 0 : 1);
    }
}
