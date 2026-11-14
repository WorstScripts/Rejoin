package com.worst.floatwin;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/** Makes each activity a small floating window with a draggable WORST title bar. Original code, no third-party parts. */
public class FloatWindows implements Application.ActivityLifecycleCallbacks {
    private final Application app;
    private final int slot;
    private final SharedPreferences prefs;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Map<Activity, PopupWindow> bars = new HashMap<>();
    private final Map<Activity, View.OnLayoutChangeListener> watchers = new HashMap<>();
    private int x = -1, y = -1, w, hgt, bar;

    FloatWindows(Application app, int slot) {
        this.app = app;
        this.slot = slot;
        this.prefs = app.getSharedPreferences("worst_float", Context.MODE_PRIVATE);
    }

    private int dp(Context c, float v) { return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f); }

    // ---------------- size & position ----------------

    private void computeSlot(Activity a) {
        if (dragged && w > 0) {
            // moved by hand: keep its size, just keep it on screen
            int[] sz = screen(a);
            x = Math.max(0, Math.min(x, sz[0] - w));
            y = Math.max(bar, Math.min(y, sz[1] - hgt));
            return;
        }
        int[] sz = screen(a);
        int sw = sz[0], sh = sz[1];
        int m = dp(a, 6);
        bar = dp(a, 26);
        boolean land = sw > sh;
        int cols = land ? 2 : 1;
        w = (sw - (cols + 1) * m) / cols;
        hgt = w * 9 / 16;
        int rowH = hgt + bar + m;
        int rowsFit = Math.max(1, (sh - m) / rowH);
        int col = slot % cols;
        int row = (slot / cols) % rowsFit;
        int dx = m + col * (w + m);
        int dy = m + bar + row * rowH;
        x = prefs.getInt(land ? "xl" : "xp", dx);
        y = prefs.getInt(land ? "yl" : "yp", dy);
        // the spot from WORST (written right before each Start) wins: same grid as all other instances
        int[] spot = worstSpot(a);
        if (spot != null && spot[2] - spot[0] > bar && spot[3] - spot[1] > bar * 2
                && spot[2] <= sw + 2 && spot[3] <= sh + 2) {
            x = spot[0];
            y = spot[1] + bar;                 // the title bar sits inside the spot, on top
            w = spot[2] - spot[0];
            hgt = spot[3] - spot[1] - bar;
            fromWorst = true;
        }
        if (w > sw) w = sw;
        // keep inside the screen
        x = Math.max(0, Math.min(x, sw - w));
        y = Math.max(bar, Math.min(y, sh - hgt));
    }

    /** The real screen in its real direction (the app's own view can be faked sideways by Roblox). */
    private static int[] screen(Activity a) {
        DisplayMetrics sys = android.content.res.Resources.getSystem().getDisplayMetrics();
        int sw = sys.widthPixels, sh = sys.heightPixels;
        try {
            android.hardware.display.DisplayManager dm =
                    (android.hardware.display.DisplayManager) a.getApplicationContext().getSystemService(Context.DISPLAY_SERVICE);
            android.graphics.Point p = new android.graphics.Point();
            dm.getDisplay(0).getRealSize(p);
            // use the real size, in the direction the system says the screen is
            int big = Math.max(p.x, p.y), small = Math.min(p.x, p.y);
            if (big > 0) { if (sw >= sh) { sw = big; sh = small; } else { sw = small; sh = big; } }
        } catch (Throwable ignored) {}
        return new int[]{sw, sh};
    }

    /** Roblox asks to be turned sideways; inside a window that only breaks the layout, so say "any direction". */
    private static void noForcedRotation(Activity a) {
        try { if (a.getRequestedOrientation() != -1) a.setRequestedOrientation(-1); } catch (Throwable ignored) {}
    }

    private boolean maxed = false;
    private boolean fromWorst = false;

    /** "left top right bottom" written by WORST into this app's files folder, or null. */
    private int[] worstSpot(Activity a) {
        try {
            java.io.File f = new java.io.File(a.getFilesDir(), "worst_float.txt");
            if (!f.exists() || dragged) return null;
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f));
            String[] p = r.readLine().trim().split("\\s+");
            r.close();
            return new int[]{Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3])};
        } catch (Throwable t) {
            return null;
        }
    }

    /** True once the user dragged the window this session: their spot wins until the next Start. */
    private boolean dragged = false;
    private long lastTap = 0;

    private void toggleMax(Activity a) {
        maxed = !maxed;
        applyWindow(a);
        placeBar(a);
    }

    private void applyWindow(Activity a) {
        try {
            Window win = a.getWindow();
            computeSlot(a);   // every time: the screen may have turned
            WindowManager.LayoutParams lp = win.getAttributes();
            lp.gravity = Gravity.TOP | Gravity.LEFT;
            if (maxed) {
                int[] sz = screen(a);
                lp.x = 0;
                lp.y = bar;
                lp.width = sz[0];
                lp.height = sz[1] - bar;
            } else {
                lp.x = x;
                lp.y = y;
                lp.width = w;
                lp.height = hgt;
            }
            lp.dimAmount = 0f;
            lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
            lp.flags &= ~WindowManager.LayoutParams.FLAG_DIM_BEHIND;
            win.setAttributes(lp);
        } catch (Throwable ignored) {}
    }

    private static void makeTranslucent(Activity a) {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                Activity.class.getMethod("setTranslucent", boolean.class).invoke(a, true);
                return;
            }
            for (Method m : Activity.class.getDeclaredMethods()) {
                if (m.getName().equals("convertToTranslucent")) {
                    m.setAccessible(true);
                    Object[] args = new Object[m.getParameterTypes().length];
                    m.invoke(a, args);
                    return;
                }
            }
        } catch (Throwable ignored) {}
    }

    // ---------------- title bar ----------------

    private void showBar(final Activity a) {
        if (bars.containsKey(a) || a.isFinishing()) return;
        final View decor = a.getWindow().getDecorView();
        if (decor.getWindowToken() == null) {
            h.postDelayed(new Runnable() { public void run() { showBar(a); } }, 300);
            return;
        }
        try {
            LinearLayout row = new LinearLayout(a);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xf0111113);
            bg.setCornerRadii(new float[]{dp(a, 8), dp(a, 8), dp(a, 8), dp(a, 8), 0, 0, 0, 0});
            bg.setStroke(1, 0xff2a2a2e);
            row.setBackground(bg);
            row.setPadding(dp(a, 10), 0, dp(a, 4), 0);

            View dot = new View(a);
            GradientDrawable dd = new GradientDrawable();
            dd.setShape(GradientDrawable.OVAL);
            dd.setColor(0xffd0202e);
            dot.setBackground(dd);
            row.addView(dot, new LinearLayout.LayoutParams(dp(a, 8), dp(a, 8)));

            TextView title = new TextView(a);
            CharSequence label = a.getApplicationInfo().loadLabel(a.getPackageManager());
            title.setText("  " + label);
            title.setTextColor(Color.WHITE);
            title.setTextSize(12);
            title.setSingleLine(true);
            title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            row.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

            TextView min = button(a, "—");
            min.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { a.moveTaskToBack(true); } });
            row.addView(min);
            TextView max = button(a, "▢");
            max.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { toggleMax(a); } });
            row.addView(max);
            TextView close = button(a, "✕");
            close.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
                try { a.finishAndRemoveTask(); } catch (Throwable t) { a.finish(); }
            }});
            row.addView(close);

            final PopupWindow pw = new PopupWindow(row, w, bar, false);
            pw.setClippingEnabled(false);
            pw.setTouchable(true);
            pw.setOutsideTouchable(false);
            try { PopupWindow.class.getMethod("setLayoutInScreenEnabled", boolean.class).invoke(pw, true); } catch (Throwable ignored) {}

            // drag the bar to move the window
            row.setOnTouchListener(new View.OnTouchListener() {
                float sx, sy;
                int ox, oy;
                public boolean onTouch(View v, MotionEvent e) {
                    switch (e.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            sx = e.getRawX(); sy = e.getRawY(); ox = x; oy = y;
                            toFront(a);
                            long now = System.currentTimeMillis();
                            if (now - lastTap < 350) { lastTap = 0; toggleMax(a); return true; }
                            lastTap = now;
                            return true;
                        case MotionEvent.ACTION_MOVE:
                            if (maxed) return true;
                            dragged = true;
                            x = ox + (int) (e.getRawX() - sx);
                            y = Math.max(bar, oy + (int) (e.getRawY() - sy));
                            applyWindow(a);
                            pw.update(x, y - bar, -1, -1);
                            return true;
                        case MotionEvent.ACTION_UP:
                        case MotionEvent.ACTION_CANCEL:
                            int[] sz = screen(a);
                            boolean land = sz[0] > sz[1];
                            x = Math.max(0, Math.min(x, sz[0] - w));
                            y = Math.max(bar, Math.min(y, sz[1] - hgt));
                            prefs.edit().putInt(land ? "xl" : "xp", x).putInt(land ? "yl" : "yp", y).apply();
                            applyWindow(a);
                            placeBar(a);
                            return true;
                    }
                    return false;
                }
            });
            pw.showAtLocation(decor, Gravity.TOP | Gravity.LEFT, x, Math.max(0, y - bar));
            bars.put(a, pw);
            placeBar(a);
        } catch (Throwable ignored) {}
    }

    private void placeBar(Activity a) {
        PopupWindow pw = bars.get(a);
        if (pw == null) return;
        try {
            int[] sz = screen(a);
            int bw = maxed ? sz[0] : w;
            int bx = maxed ? 0 : Math.max(0, Math.min(x, sz[0] - bw));
            int by = maxed ? 0 : Math.max(0, y - bar);
            pw.update(bx, by, bw, bar);
        } catch (Throwable ignored) {}
    }

    private TextView button(Context c, String s) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextColor(0xffd8d8dc);
        t.setTextSize(14);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(c, 10), dp(c, 2), dp(c, 10), dp(c, 2));
        return t;
    }

    /** Touching a window that isn't in front brings its app to the front (needs REORDER_TASKS). */
    private void toFront(Activity a) {
        try {
            ActivityManager am = (ActivityManager) a.getSystemService(Context.ACTIVITY_SERVICE);
            am.moveTaskToFront(a.getTaskId(), 0);
        } catch (Throwable ignored) {}
    }

    /** Wraps the window's input so a tap inside a background window brings it to the front. */
    private void watchTouches(final Activity a) {
        try {
            final Window win = a.getWindow();
            final Window.Callback orig = win.getCallback();
            if (orig == null || orig instanceof TouchProxy) return;
            win.setCallback(new TouchProxy(orig, new Runnable() { public void run() { toFront(a); } }));
        } catch (Throwable ignored) {}
    }

    // ---------------- lifecycle ----------------

    @Override public void onActivityCreated(final Activity a, Bundle b) {
        noForcedRotation(a);
        makeTranslucent(a);
        applyWindow(a);
        watchTouches(a);
        // if the app resets its own window size later, put it back
        View decor = a.getWindow().getDecorView();
        View.OnLayoutChangeListener l = new View.OnLayoutChangeListener() {
            public void onLayoutChange(View v, int l1, int t, int r, int bt, int ol, int ot, int or, int ob) {
                if (a.getRequestedOrientation() != -1) noForcedRotation(a);
                WindowManager.LayoutParams lp = a.getWindow().getAttributes();
                boolean wrong = maxed ? lp.width != screen(a)[0] : (lp.width != w || lp.height != hgt);
                if (wrong) h.post(new Runnable() { public void run() { applyWindow(a); placeBar(a); } });
            }
        };
        decor.addOnLayoutChangeListener(l);
        watchers.put(a, l);
    }

    @Override public void onActivityStarted(Activity a) {}

    @Override public void onActivityResumed(final Activity a) {
        noForcedRotation(a);
        applyWindow(a);
        placeBar(a);
        h.postDelayed(new Runnable() { public void run() { showBar(a); } }, 200);
    }

    @Override public void onActivityPaused(Activity a) {}
    @Override public void onActivityStopped(Activity a) {}
    @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}

    @Override public void onActivityDestroyed(Activity a) {
        PopupWindow pw = bars.remove(a);
        if (pw != null) try { pw.dismiss(); } catch (Throwable ignored) {}
        View.OnLayoutChangeListener l = watchers.remove(a);
        if (l != null) try { a.getWindow().getDecorView().removeOnLayoutChangeListener(l); } catch (Throwable ignored) {}
    }
}
