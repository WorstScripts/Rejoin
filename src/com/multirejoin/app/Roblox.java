package com.multirejoin.app;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Roblox helpers: find installed Roblox apps, read logged-in account, presence, game links. */
public final class Roblox {
    private Roblox() {}

    /** All installed apps that open roblox:// links (official app, executors and their clones). */
    public static List<String> installedApps(Context c) {
        PackageManager pm = c.getPackageManager();
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("roblox://placeID=1"));
        List<ResolveInfo> list = pm.queryIntentActivities(i, PackageManager.MATCH_ALL);
        List<String> out = new ArrayList<>();
        for (ResolveInfo r : list) {
            String p = r.activityInfo.packageName;
            if (!out.contains(p) && !p.equals(c.getPackageName())) out.add(p);
        }
        Collections.sort(out, new Comparator<String>() {
            @Override public int compare(String a, String b) {
                boolean ao = a.equals("com.roblox.client"), bo = b.equals("com.roblox.client");
                if (ao != bo) return ao ? -1 : 1;
                return a.compareTo(b);
            }
        });
        return out;
    }

    /** Turn whatever the user typed (place id, game URL, private server link, roblox:// link) into a roblox:// link. */
    public static String toUri(String in) {
        if (in == null) return null;
        String s = in.trim();
        if (s.isEmpty()) return null;
        if (s.matches("\\d+")) return "roblox://placeID=" + s;
        if (s.startsWith("roblox://") || s.startsWith("roblox-player:")) return s;
        Matcher share = Pattern.compile("[?&]code=([A-Za-z0-9_\\-]+)").matcher(s);
        if (s.contains("/share") && share.find()) {
            String type = "Server";
            Matcher t = Pattern.compile("[?&]type=([A-Za-z]+)").matcher(s);
            if (t.find()) type = t.group(1);
            return "roblox://navigation/share_links?code=" + share.group(1) + "&type=" + type;
        }
        Matcher g = Pattern.compile("/games/(\\d+)").matcher(s);
        if (g.find()) {
            Matcher lc = Pattern.compile("privateServerLinkCode=([A-Za-z0-9_\\-]+)").matcher(s);
            if (lc.find()) return "roblox://placeID=" + g.group(1) + "&linkCode=" + lc.group(1);
            return "roblox://placeID=" + g.group(1);
        }
        return null;
    }

    // ---------------- account detection ----------------

    /** Why the last account check failed (shown in the log). */
    public static volatile String lastReason = "";

    /** Reads the .ROBLOSECURITY cookie of a Roblox app (root). Never leaves the device except to roblox.com. */
    public static String readCookie(Context c, String pkg) {
        Found f = detect(c, pkg);
        return f == null ? null : f.cookie;
    }

    public static final class Found { public String cookie; public Account account; }

    private static final java.util.concurrent.atomic.AtomicInteger tmpSeq = new java.util.concurrent.atomic.AtomicInteger();

    /**
     * Finds the logged-in account of a Roblox app. Tries every login cookie it can find
     * (database read first, then a raw search of the files, like the Termux script), newest first.
     */
    public static Found detect(Context c, String pkg) {
        String dbs = Root.out("find /data/data/" + pkg + " -name Cookies -type f 2>/dev/null");
        if (!dbs.contains("/")) { lastReason = "no login file (not logged in yet)"; return null; }
        File dir = c.getCacheDir();
        List<String> candidates = new ArrayList<>();
        int copied = 0;
        for (String db : dbs.split("\n")) {
            db = db.trim();
            if (!db.startsWith("/")) continue;
            // unique temp names, so two checks running at once can't delete each other's files
            File tmp = new File(dir, "ck_" + pkg + "_" + tmpSeq.incrementAndGet() + ".db");
            String t = tmp.getAbsolutePath();
            File wal = new File(t + "-wal");
            if (!Root.catTo(db, tmp)) continue;
            copied++;
            if (!Root.catTo(db + "-wal", wal)) wal.delete();
            // 1) normal database read (every Roblox login row, newest first)
            SQLiteDatabase d = null;
            Cursor cur = null;
            try {
                d = SQLiteDatabase.openDatabase(t, null, SQLiteDatabase.OPEN_READWRITE);
                cur = d.rawQuery("select value from cookies where name='.ROBLOSECURITY' and host_key like '%roblox.com' order by last_access_utc desc", null);
                while (cur.moveToNext()) {
                    String v = cur.getString(0);
                    if (v != null && !v.isEmpty() && !candidates.contains(v)) candidates.add(v);
                }
            } catch (Exception e) {
                lastReason = "login database unreadable (" + e.getClass().getSimpleName() + ")";
            } finally {
                if (cur != null) cur.close();
                if (d != null) d.close();
            }
            // 2) raw search of the file bytes (works even if the database read fails)
            try {
                for (File f : new File[]{wal, tmp}) {
                    if (!f.exists()) continue;
                    List<String> raw = rawCookies(f);
                    for (int i = raw.size() - 1; i >= 0; i--) if (!candidates.contains(raw.get(i))) candidates.add(raw.get(i));
                }
            } catch (Exception ignored) {}
            tmp.delete();
            wal.delete();
            new File(t + "-shm").delete();
            new File(t + "-journal").delete();
        }
        if (copied == 0) { lastReason = "couldn't copy the login file (root problem?)"; return null; }
        if (candidates.isEmpty()) { lastReason = "no Roblox login found (log in on this app first)"; return null; }
        for (String ck : candidates) {
            Account a = whoAmI(ck);
            if (a != null) {
                Found f = new Found();
                f.cookie = ck;
                f.account = a;
                lastReason = "";
                return f;
            }
        }
        lastReason = "Roblox rejected the saved login" + (lastHttp.isEmpty() ? "" : " (" + lastHttp + ")") + " — log in again on this app";
        return null;
    }

    /** All ".ROBLOSECURITY" values found in a file's raw bytes, in file order. */
    private static List<String> rawCookies(File f) throws Exception {
        byte[] b = CloneCore.readAll(new java.io.FileInputStream(f));
        String s = new String(b, "ISO-8859-1");
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("_\\|WARNING:-DO-NOT-SHARE-THIS\\.[\\x20-\\x7e]{20,400}?\\|_[A-Za-z0-9_+=\\-]{40,}").matcher(s);
        while (m.find()) out.add(m.group());
        return out;
    }

    public static final class Account { public long id; public String name; }

    public static Account whoAmI(String cookie) {
        try {
            String r = http("GET", "https://users.roblox.com/v1/users/authenticated", null, cookie);
            if (r == null) return null;
            JSONObject o = new JSONObject(r);
            Account a = new Account();
            a.id = o.getLong("id");
            a.name = o.getString("name");
            return a;
        } catch (Exception e) {
            return null;
        }
    }

    public static long userIdOf(String username) {
        try {
            JSONObject body = new JSONObject();
            body.put("usernames", new JSONArray().put(username));
            body.put("excludeBannedUsers", false);
            String r = http("POST", "https://users.roblox.com/v1/usernames/users", body.toString(), null);
            JSONArray d = new JSONObject(r).getJSONArray("data");
            if (d.length() == 0) return 0;
            return d.getJSONObject(0).getLong("id");
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Presence type for each user id: 0 offline, 1 online (app/website), 2 in game, 3 in studio.
     * Missing key = unknown. Uses the account's own cookie when available (most accurate).
     */
    public static Map<Long, Integer> presence(List<Long> ids, Map<Long, String> cookies) {
        Map<Long, Integer> res = new HashMap<>();
        List<Long> anon = new ArrayList<>();
        for (Long id : ids) {
            String ck = cookies.get(id);
            if (ck != null) {
                Map<Long, Integer> one = presenceCall(Collections.singletonList(id), ck, "presence.roblox.com");
                if (one != null && one.containsKey(id)) { res.put(id, one.get(id)); continue; }
            }
            anon.add(id);
        }
        if (!anon.isEmpty()) {
            Map<Long, Integer> m = presenceCall(anon, null, "presence.roblox.com");
            if (m == null) m = presenceCall(anon, null, "presence.roproxy.com");
            if (m != null) res.putAll(m);
        }
        return res;
    }

    private static Map<Long, Integer> presenceCall(List<Long> ids, String cookie, String host) {
        try {
            JSONArray a = new JSONArray();
            for (Long id : ids) a.put(id.longValue());
            String r = http("POST", "https://" + host + "/v1/presence/users", "{\"userIds\":" + a + "}", cookie);
            if (r == null) return null;
            JSONArray up = new JSONObject(r).getJSONArray("userPresences");
            Map<Long, Integer> m = new HashMap<>();
            for (int i = 0; i < up.length(); i++) {
                JSONObject o = up.getJSONObject(i);
                m.put(o.getLong("userId"), o.getInt("userPresenceType"));
            }
            return m;
        } catch (Exception e) {
            return null;
        }
    }

    private static final Map<String, String> csrf = new HashMap<>();
    static volatile String lastHttp = "";

    static String http(String method, String url, String body, String cookie) throws Exception {
        for (int attempt = 0; attempt < 2; attempt++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(15000);
            c.setRequestMethod(method);
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) MultiRejoin");
            if (cookie != null) {
                c.setRequestProperty("Cookie", ".ROBLOSECURITY=" + cookie);
                String tok;
                synchronized (csrf) { tok = csrf.get(cookie); }
                if (tok != null) c.setRequestProperty("x-csrf-token", tok);
            }
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                OutputStream os = c.getOutputStream();
                os.write(body.getBytes("UTF-8"));
                os.close();
            }
            int code = c.getResponseCode();
            if (code == 403 && cookie != null && c.getHeaderField("x-csrf-token") != null && attempt == 0) {
                synchronized (csrf) { csrf.put(cookie, c.getHeaderField("x-csrf-token")); }
                c.disconnect();
                continue;
            }
            InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
            String s = "";
            if (in != null) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) > 0) bo.write(b, 0, n);
                in.close();
                s = bo.toString("UTF-8");
            }
            c.disconnect();
            lastHttp = code < 400 ? "" : "HTTP " + code;
            return code < 400 ? s : null;
        }
        return null;
    }
}
