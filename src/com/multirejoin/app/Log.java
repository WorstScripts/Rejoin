package com.multirejoin.app;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** In-memory log shown in the app. */
public final class Log {
    private static final List<String> lines = new ArrayList<>();
    private static int version = 0;
    private static final SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss", Locale.US);

    public static synchronized void add(String msg) {
        lines.add("[" + fmt.format(new Date()) + "] " + msg);
        while (lines.size() > 400) lines.remove(0);
        version++;
        android.util.Log.i("MultiRejoin", msg);
    }

    public static synchronized int version() { return version; }

    public static synchronized String text(int last) {
        StringBuilder sb = new StringBuilder();
        for (int i = Math.max(0, lines.size() - last); i < lines.size(); i++) sb.append(lines.get(i)).append('\n');
        return sb.toString();
    }

    public static synchronized void clear() { lines.clear(); version++; }
}
