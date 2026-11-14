package com.multirejoin.app;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Finds APKs on the device, makes clones with new package names and installs them (root). */
public final class Cloner {
    private final Context ctx;

    public Cloner(Context c) { ctx = c; }

    /** Something we can clone: an installed app, or an APK / APKS / XAPK file. */
    public static final class Source {
        public String label;        // app name, e.g. "Roblox"
        public String detail;       // package or file name
        public String version = "";
        public String pkg;
        public String installedPkg; // set when it's an installed app
        public String filePath;     // set when it's a file
        public android.graphics.drawable.Drawable icon;
        public String toString() { return label; }
    }

    private static final String[] EXECUTOR_WORDS = {"roblox", "delta", "arceus", "codex", "fluxus", "hydrogen",
            "krnl", "vega", "cryptic", "evon", "trigon", "ronix", "solara", "wave"};

    public List<Source> installedSources() {
        List<Source> out = new ArrayList<>();
        PackageManager pm = ctx.getPackageManager();
        for (String p : Roblox.installedApps(ctx)) {
            Source s = new Source();
            s.pkg = p;
            s.installedPkg = p;
            s.label = p;
            s.detail = p;
            try {
                ApplicationInfo ai = pm.getApplicationInfo(p, 0);
                s.label = String.valueOf(pm.getApplicationLabel(ai));
                s.icon = pm.getApplicationIcon(ai);
                s.version = pm.getPackageInfo(p, 0).versionName;
            } catch (Exception ignored) {}
            out.add(s);
        }
        return out;
    }

    /** Roblox APK / APKS / XAPK files in internal storage (recycle bins and hidden folders skipped). */
    public List<Source> fileSources() {
        List<Source> out = new ArrayList<>();
        PackageManager pm = ctx.getPackageManager();
        String found = Root.out("find /sdcard/ -maxdepth 5 -type f \\( -iname '*.apk' -o -iname '*.apks' -o -iname '*.xapk' \\) "
                + "-not -path '*/Android/*' -not -path '*/.*' 2>/dev/null");
        for (String f : found.split("\n")) {
            f = f.trim().replaceAll("/+", "/");
            if (!f.startsWith("/")) continue;
            String name = f.substring(f.lastIndexOf('/') + 1);
            Source s = new Source();
            s.filePath = f;
            s.detail = f.replaceFirst("^/sdcard/", "");
            s.label = name;
            boolean isRoblox = false;
            if (f.toLowerCase().endsWith(".apk")) {
                try {
                    android.content.pm.PackageInfo pi = pm.getPackageArchiveInfo(f, 0);
                    if (pi != null) {
                        pi.applicationInfo.sourceDir = f;
                        pi.applicationInfo.publicSourceDir = f;
                        s.pkg = pi.packageName;
                        s.version = pi.versionName == null ? "" : pi.versionName;
                        s.label = String.valueOf(pi.applicationInfo.loadLabel(pm));
                        s.icon = pi.applicationInfo.loadIcon(pm);
                        isRoblox = pi.packageName.toLowerCase().contains("roblox");
                    } else {
                        isRoblox = looksLikeRoblox(name);
                    }
                } catch (Throwable t) {
                    isRoblox = looksLikeRoblox(name);
                }
            } else {
                isRoblox = looksLikeRoblox(name);
            }
            if (isRoblox) out.add(s);
        }
        return out;
    }

    private static boolean looksLikeRoblox(String name) {
        String n = name.toLowerCase();
        for (String w : EXECUTOR_WORDS) if (n.contains(w)) return true;
        return false;
    }

    public List<Source> findSources() {
        List<Source> out = installedSources();
        out.addAll(fileSources());
        return out;
    }

    public void uninstall(String pkg) throws Exception {
        String r = Root.out("pm uninstall " + pkg);
        if (!r.contains("Success")) throw new Exception(r);
    }

    private File work() {
        File d = new File(ctx.getCacheDir(), "clone");
        d.mkdirs();
        return d;
    }

    /** Collect the APK files (base + splits) of a source into the work folder. Returns [base, splits...]. */
    public List<File> collect(Source s) throws Exception {
        File d = work();
        File[] old = d.listFiles();
        if (old != null) for (File f : old) f.delete();
        List<File> apks = new ArrayList<>();
        List<String> paths = new ArrayList<>();
        if (s.installedPkg != null) {
            ApplicationInfo ai = ctx.getPackageManager().getApplicationInfo(s.installedPkg, 0);
            paths.add(ai.sourceDir);
            if (ai.splitSourceDirs != null) for (String sp : ai.splitSourceDirs) paths.add(sp);
            int i = 0;
            for (String p : paths) {
                File f = new File(d, "src" + (i++) + ".apk");
                copyAsRoot(p, f);
                apks.add(f);
            }
        } else {
            String lower = s.filePath.toLowerCase();
            File f = new File(d, "input.bin");
            copyAsRoot(s.filePath, f);
            if (lower.endsWith(".apk")) {
                File a = new File(d, "src0.apk");
                f.renameTo(a);
                apks.add(a);
            } else {
                ApkZip z = new ApkZip(f);
                try {
                    int i = 0;
                    for (ApkZip.Entry e : z.entries) {
                        if (!e.name.toLowerCase().endsWith(".apk")) continue;
                        File a = new File(d, "src" + (i++) + ".apk");
                        z.extract(e, a);
                        apks.add(a);
                    }
                } finally {
                    z.close();
                }
                f.delete();
                if (apks.isEmpty()) throw new Exception("No APK inside " + s.filePath);
            }
        }
        // put the base apk first, and keep only splits of the same package
        File base = null;
        String pkg = null;
        List<File> splits = new ArrayList<>();
        for (File a : apks) {
            CloneCore.Info in = CloneCore.info(a);
            if (!in.split && base == null) { base = a; pkg = in.pkg; } else splits.add(a);
        }
        if (base == null) throw new Exception("Couldn't find the main APK");
        List<File> res = new ArrayList<>();
        res.add(base);
        for (File a : splits) if (pkg.equals(CloneCore.info(a).pkg)) res.add(a);
        s.pkg = pkg;
        return res;
    }

    private void copyAsRoot(String from, File to) throws Exception {
        if (new File(from).canRead()) {
            java.io.FileInputStream in = new java.io.FileInputStream(from);
            java.io.FileOutputStream o = new java.io.FileOutputStream(to);
            byte[] b = new byte[1 << 16];
            int n;
            while ((n = in.read(b)) > 0) o.write(b, 0, n);
            in.close();
            o.close();
            return;
        }
        if (!Root.catTo(from, to)) throw new Exception("Couldn't read " + from);
    }

    /** Package names for clones: replace the last letter (same length, like MT Manager clones). */
    public static List<String> cloneNames(String pkg, char startLetter, int count) {
        List<String> out = new ArrayList<>();
        String stem = pkg.substring(0, pkg.length() - 1);
        char orig = pkg.charAt(pkg.length() - 1);
        for (char c = startLetter; c <= 'z' && out.size() < count; c++) {
            if (c == orig) continue;
            out.add(stem + c);
        }
        return out;
    }

    public boolean isInstalled(String pkg) {
        try { ctx.getPackageManager().getApplicationInfo(pkg, 0); return true; } catch (Exception e) { return false; }
    }

    /** Make one clone from collected apks and install it. */
    public void makeClone(List<File> apks, String oldPkg, String newPkg) throws Exception { makeClone(apks, oldPkg, newPkg, null); }

    public void makeClone(List<File> apks, String oldPkg, String newPkg, String label) throws Exception {
        Store st = new Store(ctx);
        makeClone(apks, oldPkg, newPkg, label, st.b("float_" + newPkg, false), st.i("slot_" + newPkg, 0));
    }

    /** floating = add WORST's floating-window code; slot = which window spot it opens in. */
    public void makeClone(List<File> apks, String oldPkg, String newPkg, String label, boolean floating, int slot) throws Exception {
        CloneCore core = new CloneCore(ctx.getAssets().open("clone_key.pk8"), ctx.getAssets().open("clone_cert.der"));
        core.windowFriendly = false;   // Android 15 handles windows without changing the clone
        if (floating) {
            core.floatDex = CloneCore.readAll(ctx.getAssets().open("float.dex"));
            core.floatSlot = slot;
        }
        if (new Store(ctx).b("icon_" + newPkg, new Store(ctx).b("cloneIcon", true))) {
            final String badge = newPkg.substring(newPkg.length() - 1).toUpperCase();
            core.iconMaker = new CloneCore.IconMaker() {
                public byte[] make(String path, byte[] original) { return worstIcon(path, original, badge); }
            };
        }
        File d = work();
        List<File> signed = new ArrayList<>();
        try {
            int i = 0;
            for (File a : apks) {
                File tmp = new File(d, "tmp.apk");
                File out = new File(d, newPkg + "_" + (i++) + ".apk");
                Log.add("  patching + signing " + (i) + "/" + apks.size() + "...");
                core.cloneApk(a, tmp, out, oldPkg, newPkg, label);
                signed.add(out);
            }
            installKeepingLogin(signed, newPkg);
        } finally {
            for (File f : signed) f.delete();
        }
    }

    /** WORST's W icon with a small red letter badge, same size and format as the picture it replaces. */
    private byte[] worstIcon(String path, byte[] original, String badge) {
        try {
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeByteArray(original, 0, original.length, o);
            int w = o.outWidth > 0 ? o.outWidth : 192, h = o.outHeight > 0 ? o.outHeight : 192;
            android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas cv = new android.graphics.Canvas(bmp);
            android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG | android.graphics.Paint.FILTER_BITMAP_FLAG);
            // rounded square, like the WORST app icon
            android.graphics.Bitmap bg = android.graphics.BitmapFactory.decodeResource(ctx.getResources(), R.drawable.ic_bg);
            android.graphics.Bitmap fg = android.graphics.BitmapFactory.decodeResource(ctx.getResources(), R.drawable.ic_fg);
            android.graphics.RectF all = new android.graphics.RectF(0, 0, w, h);
            float r = Math.min(w, h) * 0.22f;
            int layer = cv.saveLayer(all, null);
            cv.drawRoundRect(all, r, r, p);
            p.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN));
            cv.drawBitmap(bg, null, all, p);
            p.setXfermode(null);
            cv.restoreToCount(layer);
            // the W (its picture has room around it, so draw it a bit larger than the icon)
            float g = 0.18f;
            cv.drawBitmap(fg, null, new android.graphics.RectF(-w * g, -h * g, w * (1 + g), h * (1 + g)), p);
            // letter badge, bottom right
            float br = Math.min(w, h) * 0.2f, cx = w - br * 1.05f, cy = h - br * 1.05f;
            p.setColor(0xffd0202e);
            cv.drawCircle(cx, cy, br, p);
            p.setColor(0xffffffff);
            p.setTextAlign(android.graphics.Paint.Align.CENTER);
            p.setTypeface(android.graphics.Typeface.create("sans-serif-black", android.graphics.Typeface.BOLD));
            p.setTextSize(br * 1.3f);
            android.graphics.Rect tb = new android.graphics.Rect();
            p.getTextBounds(badge, 0, badge.length(), tb);
            cv.drawText(badge, cx, cy + tb.height() / 2f, p);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            android.graphics.Bitmap.CompressFormat fmt = path.toLowerCase().endsWith(".webp")
                    ? android.graphics.Bitmap.CompressFormat.WEBP : android.graphics.Bitmap.CompressFormat.PNG;
            bmp.compress(fmt, 100, out);
            return out.toByteArray();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Install the original files unchanged. */
    public void installOriginal(List<File> apks) throws Exception {
        String pkg = null;
        try { pkg = CloneCore.info(apks.get(0)).pkg; } catch (Exception ignored) {}
        installKeepingLogin(apks, pkg);
    }

    /**
     * Installs; if an app with this name exists but was signed by another tool (MT Manager, other cloners),
     * backs up its data, uninstalls it, installs ours and puts the data back — so the login is kept.
     */
    /** First meaningful line of a long command output, max 160 chars (keeps the log readable). */
    static String short1(String s) {
        if (s == null) return "";
        for (String l : s.split("\n")) {
            String t = l.trim();
            if (t.contains("Failure") || t.contains("Error") || t.contains("Exception") || t.contains("INSTALL_"))
                return t.length() > 160 ? t.substring(0, 160) + "…" : t;
        }
        for (String l : s.split("\n")) {
            String t = l.trim();
            if (!t.isEmpty()) return t.length() > 160 ? t.substring(0, 160) + "…" : t;
        }
        return "";
    }

    private void installKeepingLogin(List<File> apks, String pkg) throws Exception {
        try {
            install(apks);
        } catch (Exception e) {
            String msg = String.valueOf(e.getMessage());
            if (pkg == null || !(msg.contains("UPDATE_INCOMPATIBLE") || msg.contains("signatures do not match"))) throw e;
            if (!new Store(ctx).b("replaceForeign", true)) throw e;
            Log.add("  " + pkg + " was signed by another tool — replacing it and keeping its login…");
            String bak = "/data/local/tmp/worstbak/" + pkg;
            String P = Root.q(pkg), B = Root.q(bak);
            // back up the login & settings only (cache folders are huge, change while copying, and Roblox rebuilds them)
            String copyDir = "for f in \"$S\"/* \"$S\"/.[!.]*; do [ -e \"$f\" ] || continue; n=$(basename \"$f\"); "
                    + "case \"$n\" in cache|code_cache|lib|no_backup) continue;; esac; cp -a \"$f\" \"$T/\" 2>/dev/null; done";
            Root.Res r = Root.run("am force-stop " + P + "; rm -rf " + B + "; mkdir -p " + B + "/ce; "
                    + "S=/data/data/" + pkg + "; T=" + B + "/ce; " + copyDir + "; "
                    + "if [ -d /data/user_de/0/" + pkg + " ]; then mkdir -p " + B + "/de; S=/data/user_de/0/" + pkg + "; T=" + B + "/de; " + copyDir + "; fi; "
                    + "[ -d " + B + "/ce ] && [ -n \"$(ls -A " + B + "/ce 2>/dev/null)\" ] && echo BACKUP_OK");
            if (!r.out.contains("BACKUP_OK")) throw new Exception("Couldn't back up " + pkg + " — nothing was changed (" + short1(r.out) + ")");
            String un = Root.out("pm uninstall " + P);
            if (!un.contains("Success")) throw new Exception("Couldn't uninstall " + pkg + ": " + short1(un) + " (backup kept in " + bak + ")");
            try {
                install(apks);
            } catch (Exception e2) {
                throw new Exception(short1(e2.getMessage()) + " — the old app was removed; its data backup is in " + bak);
            }
            String restore = "D=/data/data/" + pkg + "; E=/data/user_de/0/" + pkg + "; "
                    + "U=$(stat -c %u $D); C=$(stat -c %g $D/cache 2>/dev/null); "
                    + "am force-stop " + P + "; "
                    + "for f in " + B + "/ce/* " + B + "/ce/.[!.]*; do [ -e \"$f\" ] || continue; n=$(basename \"$f\"); "
                    + "[ \"$n\" = lib ] && continue; rm -rf \"$D/$n\"; cp -a \"$f\" \"$D/\"; done; "
                    + "chown -R $U:$U $D; [ -n \"$C\" ] && chown -R $U:$C $D/cache $D/code_cache 2>/dev/null; "
                    + "if [ -d " + B + "/de ] && [ -d $E ]; then for f in " + B + "/de/* " + B + "/de/.[!.]*; do [ -e \"$f\" ] || continue; "
                    + "n=$(basename \"$f\"); rm -rf \"$E/$n\"; cp -a \"$f\" \"$E/\"; done; chown -R $U:$U $E; fi; "
                    + "restorecon -RF $D $E >/dev/null 2>&1; echo RESTORE_OK";
            Root.Res rr = Root.run(restore);
            if (!rr.out.contains("RESTORE_OK")) throw new Exception("Installed, but couldn't restore the login (backup kept in " + bak + ")");
            Root.run("rm -rf " + B);
            Log.add("  replaced " + pkg + " — login kept");
        }
    }

    private void install(List<File> apks) throws Exception {
        if (!Root.available()) throw new Exception("No root access. " + Root.lastError);
        String tmpDir = "/data/local/tmp/multirejoin";
        Root.run("mkdir -p " + tmpDir + "; rm -f " + tmpDir + "/*.apk");
        try {
            List<String> paths = new ArrayList<>();
            int i = 0;
            for (File f : apks) {
                String p = tmpDir + "/part" + (i++) + ".apk";
                Root.run("cp " + Root.q(f.getAbsolutePath()) + " " + p + " && chmod 644 " + p);
                paths.add(p);
            }
            Root.Res r;
            if (paths.size() == 1) {
                r = Root.run("pm install -r -d -g " + paths.get(0));
            } else {
                String c = Root.out("pm install-create -r -d -g");
                Matcher m = Pattern.compile("\\[(\\d+)\\]").matcher(c);
                if (!m.find()) throw new Exception("Install failed: " + c);
                String sid = m.group(1);
                for (int k = 0; k < paths.size(); k++) {
                    long size = new File(apks.get(k).getAbsolutePath()).length();
                    Root.Res w = Root.run("pm install-write -S " + size + " " + sid + " part" + k + " " + paths.get(k));
                    if (!w.out.contains("Success")) {
                        Root.run("pm install-abandon " + sid);
                        throw new Exception("Install failed: " + w.out.trim());
                    }
                }
                r = Root.run("pm install-commit " + sid);
            }
            if (!r.out.contains("Success")) {
                String msg = r.out.trim();
                if (msg.contains("UPDATE_INCOMPATIBLE"))
                    msg += "\n  -> An app with this name is already installed with a different signature. Uninstall it first.";
                throw new Exception("Install failed: " + short1(msg));
            }
        } finally {
            Root.run("rm -f " + tmpDir + "/*.apk");
        }
    }
}
