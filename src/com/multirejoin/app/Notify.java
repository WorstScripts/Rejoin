package com.multirejoin.app;

import android.content.Context;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Sends messages to a Discord webhook (Settings › Discord). */
public final class Notify {
    private Notify() {}

    public static void send(Context c, final String text) {
        Store st = new Store(c);
        final String url = st.s("webhook", "").trim();
        if (!url.startsWith("https://")) return;
        if (!st.b("discordOn", true)) return;
        final String device = st.s("deviceName", android.os.Build.MODEL);
        new Thread(new Runnable() { public void run() { post(url, "[" + device + "] " + text); } }).start();
    }

    /** Returns null on success, or an error message. */
    public static String post(String url, String text) {
        try {
            JSONObject o = new JSONObject();
            o.put("username", "WORST - AUTO REJOIN");
            o.put("content", text.length() > 1900 ? text.substring(0, 1900) : text);
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(15000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            OutputStream os = c.getOutputStream();
            os.write(o.toString().getBytes("UTF-8"));
            os.close();
            int code = c.getResponseCode();
            c.disconnect();
            return code >= 200 && code < 300 ? null : "Discord answered " + code;
        } catch (Exception e) {
            return e.getMessage();
        }
    }

    public static String duration(long ms) {
        long m = ms / 60000, h = m / 60;
        return h > 0 ? h + "h " + (m % 60) + "m" : m + "m";
    }
}
