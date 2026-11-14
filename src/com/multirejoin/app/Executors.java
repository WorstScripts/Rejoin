package com.multirejoin.app;

import java.util.ArrayList;
import java.util.List;

/** Known executors: how to recognise them, and where their download page is (set by the user). */
public final class Executors {
    private Executors() {}

    public static final String[][] KNOWN = {
            // name, words in the file name / app name, how the site downloads
            {"Delta", "delta", "auto"},
            {"Arceus X Neo", "arceus|neo", "tap"},
            {"Codex", "codex", "tap"},
            {"Fluxus", "fluxus", "tap"},
            {"KRNL", "krnl", "tap"},
            {"Vega X", "vega", "tap"},
            {"Cryptic", "cryptic", "tap"},
            {"Trigon", "trigon", "tap"},
            {"Evon", "evon", "tap"},
            {"Hydrogen", "hydrogen", "tap"},
            {"Ronix", "ronix", "tap"},
    };

    /** Executor name for a file name / app label, or "Roblox" if it isn't a known executor. */
    public static String detect(String... hints) {
        for (String[] k : KNOWN) {
            for (String h : hints) {
                if (h == null) continue;
                String low = h.toLowerCase();
                for (String w : k[1].split("\\|")) if (low.contains(w)) return k[0];
            }
        }
        return "Roblox";
    }

    /** Built-in download pages (the user can change them; long-press in the Clone tab). */
    public static String defaultLink(String name) {
        if ("Delta".equals(name)) return "https://delta.filenetwork.vip/android.html";
        if ("Arceus X Neo".equals(name)) return "https://spdmteam.com/neo#downloads";
        return "";
    }

    public static boolean autoDownload(String name) {
        for (String[] k : KNOWN) if (k[0].equals(name)) return "auto".equals(k[2]);
        return false;
    }

    public static List<String> names() {
        List<String> n = new ArrayList<>();
        for (String[] k : KNOWN) n.add(k[0]);
        return n;
    }

    /** Compares versions like "2.745.1142" numerically. >0 when a is newer than b. */
    public static int compare(String a, String b) {
        if (a == null) a = "";
        if (b == null) b = "";
        String[] x = a.replaceAll("[^0-9.]", "").split("\\.");
        String[] y = b.replaceAll("[^0-9.]", "").split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            long p = i < x.length && !x[i].isEmpty() ? Long.parseLong(x[i]) : 0;
            long q = i < y.length && !y[i].isEmpty() ? Long.parseLong(y[i]) : 0;
            if (p != q) return p > q ? 1 : -1;
        }
        return 0;
    }

    /** Same "family" = same package except the last letter (com.roblox.client, com.roblox.cliena, ...). */
    public static boolean sameFamily(String a, String b) {
        if (a == null || b == null || a.length() != b.length() || a.length() < 2) return false;
        return a.substring(0, a.length() - 1).equals(b.substring(0, b.length() - 1));
    }
}
