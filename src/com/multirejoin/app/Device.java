package com.multirejoin.app;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Screen setup, window layout and app launching, all through root shell commands. */
public final class Device {
    private Device() {}

    /** Set by the app's screens/services so we can ask Android for the real display size. */
    public static volatile android.content.Context ctx;

    /** The screen as it is right now (width x height after rotation), as Android draws it. */
    public static int[] currentSize() {
        try {
            if (ctx != null) {
                android.hardware.display.DisplayManager dm =
                        (android.hardware.display.DisplayManager) ctx.getSystemService(android.content.Context.DISPLAY_SERVICE);
                android.view.Display d = dm.getDisplay(android.view.Display.DEFAULT_DISPLAY);
                android.graphics.Point pt = new android.graphics.Point();
                d.getRealSize(pt);
                if (pt.x > 0 && pt.y > 0) return new int[]{pt.x, pt.y};
            }
        } catch (Exception ignored) {}
        // fallback: "cur=1280x720" from the window manager
        Matcher m = Pattern.compile("cur=(\\d+)x(\\d+)").matcher(Root.out("dumpsys window displays 2>/dev/null | grep -m1 cur="));
        if (m.find()) return new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))};
        return physicalSize();
    }

    public static int[] physicalSize() {
        String s = Root.out("wm size");
        Matcher m = Pattern.compile("(\\d+)x(\\d+)").matcher(s);
        int w = 720, h = 1280;
        if (m.find()) { w = Integer.parseInt(m.group(1)); h = Integer.parseInt(m.group(2)); }
        return new int[]{w, h};
    }

    public static int physicalDensity() {
        String s = Root.out("wm density");
        Matcher m = Pattern.compile("Physical density: (\\d+)").matcher(s);
        if (m.find()) return Integer.parseInt(m.group(1));
        m = Pattern.compile("(\\d+)").matcher(s);
        return m.find() ? Integer.parseInt(m.group(1)) : 320;
    }

    public static String setting(String ns, String key) { return Root.out("settings get " + ns + " " + key); }

    public static boolean freeformOn() {
        return "1".equals(setting("global", "enable_freeform_support"));
    }

    /** Turns on freeform windows. Returns true if it was off before (a reboot is usually needed). */
    public static boolean enableFreeform() {
        boolean was = freeformOn();
        Root.run("settings put global development_settings_enabled 1; "
                + "settings put global enable_freeform_support 1; "
                + "settings put global force_resizable_activities 1; "
                + "settings put global enable_non_resizable_multi_window 1");
        return !was;
    }

    /** Undo the rotation lock older versions set (it fought Redfinger's own Rotate button). */
    public static void unforceRotation() {
        Root.run("settings put system accelerometer_rotation 1; wm user-rotation free >/dev/null 2>&1; true");
    }

    public static void forceLandscape() {
        Root.run("settings put system accelerometer_rotation 0; settings put system user_rotation 1; "
                + "wm user-rotation lock 1 >/dev/null 2>&1; true");
    }

    public static void keepAwake() { Root.run("svc power stayon true"); }

    public static void resetScreen() {
        Root.run("wm density reset; settings put system accelerometer_rotation 1; "
                + "wm user-rotation free >/dev/null 2>&1; svc power stayon false; true");
    }

    /** Grid layout: picks columns/rows so each window is as big as possible at about 16:9. */
    public static final class Layout {
        public int cols, rows, cellW, cellH, top, screenW, screenH, density;
        public List<int[]> rects = new ArrayList<>();
    }

    public static Layout layout(int n, boolean landscape, int densityMode) {
        int[] sz = physicalSize();
        int W = landscape ? Math.max(sz[0], sz[1]) : Math.min(sz[0], sz[1]);
        int H = landscape ? Math.min(sz[0], sz[1]) : Math.max(sz[0], sz[1]);
        int phys = physicalDensity();
        Layout L = new Layout();
        L.screenW = W; L.screenH = H;
        n = Math.max(1, n);
        double bestScore = -1;
        for (int cols = 1; cols <= n; cols++) {
            int rows = (n + cols - 1) / cols;
            double cw = (double) W / cols, ch = (double) H / rows;
            double w = Math.min(cw, ch * 16.0 / 9.0);
            double score = w * (w * 9.0 / 16.0);
            if (score > bestScore) { bestScore = score; L.cols = cols; L.rows = rows; }
        }
        int density = phys;
        if (densityMode > 0) density = densityMode;
        else if (densityMode == 0 && n > 1) {
            int cellMin = Math.min(W / L.cols, H / L.rows);
            // keep each window at least ~260dp on its short side so apps accept the size
            density = Math.min(phys, Math.max(80, cellMin * 160 / 260));
        }
        L.density = density;
        L.top = (int) Math.ceil(24 * density / 160.0);
        L.cellW = W / L.cols;
        L.cellH = (H - L.top) / L.rows;
        for (int i = 0; i < n; i++) {
            int l = (i % L.cols) * L.cellW;
            int t = L.top + (i / L.cols) * L.cellH;
            L.rects.add(new int[]{l, t, l + L.cellW, t + L.cellH});
        }
        return L;
    }

    public static void applyDensity(int densityMode, int density) {
        if (densityMode < 0) return;
        if (density == physicalDensity()) Root.run("wm density reset");
        else Root.run("wm density " + density);
    }

    public static void forceStop(String pkg) { Root.run("am force-stop " + pkg); }

    private static boolean failed(String out) {
        String o = out.toLowerCase();
        return o.contains("error") || o.contains("exception") || o.contains("unable") || o.contains("does not exist")
                || o.contains("not found") || o.contains("permission denial") || o.contains("su failed");
    }

    /**
     * Opens a Roblox app on a game link. Tries, in order: window mode, normal, and the exact game activity.
     * Returns null when it started, otherwise what Android answered.
     */
    public static String launch(android.content.Context c, String pkg, String uri, boolean freeform) {
        String base = "-a android.intent.action.VIEW -d " + Root.q(uri);
        String out = "";
        if (freeform) {
            out = Root.out("am start --windowingMode 5 " + base + " -p " + pkg + " 2>&1");
            if (!failed(out)) return null;
            Log.add(pkg + ": window-mode start failed (" + firstLine(out) + "), trying normal start");
        }
        out = Root.out("am start " + base + " -p " + pkg + " 2>&1");
        if (!failed(out)) return null;
        // last try: the exact activity that handles roblox:// links
        String comp = linkActivity(c, pkg, uri);
        if (comp != null) {
            out = Root.out("am start " + (freeform ? "--windowingMode 5 " : "") + base + " -n " + Root.q(comp) + " 2>&1");
            if (!failed(out)) return null;
            out = Root.out("am start " + base + " -n " + Root.q(comp) + " 2>&1");
            if (!failed(out)) return null;
        }
        return firstLine(out);
    }

    /** Kept for old callers. */
    public static String launch(String pkg, String uri, boolean freeform) {
        return Root.out("am start " + (freeform ? "--windowingMode 5 " : "") + "-a android.intent.action.VIEW -d " + Root.q(uri) + " -p " + pkg);
    }

    private static String linkActivity(android.content.Context c, String pkg, String uri) {
        try {
            android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(uri));
            i.setPackage(pkg);
            List<android.content.pm.ResolveInfo> r = c.getPackageManager().queryIntentActivities(i, 0);
            if (r == null || r.isEmpty()) return null;
            return pkg + "/" + r.get(0).activityInfo.name;
        } catch (Exception e) {
            return null;
        }
    }

    private static String firstLine(String s) {
        for (String l : s.split("\n")) {
            String t = l.trim();
            if (failed(t)) return t.length() > 160 ? t.substring(0, 160) : t;
        }
        s = s.trim();
        return s.length() > 160 ? s.substring(0, 160) : s;
    }

    /** Task id of a package's window, or -1. */
    public static int taskId(String pkg) {
        String s = Root.out("am stack list 2>/dev/null");
        Matcher m = Pattern.compile("taskId=(\\d+): " + Pattern.quote(pkg) + "/").matcher(s);
        if (m.find()) return Integer.parseInt(m.group(1));
        s = Root.out("dumpsys activity recents 2>/dev/null");
        m = Pattern.compile("Task(?:Record)?\\{[0-9a-f]+ #(\\d+)[^\\n]*?A=(?:\\d+:)?" + Pattern.quote(pkg) + "[ }]").matcher(s);
        if (m.find()) return Integer.parseInt(m.group(1));
        return -1;
    }

    public static boolean place(String pkg, int[] r) {
        int id = taskId(pkg);
        if (id < 0) return false;
        Root.run(resizeCmd(id, r));
        return true;
    }

    /** Keeps a window rectangle fully inside the current screen. */
    public static int[] clamp(int[] r) {
        int[] sz = currentSize();
        int w = Math.min(r[2] - r[0], sz[0]), h = Math.min(r[3] - r[1], sz[1]);
        int l = Math.max(0, Math.min(r[0], sz[0] - w)), t = Math.max(0, Math.min(r[1], sz[1] - h));
        return new int[]{l, t, l + w, t + h};
    }

    /** One line for the log: where Android really put this app's window. */
    public static String windowDiag(String pkg) {
        int id = taskId(pkg);
        if (id < 0) return pkg + ": no window found";
        String mode = "?";
        for (Win w : windows()) if (w.task == id) mode = w.mode;
        String b = Root.out("dumpsys activity activities 2>/dev/null | grep -A25 'Task.*#" + id + " ' | grep -m2 -iE 'mBounds=|bounds=' | tr -s ' '");
        int[] sz = currentSize();
        return pkg + ": window #" + id + " mode=" + mode + " " + b.replace("\n", " ").trim() + "  (screen " + sz[0] + "x" + sz[1] + ")";
    }

    public static String pid(String pkg) {
        String s = Root.out("pidof " + pkg);
        if (s.isEmpty()) return null;
        return s.split("\\s+")[0];
    }

    /** CPU ticks used by a process (to spot frozen apps), or -1. */
    public static long cpuTicks(String pid) {
        String s = Root.out("cat /proc/" + pid + "/stat 2>/dev/null");
        int p = s.lastIndexOf(')');
        if (p < 0) return -1;
        String[] f = s.substring(p + 2).split(" ");
        try { return Long.parseLong(f[11]) + Long.parseLong(f[12]); } catch (Exception e) { return -1; }
    }

    /** The app's launcher activity, e.g. com.roblox.cliena/com.roblox.client.ActivitySplash */
    public static String launcherComponent(android.content.Context c, String pkg) {
        try {
            android.content.Intent i = c.getPackageManager().getLaunchIntentForPackage(pkg);
            if (i != null && i.getComponent() != null) return i.getComponent().flattenToShortString();
        } catch (Exception ignored) {}
        return null;
    }

    /** Window slot for a Roblox app: its place in the grid of all ticked Roblox apps. */
    public static int[] slotFor(android.content.Context c, String pkg) {
        return new Slots(c).get(pkg);
    }

    /** Grid slots for all Roblox apps, computed once (same math as the Start button uses). */
    public static final class Slots {
        private final List<String> all, on;
        private final Layout onL, allL;
        public Slots(android.content.Context c) {
            Store st = new Store(c);
            all = Roblox.installedApps(c);
            on = new ArrayList<>();
            for (String p : all) if (st.enabled(p)) on.add(p);
            onL = layout(st.gridSlots(on.size()), st.landscape(), st.densityMode());
            allL = layout(st.gridSlots(all.size()), st.landscape(), st.densityMode());
        }
        public int[] get(String pkg) {
            boolean inOn = on.contains(pkg);
            List<String> list = inOn ? on : all;
            Layout L = inOn ? onL : allL;
            int idx = Math.max(0, list.indexOf(pkg));
            return L.rects.get(Math.min(idx, L.rects.size() - 1));
        }
    }

    public static String resizeCmd(int task, int[] r) {
        return "am task resize " + task + " " + r[0] + " " + r[1] + " " + r[2] + " " + r[3] + " >/dev/null 2>&1; ";
    }

    /** Opens an app in a freeform window at its slot (no game link, just the app). */
    public static String openInWindow(android.content.Context c, String pkg) {
        String comp = launcherComponent(c, pkg);
        if (comp == null) return "Can't find how to open " + pkg;
        if (!freeformOn()) {
            enableFreeform();
            return "Freeform windows were just turned on. Reboot the device once, then try again.";
        }
        Root.run("am start --windowingMode 5 -n " + Root.q(comp));
        try { Thread.sleep(2500); } catch (InterruptedException ignored) {}
        place(pkg, slotFor(c, pkg));
        return null;
    }

    /** A window of an app: its task id and windowing mode ("fullscreen", "freeform", ...). */
    public static final class Win { public int task; public String pkg; public String mode; }

    public static List<Win> windows() {
        List<Win> out = new ArrayList<>();
        String s = Root.out("dumpsys activity activities 2>/dev/null");
        Pattern stackP = Pattern.compile("Stack #\\d+:.*?mode=(\\w+)");
        Pattern taskP = Pattern.compile("Task(?:Record)?\\{[0-9a-f]+ #(\\d+)[^\\n]*?A=(?:\\d+:)?([A-Za-z0-9_.]+)");
        Pattern modeP = Pattern.compile(" mode=(\\w+)");
        String stackMode = "";
        List<Integer> seen = new ArrayList<>();
        for (String line : s.split("\n")) {
            Matcher m = stackP.matcher(line);
            if (m.find()) { stackMode = m.group(1); continue; }
            m = taskP.matcher(line);
            if (!m.find()) continue;
            int id = Integer.parseInt(m.group(1));
            if (seen.contains(id)) continue;
            seen.add(id);
            Win w = new Win();
            w.task = id;
            w.pkg = m.group(2);
            Matcher mm = modeP.matcher(line);
            w.mode = mm.find() ? mm.group(1) : stackMode;
            out.add(w);
        }
        return out;
    }

    /** Sets Roblox's saved graphics quality to the lowest (manual, level 1) for one app. App must be closed. */
    public static boolean lowGraphics(String pkg) {
        String f = Root.out("find /data/data/" + pkg + " -name 'GlobalBasicSettings_*.xml' 2>/dev/null | head -1");
        if (!f.startsWith("/")) return false;
        String xml = Root.run("cat " + Root.q(f)).out;
        if (!xml.contains("<roblox")) return false;
        String nx = xml.replaceAll("<token name=\"SavedQualityLevel\">\\d+</token>", "<token name=\"SavedQualityLevel\">1</token>")
                .replaceAll("<int name=\"GraphicsQualityLevel\">\\d+</int>", "<int name=\"GraphicsQualityLevel\">1</int>");
        if (nx.equals(xml)) return true;
        try { return Root.writeAsRoot(f, nx.getBytes("UTF-8")); } catch (Exception e) { return false; }
    }

    /**
     * Tells a floating clone where its window goes: "left top right bottom" in its own files folder
     * (the clone reads it when it opens). Written as root and given back to the app's owner.
     */
    public static void writeFloatSpot(String pkg, int[] r) {
        String d = "/data/data/" + pkg + "/files";
        String f = d + "/worst_float.txt";
        Root.run("mkdir -p " + d + "; echo '" + r[0] + " " + r[1] + " " + r[2] + " " + r[3] + "' > " + f
                + "; o=$(stat -c %u:%g /data/data/" + pkg + "); chown $o " + d + " " + f + "; chmod 600 " + f
                + "; restorecon " + d + " " + f + " >/dev/null 2>&1; true");
    }
}
