package com.multirejoin.app;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.ValueCallback;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hands-free executor updates: opens the executor's site in a hidden browser, reads the newest version,
 * taps the download button if needed, downloads the APK and updates every clone (logins are kept).
 */
public final class AutoUpdater {
    private AutoUpdater() {}

    public interface Listener {
        void status(String text);
        /** file == null when nothing was downloaded (up to date or failed). */
        void finished(File file, String message);
    }

    /** JavaScript run on the page every 2 s: returns "version|clicked". Clicks the Android download button if asked. */
    public static String pageScript(boolean click) {
        return "(function(){try{"
                + "var t=document.body?document.body.innerText:'';"
                + "var m=t.match(/Delta[-_ ]?v?([0-9]+\\.[0-9.]+)\\.apk/i)||t.match(/Roblox Version:?\\s*v?([0-9]+\\.[0-9.]+)/i)||t.match(/[-_ ]v?([0-9]+\\.[0-9]+\\.[0-9]+)\\.apk/i);"
                + "var v=m?m[1]:'';"
                + (click ? "var c=window.__wc||0;var now=Date.now();"
                + "if(c<3&&(!window.__wt||now-window.__wt>4000)){"
                + "var els=document.querySelectorAll('a,button,[role=button]');var pick=null,i,s;"
                + "for(i=0;i<els.length&&!pick;i++){s=(els[i].innerText||'').trim().toLowerCase();"
                + "if(s.indexOf('download for android')>=0||s.indexOf('download apk')>=0)pick=els[i];}"
                + "for(i=0;i<els.length&&!pick;i++){s=(els[i].innerText||'').trim().toLowerCase();"
                + "if(s==='download free'||s==='download'||s==='download now')pick=els[i];}"
                + "if(pick){window.__wc=c+1;window.__wt=now;pick.click();return v+'|clicked';}}" : "")
                + "return v+'|';}catch(e){return '|';}})()";
    }

    // ------------------------------------------------------------------ hidden browser

    private static volatile boolean busy = false;

    public static boolean isBusy() { return busy; }

    /**
     * Opens the site hidden, and downloads the APK only if the page shows a version newer than
     * {@code installedVersion} (or if installedVersion is null = always download).
     */
    @SuppressLint("SetJavaScriptEnabled")
    public static void fetch(final Context ctx, final String name, final String link, final String installedVersion, final Listener l) {
        if (busy) { l.finished(null, "Another update is already running"); return; }
        busy = true;
        final Handler h = new Handler(Looper.getMainLooper());
        h.post(new Runnable() { public void run() {
            final WebView wv;
            try {
                wv = new WebView(ctx.getApplicationContext());
            } catch (Throwable t) {
                busy = false;
                l.finished(null, "Browser not available: " + t.getMessage());
                return;
            }
            // hidden pages pause their countdown timers, so always tap the download button ourselves
            final boolean click = true;
            final boolean[] done = {false};
            final long start = System.currentTimeMillis();
            WebSettings ws = wv.getSettings();
            ws.setJavaScriptEnabled(true);
            ws.setDomStorageEnabled(true);
            ws.setSupportMultipleWindows(false);
            ws.setJavaScriptCanOpenWindowsAutomatically(false);
            wv.setWebViewClient(new WebViewClient() {
                @Override public boolean shouldOverrideUrlLoading(WebView v, String url) {
                    return !(url.startsWith("http://") || url.startsWith("https://"));
                }
            });
            final Runnable finish = new Runnable() { public void run() {
                try { wv.stopLoading(); wv.destroy(); } catch (Throwable ignored) {}
            }};
            wv.setDownloadListener(new DownloadListener() {
                @Override public void onDownloadStart(final String url, final String ua, final String cd, final String mime, final long len) {
                    if (done[0]) return;
                    done[0] = true;
                    final String cookies = CookieManager.getInstance().getCookie(url);
                    final String ref = wv.getUrl();
                    h.post(finish);
                    l.status("Downloading " + name + " …");
                    new Thread(new Runnable() { public void run() {
                        String fileName = guessApkName(url, cd, mime);
                        File f = new File(ctx.getCacheDir(), "update.apk");
                        String err = download(url, ua, cookies, ref, len, f, l, fileName);
                        busy = false;
                        if (err != null) { f.delete(); l.finished(null, "Download failed: " + err); return; }
                        String dest = "/sdcard/Download/" + fileName;
                        Root.run("mkdir -p /sdcard/Download; cp -f " + Root.q(f.getAbsolutePath()) + " " + Root.q(dest));
                        l.finished(f, dest);
                    }}).start();
                }
            });
            // poll the page: read the version, click the button, give up after 90 s
            final Runnable poll = new Runnable() { public void run() {
                if (done[0]) return;
                if (System.currentTimeMillis() - start > 90000) {
                    done[0] = true;
                    finish.run();
                    busy = false;
                    l.finished(null, "Timed out — the site didn't start a download");
                    return;
                }
                final Runnable self = this;
                wv.evaluateJavascript(pageScript(false), new ValueCallback<String>() {
                    public void onReceiveValue(String r) {
                        if (done[0]) return;
                        String v = r == null ? "" : r.replace("\"", "");
                        v = v.contains("|") ? v.substring(0, v.indexOf('|')) : v;
                        if (installedVersion != null && !v.isEmpty()) {
                            if (Executors.compare(v, installedVersion) <= 0) {
                                done[0] = true;
                                finish.run();
                                busy = false;
                                l.finished(null, "up to date (v" + installedVersion + ", site has v" + v + ")");
                                return;
                            }
                        }
                        // newer (or unknown) version: let it download — click the button if this site needs it
                        if (click && (installedVersion == null || !v.isEmpty()))
                            wv.evaluateJavascript(pageScript(true), null);
                        h.postDelayed(self, 2000);
                    }
                });
            }};
            wv.loadUrl(link);
            h.postDelayed(poll, 3000);
        }});
    }

    private static String download(String url, String ua, String cookies, String ref, long len, File out,
                                   Listener l, String fileName) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(true);
            c.setConnectTimeout(20000);
            c.setReadTimeout(30000);
            if (ua != null) c.setRequestProperty("User-Agent", ua);
            if (cookies != null) c.setRequestProperty("Cookie", cookies);
            if (ref != null) c.setRequestProperty("Referer", ref);
            long total = c.getContentLength() > 0 ? c.getContentLength() : len;
            InputStream in = c.getInputStream();
            FileOutputStream o = new FileOutputStream(out);
            byte[] b = new byte[1 << 16];
            long got = 0, last = 0;
            int n;
            while ((n = in.read(b)) > 0) {
                o.write(b, 0, n);
                got += n;
                long now = System.currentTimeMillis();
                if (now - last > 1000) {
                    last = now;
                    l.status("Downloading " + fileName + "  " + DeviceInfo.gb(got >> 20) + (total > 0 ? " / " + DeviceInfo.gb(total >> 20) : ""));
                }
            }
            o.close();
            in.close();
            if (got < (1 << 20)) return "the site sent a web page instead of the APK";
            return null;
        } catch (Exception e) {
            return e.getMessage();
        }
    }

    public static String guessApkName(String url, String cd, String mime) {
        String n = android.webkit.URLUtil.guessFileName(url, cd, mime);
        n = n.replaceAll("[^A-Za-z0-9._()\\- ]", "_");
        String low = n.toLowerCase();
        if (!(low.endsWith(".apk") || low.endsWith(".apks") || low.endsWith(".xapk")))
            n = n.replaceAll("\\.(bin|zip)$", "") + ".apk";
        return n;
    }

    // ------------------------------------------------------------------ applying an update (no screens)

    /** The executor that clones with no record belong to: the original app's executor, else the user's choice, else Delta. */
    public static String mainExec(Context c) {
        Store st = new Store(c);
        String m = st.s("mainExec", "");
        if (!m.isEmpty()) return m;
        String orig = st.s("exec_com.roblox.client", "");
        return orig.isEmpty() ? "Delta" : orig;
    }

    /** Executor of one installed app: its record, else detected from its name, else the main executor. */
    public static String execOf(Context c, String pkg) {
        Store st = new Store(c);
        String e = st.s("exec_" + pkg, "");
        if (!e.isEmpty()) return e;
        try {
            PackageManager pm = c.getPackageManager();
            e = Executors.detect(String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0))));
        } catch (Exception ex) { e = "Roblox"; }
        return e.equals("Roblox") ? mainExec(c) : e;
    }

    /** Which installed Roblox apps (original + clones) use this executor. */
    public static List<String> appsOf(Context c, String exec) {
        List<String> out = new ArrayList<>();
        for (String p : Roblox.installedApps(c)) if (execOf(c, p).equals(exec)) out.add(p);
        return out;
    }

    public static String version(Context c, String pkg) {
        try { return c.getPackageManager().getPackageInfo(pkg, 0).versionName; } catch (Exception e) { return ""; }
    }

    /** Updates every listed app from the downloaded APK. Returns how many were updated. */
    public static int apply(Context c, File apk, String downloadedPath, String exec, List<String> targets) {
        Store st = new Store(c);
        PackageManager pm = c.getPackageManager();
        PackageInfo pi = pm.getPackageArchiveInfo(apk.getAbsolutePath(), 0);
        if (pi == null) { Log.add("Auto-update: the downloaded file isn't a valid APK"); return 0; }
        Cloner.Source s = new Cloner.Source();
        s.filePath = downloadedPath;
        s.pkg = pi.packageName;
        s.version = pi.versionName;
        Cloner cl = new Cloner(c);
        int ok = 0;
        try {
            List<File> apks = cl.collect(s);
            for (String t : targets) {
                if (!Executors.sameFamily(t, s.pkg)) continue;
                // every clone is rebuilt from the new APK and re-signed (same version = refresh), never downgraded
                if (Executors.compare(s.version, version(c, t)) < 0) continue;
                String label = null;
                try {
                    String cur = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(t, 0)));
                    if (!cur.equals("Roblox")) label = cur;
                } catch (Exception ignored) {}
                try {
                    Device.forceStop(t);
                    if (t.equals(s.pkg)) cl.installOriginal(apks); else cl.makeClone(apks, s.pkg, t, label);
                    st.put("exec_" + t, exec);
                    ok++;
                    Log.add("Auto-update: " + t + " → " + exec + " v" + s.version);
                } catch (Exception e) {
                    Log.add("Auto-update FAILED for " + t + ": " + e.getMessage());
                }
            }
            for (File f : apks) f.delete();
        } catch (Exception e) {
            Log.add("Auto-update error: " + e.getMessage());
        }
        return ok;
    }

    /** Background check for every executor in use. Called by KeeperService on its schedule. */
    public static void checkAll(final Context c, final boolean manual) {
        final Store st = new Store(c);
        final List<String> execs = new ArrayList<>();
        for (String e : Executors.names()) if (!appsOf(c, e).isEmpty() && !st.s("dl_" + e, Executors.defaultLink(e)).isEmpty()) execs.add(e);
        if (execs.isEmpty()) { if (manual) Log.add("Auto-update: no executor with a download page is in use."); return; }
        next(c, execs, 0);
    }

    private static void next(final Context c, final List<String> execs, final int i) {
        if (i >= execs.size()) return;
        final Store st = new Store(c);
        final String exec = execs.get(i);
        final List<String> apps = appsOf(c, exec);
        String newest = "";
        String oldest = null;
        for (String p : apps) {
            String v = version(c, p);
            if (Executors.compare(v, newest) > 0) newest = v;
            if (oldest == null || Executors.compare(v, oldest) < 0) oldest = v;
        }
        final String have = oldest == null ? "" : oldest;
        Log.add("Checking " + exec + " for updates (you have v" + have + ") …");
        fetch(c, exec, st.s("dl_" + exec, Executors.defaultLink(exec)), have, new Listener() {
            public void status(String t) {}
            public void finished(final File f, final String msg) {
                if (f == null) {
                    Log.add(exec + ": " + msg);
                    next(c, execs, i + 1);
                    return;
                }
                new Thread(new Runnable() { public void run() {
                    // hands-free mode only acts when the file is really newer than what's installed
                    PackageInfo pi = c.getPackageManager().getPackageArchiveInfo(f.getAbsolutePath(), 0);
                    String fv = pi == null ? "" : pi.versionName;
                    if (pi == null || Executors.compare(fv, have) <= 0) {
                        Log.add(exec + ": " + (pi == null ? "downloaded file isn't an APK" : "already up to date (v" + have + ")"));
                        f.delete();
                        new Handler(Looper.getMainLooper()).post(new Runnable() { public void run() { next(c, execs, i + 1); } });
                        return;
                    }
                    boolean wasRunning = RunnerService.isRunning();
                    if (wasRunning) {
                        c.startService(new Intent(c, RunnerService.class).setAction(RunnerService.ACTION_STOP));
                        try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
                    }
                    Notify.send(c, "⬆ New " + exec + " found — updating " + apps.size() + " app(s)…");
                    int n = apply(c, f, msg, exec, apps);
                    f.delete();
                    String v = version(c, apps.get(0));
                    Notify.send(c, "✅ Updated " + n + "/" + apps.size() + " app(s) to " + exec + " v" + v
                            + ". If the executor asks for a new key, enter it on each instance.");
                    Log.add("Auto-update done: " + n + "/" + apps.size() + " on " + exec + " v" + v);
                    if (wasRunning) {
                        Intent run = new Intent(c, RunnerService.class).setAction(RunnerService.ACTION_START);
                        try {
                            if (Build.VERSION.SDK_INT >= 26) Context.class.getMethod("startForegroundService", Intent.class).invoke(c, run);
                            else c.startService(run);
                        } catch (Exception e) { c.startService(run); }
                    }
                    new Handler(Looper.getMainLooper()).post(new Runnable() { public void run() { next(c, execs, i + 1); } });
                }}).start();
            }
        });
    }

    static final Pattern VERSION = Pattern.compile("([0-9]+\\.[0-9]+\\.[0-9]+)");

    public static String versionInName(String s) {
        Matcher m = VERSION.matcher(s == null ? "" : s);
        return m.find() ? m.group(1) : "";
    }
}
