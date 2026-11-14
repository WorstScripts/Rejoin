package com.multirejoin.app;

import android.app.ActivityManager;
import android.content.Context;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.os.Build;
import android.os.StatFs;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads device specs and estimates how many Roblox instances it can run. */
public final class DeviceInfo {
    // assumptions (MB). Roblox + executor at low graphics typically uses 700-1100 MB.
    public static final int DEFAULT_PER_INSTANCE_MB = 1536;
    /** PSS misses graphics memory and swapped pages; real use is usually ~1.6x higher. */
    public static final double PSS_FACTOR = 1.6;
    public static final int SYSTEM_RESERVE_MB = 1200;
    public static final int CLONE_SIZE_MB = 350;

    public String model, android, cpu, gpu, screen;
    public int cores, sdk;
    public double maxGhz;
    public long ramTotalMb, ramFreeMb, storageFreeMb, storageTotalMb;
    public boolean root, freeform;
    public int robloxApps, robloxRunning;
    public long measuredPerInstanceMb; // 0 = nothing running to measure
    public long perInstanceMb;         // value actually used for the estimate
    public String perSource = "";
    public int cpuLoad = -1;           // % of the whole CPU in use right now, -1 = unknown
    public List<String> perApp = new ArrayList<>();

    // results
    public int byRam, byCpu, byStorage, recommended, maximum;
    public String limit;

    public static DeviceInfo collect(Context c) {
        DeviceInfo d = new DeviceInfo();
        d.model = cap(Build.MANUFACTURER) + " " + Build.MODEL;
        d.sdk = Build.VERSION.SDK_INT;
        d.android = "Android " + Build.VERSION.RELEASE + " (SDK " + d.sdk + ")";

        // CPU
        d.cores = Runtime.getRuntime().availableProcessors();
        long maxKhz = 0;
        for (int i = 0; i < d.cores; i++) {
            String f = readLine("/sys/devices/system/cpu/cpu" + i + "/cpufreq/cpuinfo_max_freq");
            try { maxKhz = Math.max(maxKhz, Long.parseLong(f.trim())); } catch (Exception ignored) {}
        }
        d.maxGhz = maxKhz / 1_000_000.0;
        String soc = prop("ro.soc.model");
        if (soc.isEmpty()) soc = cpuinfoHardware();
        if (soc.isEmpty()) soc = prop("ro.board.platform");
        if (soc.isEmpty()) soc = Build.HARDWARE;
        d.cpu = soc + "  ·  " + d.cores + " cores" + (d.maxGhz > 0 ? String.format(" up to %.2f GHz", d.maxGhz) : "");
        d.gpu = gpuName();

        // RAM
        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        d.ramTotalMb = mi.totalMem / (1024 * 1024);
        d.ramFreeMb = mi.availMem / (1024 * 1024);

        // storage (/data is where apps and clones live)
        try {
            StatFs fs = new StatFs("/data");
            d.storageFreeMb = fs.getAvailableBytes() / (1024 * 1024);
            d.storageTotalMb = fs.getTotalBytes() / (1024 * 1024);
        } catch (Exception ignored) {}

        android.util.DisplayMetrics dm = c.getResources().getDisplayMetrics();
        d.screen = Math.max(dm.widthPixels, dm.heightPixels) + " x " + Math.min(dm.widthPixels, dm.heightPixels)
                + "  ·  density " + dm.densityDpi;

        // root / windows / roblox
        d.root = Root.available();
        d.freeform = d.root && Device.freeformOn();
        List<String> apps = Roblox.installedApps(c);
        d.robloxApps = apps.size();
        long sum = 0;
        if (d.root) {
            for (String p : apps) {
                String pid = Device.pid(p);
                if (pid == null) continue;
                long mb = pssMb(pid);
                if (mb <= 0) continue;
                d.robloxRunning++;
                sum += mb;
                d.perApp.add(p + "  ·  " + mb + " MB");
            }
        }
        if (d.robloxRunning > 0) d.measuredPerInstanceMb = sum / d.robloxRunning;
        if (d.root) d.cpuLoad = cpuLoad();
        Store st = new Store(c);
        int override = st.i("perInstanceMb", 0);
        int runMeasured = st.i("measuredPerMb", 0);
        if (override > 0) {
            d.perInstanceMb = override;
            d.perSource = "your setting (" + gb(override) + " per instance)";
        } else if (runMeasured > 0) {
            d.perInstanceMb = runMeasured;
            d.perSource = "measured while " + st.i("measuredWith", 1) + " instance(s) were in game: about " + gb(runMeasured) + " each";
        } else if (d.robloxRunning > 0) {
            // Real use while running = RAM in use minus what the system needs, shared by the running instances.
            // (Android's per-app counter is unreliable on cloud phones: it counts shared graphics memory.)
            long used = d.ramTotalMb - d.ramFreeMb - SYSTEM_RESERVE_MB;
            d.perInstanceMb = Math.max(500, Math.min(3000, used / d.robloxRunning));
            d.perSource = "about " + gb(d.perInstanceMb) + " each, from the RAM in use with " + d.robloxRunning + " running";
        } else {
            d.perInstanceMb = DEFAULT_PER_INSTANCE_MB;
            d.perSource = "a typical " + gb(DEFAULT_PER_INSTANCE_MB) + " per instance (start your instances once — the app measures the real number)";
        }
        d.estimate();
        return d;
    }

    private void estimate() {
        long per = Math.max(300, perInstanceMb);
        if (robloxRunning > 0) {
            // what's running now + how many more fit in the RAM that is still free (keep 600 MB spare)
            byRam = (int) (robloxRunning + Math.max(0, (ramFreeMb - 600) / per));
        } else {
            byRam = (int) Math.max(0, (ramTotalMb - SYSTEM_RESERVE_MB) / per);
        }
        // cloud phones share their CPU cores and often draw graphics on the CPU: be careful
        double perCore = maxGhz >= 2.8 ? 0.6 : maxGhz >= 2.0 ? 0.45 : 0.3;
        byCpu = (int) Math.max(1, Math.floor(cores * perCore));
        // when instances are running, trust the real CPU load more than the guess
        if (robloxRunning > 0 && cpuLoad >= 0) {
            if (cpuLoad >= 90) byCpu = Math.max(1, robloxRunning - 1);
            else if (cpuLoad >= 75) byCpu = robloxRunning;
            else byCpu = Math.max(byCpu, robloxRunning + (cpuLoad < 50 ? 1 : 0));
        }
        byStorage = (int) Math.max(0, storageFreeMb / CLONE_SIZE_MB);
        recommended = Math.max(1, Math.min(byRam, byCpu));
        maximum = Math.max(recommended, Math.min(byRam + 1, byCpu + 1));
        limit = byRam <= byCpu ? "memory (RAM)" : "processor (CPU)";
    }

    /** Whole-CPU usage in % over one second, from /proc/stat. */
    private static int cpuLoad() {
        long[] a = cpuTimes(), b;
        try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
        b = cpuTimes();
        if (a == null || b == null) return -1;
        long idle = b[1] - a[1], total = b[0] - a[0];
        if (total <= 0) return -1;
        return (int) Math.round(100.0 * (total - idle) / total);
    }

    private static long[] cpuTimes() {
        String l = Root.out("head -1 /proc/stat");
        if (!l.startsWith("cpu")) return null;
        String[] f = l.trim().split("\\s+");
        long total = 0, idle = 0;
        for (int i = 1; i < f.length; i++) {
            try {
                long v = Long.parseLong(f[i]);
                total += v;
                if (i == 4 || i == 5) idle += v;   // idle + iowait
            } catch (Exception ignored) {}
        }
        return new long[]{total, idle};
    }

    /** Smallest "windows per screen" choice that fits n windows. */
    public static int gridFor(int n) {
        int[] opts = {1, 2, 4, 6, 9, 12, 16};
        for (int o : opts) if (o >= n) return o;
        return 16;
    }

    // ------------------------------------------------------------------ helpers

    private static long pssMb(String pid) {
        String s = Root.out("dumpsys meminfo " + pid + " 2>/dev/null | grep -E 'TOTAL( PSS)?[: ]' | head -1");
        Matcher m = Pattern.compile("TOTAL(?: PSS)?:?\\s+(\\d+)").matcher(s);
        if (m.find()) return Long.parseLong(m.group(1)) / 1024;
        return 0;
    }

    private static String gpuName() {
        EGLDisplay dpy = null;
        EGLContext ctx = null;
        EGLSurface surf = null;
        try {
            dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] ver = new int[2];
            if (!EGL14.eglInitialize(dpy, ver, 0, ver, 1)) return "unknown";
            int[] attr = {EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE};
            EGLConfig[] cfg = new EGLConfig[1];
            int[] n = new int[1];
            EGL14.eglChooseConfig(dpy, attr, 0, cfg, 0, 1, n, 0);
            if (n[0] == 0) return "unknown";
            ctx = EGL14.eglCreateContext(dpy, cfg[0], EGL14.EGL_NO_CONTEXT, new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
            surf = EGL14.eglCreatePbufferSurface(dpy, cfg[0], new int[]{EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE}, 0);
            EGL14.eglMakeCurrent(dpy, surf, surf, ctx);
            String r = GLES20.glGetString(GLES20.GL_RENDERER);
            String v = GLES20.glGetString(GLES20.GL_VENDOR);
            if (r == null) return "unknown";
            return (v != null && !r.toLowerCase().contains(v.toLowerCase()) ? v + " " : "") + r;
        } catch (Throwable t) {
            return "unknown";
        } finally {
            try {
                if (dpy != null) {
                    EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                    if (surf != null) EGL14.eglDestroySurface(dpy, surf);
                    if (ctx != null) EGL14.eglDestroyContext(dpy, ctx);
                    EGL14.eglTerminate(dpy);
                }
            } catch (Throwable ignored) {}
        }
    }

    private static String readLine(String path) {
        try {
            BufferedReader r = new BufferedReader(new FileReader(new File(path)));
            String l = r.readLine();
            r.close();
            return l == null ? "" : l;
        } catch (Exception e) {
            return "";
        }
    }

    private static String cpuinfoHardware() {
        try {
            BufferedReader r = new BufferedReader(new FileReader("/proc/cpuinfo"));
            String l;
            while ((l = r.readLine()) != null) {
                if (l.startsWith("Hardware")) { r.close(); return l.substring(l.indexOf(':') + 1).trim(); }
            }
            r.close();
        } catch (Exception ignored) {}
        return "";
    }

    private static String prop(String key) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"getprop", key});
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String l = r.readLine();
            r.close();
            p.waitFor();
            return l == null ? "" : l.trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static String cap(String s) {
        return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    public static String gb(long mb) {
        return mb >= 1024 ? String.format("%.1f GB", mb / 1024.0) : mb + " MB";
    }
}
