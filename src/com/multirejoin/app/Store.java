package com.multirejoin.app;

import android.content.Context;
import android.content.SharedPreferences;

/** Saved settings. */
public final class Store {
    private final SharedPreferences p;

    public Store(Context c) { p = c.getSharedPreferences("cfg", Context.MODE_PRIVATE); }

    public String s(String k, String d) { return p.getString(k, d); }
    public int i(String k, int d) { return p.getInt(k, d); }
    public boolean b(String k, boolean d) { return p.getBoolean(k, d); }
    public void put(String k, String v) { p.edit().putString(k, v).apply(); }
    public void put(String k, int v) { p.edit().putInt(k, v).apply(); }
    public void put(String k, boolean v) { p.edit().putBoolean(k, v).apply(); }

    // global
    public String gameLink() { return s("game", ""); }
    public int launchDelay() { return i("launchDelay", 15); }
    public int checkEvery() { return i("checkEvery", 20); }
    public int grace() { return i("grace", 180); }
    public boolean presenceCheck() { return b("presence", true); }
    public boolean freezeCheck() { return b("freeze", true); }
    /** -1 = don't change, 0 = auto, >0 = fixed density */
    public int densityMode() { return i("density", 0); }
    public boolean landscape() { return b("landscape", true); }
    /** Grid has at least this many window slots, so a few instances still get small windows. */
    public int minSlots() { return i("minSlots", 4); }
    public int gridSlots(int n) { return Math.max(Math.max(1, n), minSlots()); }

    // per instance
    public boolean enabled(String pkg) { return b("en_" + pkg, true); }
    public void setEnabled(String pkg, boolean v) { put("en_" + pkg, v); }
    public String user(String pkg) { return s("user_" + pkg, ""); }
    public void setUser(String pkg, String v) { put("user_" + pkg, v); }
    public long userId(String pkg) { try { return Long.parseLong(s("uid_" + pkg, "0")); } catch (Exception e) { return 0; } }
    public void setUserId(String pkg, long v) { put("uid_" + pkg, String.valueOf(v)); }
    public String link(String pkg) { return s("link_" + pkg, ""); }
    public void setLink(String pkg, String v) { put("link_" + pkg, v); }
}
