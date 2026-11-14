package com.multirejoin.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private Ui ui;
    private Store st;
    private final Handler h = new Handler();
    private FrameLayout content;
    private TextView titleView, subtitleView;
    private ImageView backBtn;
    private final List<LinearLayout> navItems = new ArrayList<>();
    private int tab = 0;
    private boolean inDetail = false;
    private volatile boolean busy = false;

    // live views refreshed by the timer
    private TextView logView;
    private int logVersion = -1;
    private Runnable liveRefresh;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ui = new Ui(this);
        st = new Store(this);
        Device.ctx = getApplicationContext();
        getWindow().setStatusBarColor(0xff000000);
        getWindow().setNavigationBarColor(Ui.SURFACE);

        LinearLayout root = ui.vbox();
        root.setBackgroundColor(Ui.BG);

        // top bar
        LinearLayout bar = ui.hbox();
        bar.setBackgroundColor(Ui.PRIMARY_DARK);
        bar.setPadding(ui.dp(8), ui.dp(12), ui.dp(16), ui.dp(12));
        backBtn = ui.icon(R.drawable.ic_back, Color.WHITE, 26);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ui.dp(26), ui.dp(26));
        blp.setMargins(ui.dp(8), 0, ui.dp(12), 0);
        backBtn.setLayoutParams(blp);
        backBtn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { onBackPressed(); } });
        bar.addView(backBtn);
        LinearLayout titles = ui.vbox();
        titles.setPadding(ui.dp(8), 0, 0, 0);
        titleView = ui.text("", 22, Color.WHITE, true);
        titleView.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
        titleView.setLetterSpacing(0.06f);
        subtitleView = ui.text("", 12, Ui.RED, true);
        subtitleView.setLetterSpacing(0.12f);
        titles.addView(titleView);
        titles.addView(subtitleView);
        bar.addView(titles, ui.weight(1));
        root.addView(bar, ui.match());

        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        // bottom navigation
        LinearLayout nav = ui.hbox();
        nav.setBackgroundColor(Ui.SURFACE);
        nav.setPadding(0, ui.dp(6), 0, ui.dp(6));
        String[] names = {"Home", "Clone", "Scripts", "Device", "Settings"};
        int[] icons = {R.drawable.ic_home, R.drawable.ic_copy, R.drawable.ic_code, R.drawable.ic_chip, R.drawable.ic_tune};
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            LinearLayout item = ui.vbox();
            item.setGravity(Gravity.CENTER);
            item.addView(ui.icon(icons[i], Ui.SUB, 24));
            TextView t = ui.text(names[i], 12, Ui.SUB, true);
            t.setGravity(Gravity.CENTER);
            item.addView(t);
            item.setBackground(ui.ripple(Ui.SURFACE, 12));
            item.setPadding(0, ui.dp(4), 0, ui.dp(4));
            item.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { showTab(idx); } });
            nav.addView(item, ui.weight(1));
            navItems.add(item);
        }
        root.addView(nav, ui.match());
        setContentView(root);

        showTab(0);
        h.post(tick);
        checkRoot();
    }

    @Override protected void onDestroy() { h.removeCallbacks(tick); super.onDestroy(); }

    @Override protected void onResume() {
        super.onResume();
        if (!inDetail && tab == 0) showTab(0);
        if (!inDetail && tab == 1) { fileSrc = null; showTab(1); }
    }

    @Override public void onBackPressed() {
        if (web != null && web.canGoBack()) { web.goBack(); return; }
        if (web != null) { web.destroy(); web = null; }
        if (inDetail) { showTab(tab); return; }
        if (tab != 0) { showTab(0); return; }
        super.onBackPressed();
    }

    private final Runnable tick = new Runnable() {
        public void run() {
            if (logView != null && Log.version() != logVersion) {
                logVersion = Log.version();
                logView.setText(Log.text(60));
            }
            if (liveRefresh != null) liveRefresh.run();
            h.postDelayed(this, 1000);
        }
    };

    private void checkRoot() {
        bg(new Runnable() { public void run() {
            Root.reset();
            if (Root.available()) {
                // let this app read APK files in storage
                String me = getPackageName();
                Root.run("pm grant " + me + " android.permission.READ_EXTERNAL_STORAGE; pm grant " + me
                        + " android.permission.WRITE_EXTERNAL_STORAGE; appops set " + me + " MANAGE_EXTERNAL_STORAGE allow; true");
                Log.add("Root OK");
                runOnUiThread(new Runnable() { public void run() { KeeperService.sync(MainActivity.this); } });
                return;
            }
            Log.add("No root: " + Root.lastError);
            runOnUiThread(new Runnable() { public void run() {
                new AlertDialog.Builder(MainActivity.this).setTitle("Root needed")
                        .setMessage("This app can't get root on this device.\n\n"
                                + "• Magisk: allow the superuser prompt, or open Magisk › Superuser and turn this app on.\n"
                                + "• KernelSU / APatch: open the manager app › Superuser and turn this app on.\n"
                                + "• Redfinger / UGPhone: turn on Root in the cloud phone settings.\n\n"
                                + "Then tap Try again.")
                        .setPositiveButton("Try again", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) { checkRoot(); }
                        })
                        .setNegativeButton("Close", null).show();
            }});
        }});
    }

    // ------------------------------------------------------------------ navigation

    private void setTitles(String t, String s, boolean back) {
        titleView.setText(t);
        subtitleView.setText(s);
        subtitleView.setVisibility(s == null || s.isEmpty() ? View.GONE : View.VISIBLE);
        backBtn.setVisibility(back ? View.VISIBLE : View.GONE);
    }

    private void showTab(int i) {
        if (web != null) { web.destroy(); web = null; }
        tab = i;
        inDetail = false;
        for (int k = 0; k < navItems.size(); k++) {
            LinearLayout it = navItems.get(k);
            int col = k == i ? Ui.RED : Ui.SUB;
            ((ImageView) it.getChildAt(0)).setImageTintList(android.content.res.ColorStateList.valueOf(col));
            ((TextView) it.getChildAt(1)).setTextColor(col);
        }
        LinearLayout page;
        if (i == 0) { setTitles("WORST", "AUTO REJOIN", false); page = homePage(); }
        else if (i == 1) { setTitles("Clone", "ROBLOX & EXECUTORS", false); page = clonePage(); }
        else if (i == 2) { setTitles("Scripts", "AUTOEXECUTE & SCRIPTS", false); page = scriptsPage(); }
        else if (i == 3) { setTitles("Device", "SPECS & HOW MANY TO RUN", false); page = devicePage(); }
        else { setTitles("Settings", "SCREEN · WINDOWS · REJOIN", false); page = settingsPage(); }
        setPage(page);
    }

    private LinearLayout newPage() {
        logView = null;
        liveRefresh = null;
        logVersion = -1;
        LinearLayout p = ui.vbox();
        p.setPadding(0, ui.dp(8), 0, ui.dp(24));
        return p;
    }

    private void setPage(View page) {
        content.removeAllViews();
        ScrollView sv = new ScrollView(this);
        sv.addView(page);
        content.addView(sv);
    }

    // ------------------------------------------------------------------ HOME

    private LinearLayout homePage() {
        LinearLayout p = newPage();

        // hero card with the big start/stop button
        LinearLayout hero = ui.vbox();
        Drawable art = new CropDrawable(((android.graphics.drawable.BitmapDrawable) getResources().getDrawable(R.drawable.banner)).getBitmap());
        GradientDrawable shade = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x33000000, 0xee000000});
        shade.setCornerRadius(ui.dp(20));
        android.graphics.drawable.LayerDrawable heroBg = new android.graphics.drawable.LayerDrawable(new Drawable[]{art, shade});
        hero.setBackground(heroBg);
        hero.setClipToOutline(true);
        hero.setOutlineProvider(new android.view.ViewOutlineProvider() {
            public void getOutline(View v, android.graphics.Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), ui.dp(20)); }
        });
        hero.setPadding(ui.dp(18), ui.dp(96), ui.dp(18), ui.dp(18));
        LinearLayout.LayoutParams hlp = ui.match();
        hlp.setMargins(ui.dp(14), ui.dp(8), ui.dp(14), ui.dp(8));
        p.addView(hero, hlp);
        final TextView status = ui.text("", 20, Color.WHITE, true);
        final TextView statusSub = ui.text("", 13, 0xddffffff, false);
        hero.addView(status);
        hero.addView(statusSub);
        final Button go = ui.button("", Ui.PRIMARY, Ui.ON_PRIMARY);
        LinearLayout.LayoutParams glp = ui.match();
        glp.topMargin = ui.dp(14);
        hero.addView(go, glp);
        go.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            go.setEnabled(false);
            h.postDelayed(new Runnable() { public void run() { go.setEnabled(true); } }, 2500);
            if (RunnerService.isRunning()) {
                startService(new Intent(MainActivity.this, RunnerService.class).setAction(RunnerService.ACTION_STOP));
                return;
            }
            if (Roblox.toUri(st.gameLink()) == null) { toast("Enter a Place ID or game link first"); return; }
            startFg(new Intent(MainActivity.this, RunnerService.class).setAction(RunnerService.ACTION_START));
            toast("Starting…");
        }});

        boolean anyFloat = false;
        for (String ap : Roblox.installedApps(this)) if (st.b("float_" + ap, false)) anyFloat = true;
        if (Build.VERSION.SDK_INT < 35 && !anyFloat) {
            LinearLayout warn = ui.card(p);
            warn.addView(ui.header("Android 15 needed"));
            warn.addView(ui.note("This device runs Android " + Build.VERSION.RELEASE + ". WORST - AUTO REJOIN is made for Android 15 — "
                    + "on older versions the Roblox windows may not show. Use an Android 15 cloud phone, or make Floating clones on the Clone tab (they work on any Android)."));
        }

        // game
        LinearLayout gameCard = ui.card(p);
        gameCard.addView(ui.header("Game"));
        gameCard.addView(ui.note("Place ID, game link, or private server link. Used by every instance unless you set its own."));
        final EditText game = ui.field("e.g. 107778070777162", st.gameLink());
        game.addTextChangedListener(saver("game"));
        gameCard.addView(game, ui.fieldLp());

        // instances
        final LinearLayout inst = ui.card(p);
        final List<String> apps = Roblox.installedApps(this);
        LinearLayout ih = ui.hbox();
        ih.addView(ui.header("Instances (" + apps.size() + ")"), ui.weight(1));
        ImageView refresh = ui.icon(R.drawable.ic_refresh, Ui.SUB, 22);
        refresh.setPadding(0, 0, 0, 0);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ui.dp(22), ui.dp(22));
        rlp.setMargins(0, 0, ui.dp(18), 0);
        refresh.setLayoutParams(rlp);
        refresh.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { detectAccounts(); } });
        ih.addView(refresh);
        inst.addView(ih);
        if (apps.isEmpty()) inst.addView(ui.note("No Roblox apps yet. Go to Clone to make some."));
        final List<TextView> stateViews = new ArrayList<>();
        PackageManager pm = getPackageManager();
        for (int i = 0; i < apps.size(); i++) {
            final String pkg = apps.get(i);
            Drawable icon = null;
            String label = pkg;
            try {
                ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                icon = pm.getApplicationIcon(ai);
                label = String.valueOf(pm.getApplicationLabel(ai));
            } catch (Exception ignored) {}
            String user = st.user(pkg);
            String sub = (user.isEmpty() ? "No account detected" : "@" + user) + "  ·  " + pkg;
            Switch sw = ui.toggle(st.enabled(pkg));
            sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                public void onCheckedChanged(CompoundButton b, boolean on) { st.setEnabled(pkg, on); }
            });
            final String lbl = label;
            final Drawable ic = icon;
            LinearLayout row = ui.row(ui.appIcon(icon), label, sub, sw, new View.OnClickListener() {
                public void onClick(View v) { instanceActions(pkg, lbl, ic); }
            });
            TextView stv = ui.text("", 12, Ui.GREEN, true);
            stv.setVisibility(View.GONE);
            ((LinearLayout) row.getChildAt(2)).addView(stv);
            stateViews.add(stv);
            if (i > 0) inst.addView(ui.divider());
            inst.addView(row);
        }
        inst.addView(ui.note("Tap an instance to open it in a window, add a home screen shortcut, or set its account and game."));

        final int appCount = apps.size();
        final String userSig = userSignature(apps);
        final long[] lastAuto = {0};
        liveRefresh = new Runnable() { public void run() {
            // refresh by itself: new / removed apps, newly detected accounts, ticks changed by Start
            long nowT = System.currentTimeMillis();
            if (nowT - lastAuto[0] > 3000) {
                lastAuto[0] = nowT;
                List<String> cur = Roblox.installedApps(MainActivity.this);
                if (cur.size() != appCount || !userSignature(cur).equals(userSig)) { showTab(0); return; }
            }
            boolean run = RunnerService.isRunning();
            status.setText(run ? "Running" : "Stopped");
            statusSub.setText(run ? RunnerService.status() : "Tap Start to launch every ticked instance and keep them in game.");
            go.setText(run ? "Stop" : "Start");
            go.setTextColor(run ? Ui.RED : Ui.ON_PRIMARY);
            for (int i = 0; i < apps.size() && i < stateViews.size(); i++) {
                String s = RunnerService.state.get(apps.get(i));
                TextView tv = stateViews.get(i);
                if (!run || s == null) { tv.setVisibility(View.GONE); continue; }
                tv.setVisibility(View.VISIBLE);
                Long sa = RunnerService.startedAt.get(apps.get(i));
                Integer rj = RunnerService.rejoins.get(apps.get(i));
                tv.setText("● " + s + (sa == null ? "" : "  ·  up " + Notify.duration(System.currentTimeMillis() - sa))
                        + (rj == null || rj == 0 ? "" : "  ·  " + rj + " rejoin" + (rj == 1 ? "" : "s")));
                tv.setTextColor(s.equals("In game") ? Ui.GREEN : s.equals("Launching") ? Ui.ORANGE : Ui.RED);
            }
        }};
        liveRefresh.run();
        autoDetect();

        addLog(p);
        return p;
    }

    /** Accounts + ticks of every app, so the Home list redraws when something changes. */
    private String userSignature(List<String> apps) {
        StringBuilder sb = new StringBuilder();
        for (String a : apps) sb.append(a).append('=').append(st.user(a)).append(st.enabled(a)).append(';');
        return sb.toString();
    }

    private long lastDetect = 0;

    /** Looks for logged-in accounts in the background (at most once a minute). */
    private void autoDetect() {
        if (busy || System.currentTimeMillis() - lastDetect < 60000) return;
        lastDetect = System.currentTimeMillis();
        bg(new Runnable() { public void run() {
            if (!Root.available()) return;
            for (String p : Roblox.installedApps(MainActivity.this)) {
                Roblox.Found f = Roblox.detect(MainActivity.this, p);
                if (f != null && !f.account.name.equals(st.user(p))) {
                    st.setUser(p, f.account.name);
                    st.setUserId(p, f.account.id);
                }
            }
        }});
    }

    private void instanceActions(final String pkg, final String label, final Drawable icon) {
        String[] items = {"Open in a window", "Add to home screen", "Account & game", "Uninstall"};
        new AlertDialog.Builder(this).setTitle(label + "  (" + pkg + ")")
                .setItems(items, new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) {
                    if (w == 0) {
                        bg(new Runnable() { public void run() {
                            final String err = Device.openInWindow(MainActivity.this, pkg);
                            if (err != null) runOnUiThread(new Runnable() { public void run() { toast(err); } });
                        }});
                    } else if (w == 1) {
                        addShortcut(pkg, label, icon);
                    } else if (w == 2) {
                        editInstance(pkg);
                    } else {
                        new AlertDialog.Builder(MainActivity.this).setMessage("Uninstall " + label + " (" + pkg + ")? Its login is lost.")
                                .setPositiveButton("Uninstall", new DialogInterface.OnClickListener() { public void onClick(DialogInterface dd, int ww) {
                                    bg(new Runnable() { public void run() {
                                        try { new Cloner(MainActivity.this).uninstall(pkg); Log.add("Uninstalled " + pkg); }
                                        catch (Exception e) { Log.add("Uninstall failed: " + e.getMessage()); }
                                        runOnUiThread(new Runnable() { public void run() { showTab(0); } });
                                    }});
                                }}).setNegativeButton("Cancel", null).show();
                    }
                }}).show();
    }

    private void addShortcut(String pkg, String label, Drawable icon) {
        try {
            Intent open = new Intent(this, OpenActivity.class).setAction(Intent.ACTION_VIEW).putExtra("pkg", pkg)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Bitmap bmp = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888);
            if (icon != null) {
                Canvas cv = new Canvas(bmp);
                icon.setBounds(0, 0, 192, 192);
                icon.draw(cv);
            }
            Class<?> smC = Class.forName("android.content.pm.ShortcutManager");
            Object sm = getSystemService(smC);
            Class<?> siC = Class.forName("android.content.pm.ShortcutInfo");
            Class<?> bC = Class.forName("android.content.pm.ShortcutInfo$Builder");
            Object b = bC.getConstructor(Context.class, String.class).newInstance(this, "win_" + pkg);
            String name = label + " ⧉";
            bC.getMethod("setShortLabel", CharSequence.class).invoke(b, name);
            bC.getMethod("setIcon", Icon.class).invoke(b, Icon.createWithBitmap(bmp));
            bC.getMethod("setIntent", Intent.class).invoke(b, open);
            Object si = bC.getMethod("build").invoke(b);
            smC.getMethod("requestPinShortcut", siC, IntentSender.class).invoke(sm, si, null);
            toast("Confirm to add \"" + name + "\" to your home screen");
        } catch (Throwable e) {
            toast("Your launcher doesn't support shortcuts: " + e.getClass().getSimpleName());
        }
    }

    private void editInstance(final String p) {
        LinearLayout l = ui.vbox();
        l.setPadding(0, ui.dp(8), 0, 0);
        l.addView(ui.note("Roblox username (only needed if auto-detect can't find it)"));
        final EditText u = ui.field("username", st.user(p));
        l.addView(u, ui.fieldLp());
        l.addView(ui.note("Game for this instance only (empty = main game)"));
        final EditText g = ui.field("Place ID or link", st.link(p));
        l.addView(g, ui.fieldLp());
        new AlertDialog.Builder(this).setTitle(p).setView(l)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) {
                    String nu = u.getText().toString().trim();
                    if (!nu.equals(st.user(p))) { st.setUser(p, nu); st.setUserId(p, 0); }
                    st.setLink(p, g.getText().toString().trim());
                    showTab(0);
                }})
                .setNegativeButton("Cancel", null).show();
    }

    private void detectAccounts() {
        if (busy) return;
        busy = true;
        toast("Detecting accounts…");
        bg(new Runnable() { public void run() {
            try {
                for (String p : Roblox.installedApps(MainActivity.this)) {
                    Roblox.Found f = Roblox.detect(MainActivity.this, p);
                    if (f != null) {
                        st.setUser(p, f.account.name);
                        st.setUserId(p, f.account.id);
                        Log.add(p + ": @" + f.account.name);
                    } else {
                        Log.add(p + ": no account — " + Roblox.lastReason);
                    }
                }
            } finally {
                busy = false;
                runOnUiThread(new Runnable() { public void run() { if (!inDetail && tab == 0) showTab(0); } });
            }
        }});
    }

    // ------------------------------------------------------------------ CLONE

    private List<Cloner.Source> installedSrc;
    private List<Cloner.Source> fileSrc;
    private boolean scanning = false;

    private LinearLayout clonePage() {
        LinearLayout p = newPage();
        installedSrc = new Cloner(this).installedSources();

        updatesCard(p);

        LinearLayout a = ui.card(p);
        a.addView(ui.header("Installed Roblox apps"));
        if (installedSrc.isEmpty()) a.addView(ui.note("None installed. Pick an APK file below."));
        for (int i = 0; i < installedSrc.size(); i++) {
            if (i > 0) a.addView(ui.divider());
            a.addView(sourceRow(installedSrc.get(i)));
        }

        final LinearLayout f = ui.card(p);
        LinearLayout fh = ui.hbox();
        fh.addView(ui.header("APK files"), ui.weight(1));
        ImageView rf = ui.icon(R.drawable.ic_refresh, Ui.SUB, 22);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ui.dp(22), ui.dp(22));
        rlp.setMargins(0, 0, ui.dp(18), 0);
        rf.setLayoutParams(rlp);
        fh.addView(rf);
        f.addView(fh);
        final LinearLayout list = ui.vbox();
        f.addView(list);
        rf.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { scanFiles(list); } });
        if (fileSrc == null) scanFiles(list); else fillFiles(list);

        LinearLayout help = ui.card(p);
        help.addView(ui.header("How it works"));
        help.addView(ui.note("Pick Roblox or your executor (Delta, Arceus X, …), choose how many clones, tap Create. "
                + "Each clone is a separate app with its own login. Making clones again with the same names updates them and keeps logins."));
        return p;
    }

    private View sourceRow(final Cloner.Source s) {
        String sub = (s.filePath != null ? s.detail : s.pkg) + (s.version == null || s.version.isEmpty() ? "" : "  ·  v" + s.version);
        return ui.row(ui.appIcon(s.icon), s.label, sub, ui.chevron(), new View.OnClickListener() {
            public void onClick(View v) { cloneDetail(s); }
        });
    }

    private void scanFiles(final LinearLayout list) {
        if (scanning) return;
        scanning = true;
        list.removeAllViews();
        LinearLayout r = ui.hbox();
        r.setPadding(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(12));
        ProgressBar pb = new ProgressBar(this);
        r.addView(pb, new LinearLayout.LayoutParams(ui.dp(24), ui.dp(24)));
        TextView t = ui.text("   Searching your storage…", 14, Ui.SUB, false);
        r.addView(t);
        list.addView(r);
        bg(new Runnable() { public void run() {
            final List<Cloner.Source> s = new Cloner(MainActivity.this).fileSources();
            runOnUiThread(new Runnable() { public void run() {
                scanning = false;
                fileSrc = s;
                fillFiles(list);
                fillUpdates();
            }});
        }});
    }

    private LinearLayout updatesBox;

    /** "Executor updates": download page buttons + one-tap update of every clone when a newer APK is found. */
    private void updatesCard(LinearLayout p) {
        LinearLayout c = ui.card(p);
        c.addView(ui.header("Executor updates"));
        updatesBox = ui.vbox();
        c.addView(updatesBox);
        fillUpdates();
    }

    private String execOf(Cloner.Source s) { return AutoUpdater.execOf(this, s.pkg); }

    private void fillUpdates() {
        if (updatesBox == null) return;
        updatesBox.removeAllViews();
        List<Cloner.Source> inst = installedSrc == null ? new ArrayList<Cloner.Source>() : installedSrc;
        int rows = 0;
        // 1) newer APK files that can update installed apps
        if (fileSrc != null) {
            for (final Cloner.Source f : fileSrc) {
                if (f.pkg == null || f.version == null || f.version.isEmpty()) continue;
                final String ex = Executors.detect(f.detail, f.label);
                final List<Cloner.Source> older = new ArrayList<>();
                boolean anyOlder = false;
                for (Cloner.Source i : inst) {
                    if (!Executors.sameFamily(i.pkg, f.pkg)) continue;
                    older.add(i);
                    if (Executors.compare(f.version, i.version) > 0 && execOf(i).equals(ex)) anyOlder = true;
                }
                if (!anyOlder) continue;
                TextView pill = ui.pill("Update", Ui.GREEN);
                if (rows++ > 0) updatesBox.addView(ui.divider());
                updatesBox.addView(ui.row(ui.appIcon(f.icon), "⬆ " + ex + " v" + f.version + " found",
                        "Rebuilds the original + every clone  ·  " + f.detail, pill,
                        new View.OnClickListener() { public void onClick(View v) { updateDialog(f, older, ex); } }));
            }
        }
        // 2) download pages for the executors in use (Delta and Arceus X Neo always shown)
        List<String> execs = new ArrayList<>();
        for (Cloner.Source i : inst) { String e = execOf(i); if (!e.equals("Roblox") && !execs.contains(e)) execs.add(e); }
        for (String e : new String[]{"Delta", "Arceus X Neo"}) if (!execs.contains(e)) execs.add(e);
        for (final String name : execs) {
            final String link = st.s("dl_" + name, Executors.defaultLink(name));
            String ver = "";
            int count = 0;
            for (Cloner.Source i : inst) if (execOf(i).equals(name)) { count++; if (Executors.compare(i.version, ver) > 0) ver = i.version; }
            String sub = (count > 0 ? "On " + count + " app(s), v" + ver + "\n" : "")
                    + (link.isEmpty() ? "Tap to set its download page"
                       : Executors.autoDownload(name) ? "Opens the site here — the download starts by itself"
                       : "Opens the site here and taps Download for Android for you");
            if (rows++ > 0) updatesBox.addView(ui.divider());
            LinearLayout row = ui.row(ui.iconBubble(R.drawable.ic_shortcut), name, sub,
                    ui.pill(link.isEmpty() ? "Set link" : "Get latest", Ui.PRIMARY), new View.OnClickListener() {
                        public void onClick(View v) {
                            if (link.isEmpty()) { editLink(name); return; }
                            downloaderPage(name, link);
                        }
                    });
            row.setOnLongClickListener(new View.OnLongClickListener() { public boolean onLongClick(View v) { editLink(name); return true; } });
            updatesBox.addView(row);
        }
        updatesBox.addView(ui.note("Long-press an executor to change its download page. Downloads made in your browser show up here too, with an Update button."));
    }

    private android.webkit.WebView web;
    private volatile boolean downloading = false;

    /** Opens the executor's site inside the app and catches the APK download. */
    private void downloaderPage(final String name, String link) {
        inDetail = true;
        setTitles(name, "GET LATEST", true);
        logView = null;
        liveRefresh = null;
        content.removeAllViews();
        LinearLayout root = ui.vbox();
        final TextView status = ui.note(Executors.autoDownload(name)
                ? "Wait on this page — the download starts by itself in about 10 seconds."
                : "The app taps \"Download for Android\" for you.");
        status.setTextColor(Ui.TEXT);
        status.setPadding(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(6));
        root.addView(status, ui.match());
        final ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setVisibility(View.GONE);
        LinearLayout.LayoutParams blp = ui.match();
        blp.setMargins(ui.dp(16), 0, ui.dp(16), ui.dp(6));
        root.addView(bar, blp);
        web = new android.webkit.WebView(this);
        android.webkit.WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setSupportMultipleWindows(false);          // blocks pop-up ad tabs
        ws.setJavaScriptCanOpenWindowsAutomatically(false);
        ws.setLoadWithOverviewMode(true);
        ws.setUseWideViewPort(true);
        web.setWebViewClient(new android.webkit.WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(android.webkit.WebView v, String url) {
                return !(url.startsWith("http://") || url.startsWith("https://"));   // ignore intent:// and app links
            }
        });
        web.setDownloadListener(new android.webkit.DownloadListener() {
            @Override public void onDownloadStart(String url, String ua, String cd, String mime, long len) {
                startDownload(name, url, ua, cd, mime, len, status, bar);
            }
        });
        root.addView(web, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        content.addView(root);
        web.loadUrl(link);
        if (!Executors.autoDownload(name)) {
            status.setText("Finding the Android download button…");
            final android.webkit.WebView me = web;
            final long t0 = System.currentTimeMillis();
            h.postDelayed(new Runnable() { public void run() {
                if (web != me || downloading || System.currentTimeMillis() - t0 > 60000) return;
                me.evaluateJavascript(AutoUpdater.pageScript(true), new android.webkit.ValueCallback<String>() {
                    public void onReceiveValue(String r) {
                        if (r != null && r.contains("clicked") && !downloading) status.setText("Tapped the download button — waiting for the file…");
                    }
                });
                h.postDelayed(this, 2000);
            }}, 3000);
        }
    }

    private void startDownload(final String name, final String url, final String ua, final String cd, final String mime,
                               final long len, final TextView status, final ProgressBar bar) {
        if (downloading) return;
        downloading = true;
        final String fileName = guessApkName(url, cd, mime);
        status.setText("Downloading " + fileName + " …");
        bar.setVisibility(View.VISIBLE);
        bar.setIndeterminate(len <= 0);
        bar.setMax(1000);
        final String cookies = android.webkit.CookieManager.getInstance().getCookie(url);
        final String referer = web != null ? web.getUrl() : null;
        bg(new Runnable() { public void run() {
            File tmp = new File(getCacheDir(), "download.apk");
            String err = null;
            try {
                java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                c.setInstanceFollowRedirects(true);
                c.setConnectTimeout(20000);
                c.setReadTimeout(30000);
                if (ua != null) c.setRequestProperty("User-Agent", ua);
                if (cookies != null) c.setRequestProperty("Cookie", cookies);
                if (referer != null) c.setRequestProperty("Referer", referer);
                long total = c.getContentLength() > 0 ? c.getContentLength() : len;
                java.io.InputStream in = c.getInputStream();
                java.io.FileOutputStream out = new java.io.FileOutputStream(tmp);
                byte[] buf = new byte[1 << 16];
                long got = 0, lastUi = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    got += n;
                    long now = System.currentTimeMillis();
                    if (now - lastUi > 300) {
                        lastUi = now;
                        final long g = got, t = total;
                        runOnUiThread(new Runnable() { public void run() {
                            if (t > 0) { bar.setIndeterminate(false); bar.setProgress((int) (g * 1000 / t)); }
                            status.setText("Downloading " + fileName + "  " + DeviceInfo.gb(g / (1024 * 1024)) + (t > 0 ? " / " + DeviceInfo.gb(t / (1024 * 1024)) : ""));
                        }});
                    }
                }
                out.close();
                in.close();
                if (got < 1024 * 1024) err = "The file is too small — the site may have sent a web page instead of the APK.";
            } catch (Exception e) {
                err = e.getMessage();
            }
            if (err == null) {
                String dest = "/sdcard/Download/" + fileName;
                Root.run("mkdir -p /sdcard/Download; cp -f " + Root.q(tmp.getAbsolutePath()) + " " + Root.q(dest));
                Log.add("Downloaded " + name + ": Download/" + fileName);
                final Cloner.Source src = sourceFromFile(tmp, dest);
                tmp.delete();
                runOnUiThread(new Runnable() { public void run() {
                    downloading = false;
                    afterDownload(name, src);
                }});
            } else {
                tmp.delete();
                final String e2 = err;
                Log.add("Download failed: " + err);
                runOnUiThread(new Runnable() { public void run() {
                    downloading = false;
                    bar.setVisibility(View.GONE);
                    status.setText("Download failed: " + e2 + "  — try the button on the page again.");
                    status.setTextColor(Ui.RED);
                }});
            }
        }});
    }

    private static String guessApkName(String url, String cd, String mime) {
        String n = android.webkit.URLUtil.guessFileName(url, cd, mime);
        n = n.replaceAll("[^A-Za-z0-9._()\\- ]", "_");
        String low = n.toLowerCase();
        if (!(low.endsWith(".apk") || low.endsWith(".apks") || low.endsWith(".xapk"))) {
            n = n.replaceAll("\\.(bin|bin\\.apk|zip)$", "") + ".apk";
        }
        return n;
    }

    /** Reads package / version / icon of a just-downloaded APK. */
    private Cloner.Source sourceFromFile(File local, String dest) {
        Cloner.Source s = new Cloner.Source();
        s.filePath = dest;
        s.detail = dest.replaceFirst("^/sdcard/", "");
        s.label = dest.substring(dest.lastIndexOf('/') + 1);
        try {
            PackageManager pm = getPackageManager();
            android.content.pm.PackageInfo pi = pm.getPackageArchiveInfo(local.getAbsolutePath(), 0);
            if (pi != null) {
                pi.applicationInfo.sourceDir = local.getAbsolutePath();
                pi.applicationInfo.publicSourceDir = local.getAbsolutePath();
                s.pkg = pi.packageName;
                s.version = pi.versionName == null ? "" : pi.versionName;
                s.label = String.valueOf(pi.applicationInfo.loadLabel(pm));
                s.icon = pi.applicationInfo.loadIcon(pm);
            }
        } catch (Throwable ignored) {}
        return s;
    }

    private void afterDownload(String name, Cloner.Source src) {
        if (web != null) { web.destroy(); web = null; }
        fileSrc = null;
        if (src.pkg == null) {
            toast("Downloaded, but it doesn't look like an APK. Check Download/" + src.detail);
            showTab(1);
            return;
        }
        List<Cloner.Source> inst = new Cloner(this).installedSources();
        List<Cloner.Source> family = new ArrayList<>();
        for (Cloner.Source i : inst) if (Executors.sameFamily(i.pkg, src.pkg)) family.add(i);
        showTab(1);
        if (family.isEmpty()) cloneDetail(src); else updateDialog(src, family, name);
    }

    private void editLink(final String name) {
        LinearLayout l = ui.vbox();
        l.setPadding(0, ui.dp(8), 0, 0);
        l.addView(ui.note("Paste the official download page for " + name + ". Only use a site you trust — many fake sites spread malware."));
        final EditText e = ui.field("https://…", st.s("dl_" + name, Executors.defaultLink(name)));
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        l.addView(e, ui.fieldLp());
        new AlertDialog.Builder(this).setTitle(name + " download page").setView(l)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) {
                    String u = e.getText().toString().trim();
                    if (!u.isEmpty() && !u.startsWith("http")) u = "https://" + u;
                    st.put("dl_" + name, u);
                    fillUpdates();
                }}).setNegativeButton("Cancel", null).show();
    }

    /** Lists the original + all clones of this package family. Apps using this executor are ticked. */
    private void updateDialog(final Cloner.Source f, final List<Cloner.Source> family, final String ex) {
        final String[] items = new String[family.size()];
        final boolean[] on = new boolean[family.size()];
        for (int i = 0; i < family.size(); i++) {
            Cloner.Source o = family.get(i);
            int cmp = Executors.compare(f.version, o.version);
            items[i] = o.label + "  (" + o.pkg + ")\n" + execOf(o) + " v" + o.version
                    + (cmp > 0 ? "  →  v" + f.version : cmp == 0 ? "  →  re-clone" : "  (newer — skipped)");
            on[i] = cmp >= 0 && execOf(o).equals(ex);
        }
        new AlertDialog.Builder(this).setTitle("Update to " + ex + " v" + f.version)
                .setMultiChoiceItems(items, on, new DialogInterface.OnMultiChoiceClickListener() {
                    public void onClick(DialogInterface d, int w, boolean c) { on[w] = c; }
                })
                .setPositiveButton("Update all ticked", new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) {
                    List<Cloner.Source> pick = new ArrayList<>();
                    for (int i = 0; i < family.size(); i++) if (on[i]) pick.add(family.get(i));
                    if (!pick.isEmpty()) runUpdate(f, pick, ex);
                }}).setNegativeButton("Cancel", null).show();
    }

    private void runUpdate(final Cloner.Source f, final List<Cloner.Source> targets, final String ex) {
        if (busy) { toast("Busy, please wait…"); return; }
        busy = true;
        toast("Updating " + targets.size() + " app(s)… logins and keys are kept");
        if (RunnerService.isRunning()) {
            startService(new Intent(this, RunnerService.class).setAction(RunnerService.ACTION_STOP));
            Log.add("Stopped instances for the update. Press Start again when it's done.");
        }
        bg(new Runnable() { public void run() {
            Cloner cl = new Cloner(MainActivity.this);
            int ok = 0;
            try {
                List<File> apks = cl.collect(f);
                for (Cloner.Source t : targets) {
                    if (Executors.compare(f.version, t.version) < 0) { Log.add("Skipped " + t.pkg + " (it has a newer version)"); continue; }
                    Log.add((t.pkg.equals(f.pkg) ? "Updating original " : "Re-cloning + re-signing ") + t.label + " (" + t.pkg + ") → v" + f.version + " …");
                    try {
                        Device.forceStop(t.pkg);
                        if (t.pkg.equals(f.pkg)) cl.installOriginal(apks);
                        else cl.makeClone(apks, f.pkg, t.pkg, t.label == null || t.label.equals("Roblox") ? null : t.label);
                        st.put("exec_" + t.pkg, ex);
                        ok++;
                        Log.add("  ✓ updated");
                    } catch (Exception e) {
                        Log.add("  FAILED: " + e.getMessage());
                    }
                }
                for (File x : apks) x.delete();
            } catch (Throwable e) {
                Log.add("Update error: " + e.getMessage());
            }
            final int k = ok;
            Log.add("Update done: " + ok + "/" + targets.size() + " app(s) on " + ex + " v" + f.version);
            runOnUiThread(new Runnable() { public void run() {
                busy = false;
                toast("Updated " + k + "/" + targets.size());
                if (!inDetail && tab == 1) { fileSrc = null; showTab(1); }
            }});
        }});
    }

    private void fillFiles(LinearLayout list) {
        list.removeAllViews();
        if (fileSrc == null || fileSrc.isEmpty()) {
            list.addView(ui.note("No Roblox APK files found. Download your executor, then tap refresh."));
            return;
        }
        for (int i = 0; i < fileSrc.size(); i++) {
            if (i > 0) list.addView(ui.divider());
            list.addView(sourceRow(fileSrc.get(i)));
        }
    }

    private void cloneDetail(final Cloner.Source s) {
        inDetail = true;
        setTitles(s.label, s.filePath != null ? "APK file" : "Installed app", true);
        LinearLayout p = newPage();

        // header
        LinearLayout head = ui.vbox();
        head.setGravity(Gravity.CENTER_HORIZONTAL);
        head.setPadding(0, ui.dp(16), 0, ui.dp(10));
        View ic = ui.appIcon(s.icon);
        ic.setLayoutParams(new LinearLayout.LayoutParams(ui.dp(84), ui.dp(84)));
        head.addView(ic);
        TextView name = ui.text(s.label, 22, Ui.TEXT, true);
        name.setGravity(Gravity.CENTER);
        name.setPadding(0, ui.dp(10), 0, ui.dp(6));
        head.addView(name);
        if (s.version != null && !s.version.isEmpty()) head.addView(ui.pill("v" + s.version, Ui.PRIMARY));
        TextView det = ui.text(s.filePath != null ? s.detail : s.pkg, 13, Ui.SUB, false);
        det.setGravity(Gravity.CENTER);
        det.setPadding(ui.dp(24), ui.dp(8), ui.dp(24), 0);
        head.addView(det);
        p.addView(head, ui.match());

        // options
        LinearLayout o = ui.card(p);
        o.addView(ui.header("Clone options"));
        final int[] count = {Math.max(1, Math.min(25, st.i("cloneCount", 2)))};
        final TextView countTv = ui.text(String.valueOf(count[0]), 18, Ui.TEXT, true);
        countTv.setGravity(Gravity.CENTER);
        countTv.setMinWidth(ui.dp(40));
        LinearLayout stepper = ui.hbox();
        ImageView minus = ui.icon(R.drawable.ic_remove, Ui.TEXT, 20);
        ImageView plus = ui.icon(R.drawable.ic_add, Ui.TEXT, 20);
        for (ImageView iv : new ImageView[]{minus, plus}) {
            iv.setBackground(ui.round(Ui.ICON_BG, 18));
            iv.setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8));
            iv.setLayoutParams(new LinearLayout.LayoutParams(ui.dp(36), ui.dp(36)));
        }
        stepper.addView(minus);
        stepper.addView(countTv);
        stepper.addView(plus);
        o.addView(ui.row(ui.iconBubble(R.drawable.ic_add), "Number of clones", "1 to 25", stepper, null));
        o.addView(ui.divider());

        final String[] letter = {st.s("cloneLetter", "a")};
        final TextView letterTv = ui.pill(letter[0].toUpperCase(), Ui.PRIMARY);
        o.addView(ui.row(ui.iconBubble(R.drawable.ic_file), "First letter", "Package names end with this letter and the next ones",
                letterTv, new View.OnClickListener() { public void onClick(View v) {
                    final String[] letters = new String[26];
                    for (int i = 0; i < 26; i++) letters[i] = String.valueOf((char) ('a' + i));
                    new AlertDialog.Builder(MainActivity.this).setTitle("First letter")
                            .setItems(letters, new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) {
                                letter[0] = letters[w];
                                st.put("cloneLetter", letter[0]);
                                letterTv.setText(letter[0].toUpperCase());
                            }}).show();
                }}));
        o.addView(ui.divider());

        o.addView(ui.row(ui.iconBubble(R.drawable.ic_person), "Clone name", "Shown under the app icon, e.g. \"Roblox A\"", null, null));
        String defName = s.label == null || s.label.toLowerCase().endsWith(".apk") || s.label.toLowerCase().endsWith(".apks")
                || s.label.toLowerCase().endsWith(".xapk") ? "Roblox" : s.label;
        final EditText cname = ui.field("Roblox", st.s("cloneName_" + (s.pkg == null ? "" : s.pkg), defName));
        o.addView(cname, ui.fieldLp());

        o.addView(ui.divider());
        o.addView(switchRow(R.drawable.ic_window, "Floating windows (like UG Cloner)",
                "Each clone opens as its own small window with a WORST title bar — works on any Android / cloud phone", "floatClones", true));
        o.addView(switchRow(R.drawable.ic_window, "WORST icon on clones",
                "Clones get the W icon with their letter (A, B, C...) so you can tell them apart", "cloneIcon", true));

        final Switch orig = ui.toggle(st.b("installOrig", true));
        if (s.filePath != null) {
            o.addView(ui.divider());
            o.addView(ui.row(ui.iconBubble(R.drawable.ic_save), "Also install the original", "Installs the file unchanged too, if it isn't installed yet", orig, null));
        }

        // preview
        LinearLayout pv = ui.card(p);
        pv.addView(ui.header("Will create"));
        final TextView preview = ui.note("");
        pv.addView(preview);
        final Runnable updatePreview = new Runnable() { public void run() {
            countTv.setText(String.valueOf(count[0]));
            String nm = cname.getText().toString().trim();
            if (s.pkg == null) { preview.setText(count[0] + " clone(s). Package names are shown once the file is read."); return; }
            StringBuilder sb = new StringBuilder();
            for (String n : Cloner.cloneNames(s.pkg, letter[0].charAt(0), count[0])) {
                String L = n.substring(n.length() - 1).toUpperCase();
                sb.append(nm.isEmpty() ? "" : nm + " " + L + "   ").append(n).append('\n');
            }
            preview.setText(sb.toString().trim());
        }};
        minus.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            if (count[0] > 1) { count[0]--; st.put("cloneCount", count[0]); updatePreview.run(); } }});
        plus.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            if (count[0] < 25) { count[0]++; st.put("cloneCount", count[0]); updatePreview.run(); } }});
        cname.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence a, int b, int c, int d) {}
            public void onTextChanged(CharSequence a, int b, int c, int d) {}
            public void afterTextChanged(Editable e) { updatePreview.run(); }
        });
        letterTv.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence a, int b, int c, int d) {}
            public void onTextChanged(CharSequence a, int b, int c, int d) {}
            public void afterTextChanged(Editable e) { updatePreview.run(); }
        });
        updatePreview.run();

        // progress + button
        final ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setVisibility(View.GONE);
        LinearLayout.LayoutParams blp = ui.match();
        blp.setMargins(ui.dp(18), ui.dp(8), ui.dp(18), 0);
        p.addView(bar, blp);
        final TextView stepTv = ui.note("");
        stepTv.setGravity(Gravity.CENTER);
        p.addView(stepTv, ui.match());
        final Button create = ui.primary("Create clones");
        p.addView(create, ui.buttonLp());
        create.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            if (busy) { toast("Busy, please wait…"); return; }
            final String nm = cname.getText().toString().trim();
            if (s.pkg != null) st.put("cloneName_" + s.pkg, nm);
            st.put("installOrig", orig.isChecked());
            makeClones(s, count[0], letter[0].charAt(0), nm, s.filePath != null && orig.isChecked(), bar, stepTv, create);
        }});
        addLog(p);
        setPage(p);
    }

    private void makeClones(final Cloner.Source s, final int count, final char letter, final String name,
                            final boolean installOriginal, final ProgressBar bar, final TextView stepTv, final Button create) {
        busy = true;
        create.setEnabled(false);
        create.setAlpha(0.5f);
        bar.setVisibility(View.VISIBLE);
        bar.setMax(count * 10 + 2);
        bar.setProgress(0);
        Log.add("Preparing " + s.label + " …");
        bg(new Runnable() { public void run() {
            Cloner cl = new Cloner(MainActivity.this);
            int ok = 0, total = 0;
            try {
                step(stepTv, bar, "Reading the app…", 1);
                List<File> apks = cl.collect(s);
                Log.add("Package: " + s.pkg + " (" + apks.size() + " APK file" + (apks.size() > 1 ? "s" : "") + ")");
                if (installOriginal && !cl.isInstalled(s.pkg)) {
                    step(stepTv, bar, "Installing the original…", 2);
                    try { cl.installOriginal(apks); st.put("exec_" + s.pkg, Executors.detect(s.label, s.detail, s.filePath)); Log.add("Installed original " + s.pkg); }
                    catch (Exception e) { Log.add("Original: " + e.getMessage()); }
                }
                List<String> names = Cloner.cloneNames(s.pkg, letter, count);
                total = names.size();
                for (int i = 0; i < names.size(); i++) {
                    String n = names.get(i);
                    String label = name.isEmpty() ? null : name + " " + n.substring(n.length() - 1).toUpperCase();
                    step(stepTv, bar, "Creating clone " + (i + 1) + " of " + names.size() + "  (" + n + ")", 2 + i * 10 + 3);
                    Log.add("Clone " + (i + 1) + "/" + names.size() + ": " + n + (label == null ? "" : " \"" + label + "\""));
                    try {
                        boolean fl = st.b("floatClones", true);
                        st.put("float_" + n, fl);
                        st.put("slot_" + n, i);
                        cl.makeClone(apks, s.pkg, n, label, fl, i);
                        st.put("exec_" + n, Executors.detect(s.label, s.detail, s.filePath));
                        ok++;
                        Log.add("  installed " + n);
                    } catch (Exception e) {
                        Log.add("  FAILED: " + e.getMessage());
                    }
                    step(stepTv, bar, null, 2 + (i + 1) * 10);
                }
                for (File f : apks) f.delete();
            } catch (Throwable e) {
                Log.add("Error: " + e.getMessage());
            }
            final int fOk = ok, fTotal = total;
            Log.add("Done: " + ok + "/" + total + " clones installed. Open each one once, log in, and enter the executor key if it asks.");
            runOnUiThread(new Runnable() { public void run() {
                busy = false;
                create.setEnabled(true);
                create.setAlpha(1f);
                bar.setProgress(bar.getMax());
                stepTv.setText(fTotal == 0 ? "Failed — see the log below." : fOk + " of " + fTotal + " clones installed ✓");
                stepTv.setTextColor(fOk == fTotal && fTotal > 0 ? Ui.GREEN : Ui.RED);
            }});
        }});
    }

    private void step(final TextView tv, final ProgressBar bar, final String text, final int progress) {
        runOnUiThread(new Runnable() { public void run() {
            if (text != null) { tv.setText(text); tv.setTextColor(Ui.SUB); }
            bar.setProgress(progress);
        }});
    }

    // ------------------------------------------------------------------ SCRIPTS

    private List<Scripts.Folder> folders = null;
    private final List<Boolean> folderTicks = new ArrayList<>();

    private LinearLayout scriptsPage() {
        LinearLayout p = newPage();
        final LinearLayout fc = ui.card(p);
        LinearLayout fh = ui.hbox();
        fh.addView(ui.header("Executor folders"), ui.weight(1));
        ImageView rf = ui.icon(R.drawable.ic_refresh, Ui.SUB, 22);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ui.dp(22), ui.dp(22));
        rlp.setMargins(0, 0, ui.dp(18), 0);
        rf.setLayoutParams(rlp);
        fh.addView(rf);
        fc.addView(fh);
        final LinearLayout list = ui.vbox();
        fc.addView(list);
        rf.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { scanFolders(list); } });
        if (folders == null) scanFolders(list); else fillFolders(list);

        LinearLayout ed = ui.card(p);
        ed.addView(ui.header("Write a script"));
        ed.addView(ui.note("Saved into every ticked folder. Scripts in Autoexecute run each time an instance joins a game."));
        final EditText name = ui.field("File name, e.g. farm.lua", st.s("scriptName", ""));
        ed.addView(name, ui.fieldLp());
        final EditText body = ui.field("Paste your script here", st.s("scriptBody", ""));
        body.setSingleLine(false);
        body.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        body.setMinLines(8);
        body.setGravity(Gravity.TOP);
        body.setTypeface(Typeface.MONOSPACE);
        body.setTextSize(12);
        ed.addView(body, ui.fieldLp());
        Button save = ui.primary("Save to ticked folders");
        ed.addView(save, ui.buttonLp());
        save.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            String n = name.getText().toString().trim();
            final String b = body.getText().toString();
            if (n.isEmpty() || n.contains("/")) { toast("Enter a file name"); return; }
            if (!n.contains(".")) n = n + ".lua";
            final String fn = n;
            st.put("scriptName", n);
            st.put("scriptBody", b);
            final List<String> targets = new ArrayList<>();
            if (folders != null) for (int i = 0; i < folders.size(); i++) if (folderTicks.get(i)) targets.add(folders.get(i).path);
            if (targets.isEmpty()) { toast("Tick at least one folder"); return; }
            bg(new Runnable() { public void run() {
                int ok = 0;
                for (String t : targets) {
                    if (Scripts.write(t, fn, b)) { ok++; Log.add("Saved " + fn + " → " + t); }
                    else Log.add("Couldn't save to " + t);
                }
                final int k = ok;
                runOnUiThread(new Runnable() { public void run() { toast("Saved to " + k + " folder(s)"); } });
            }});
        }});
        Button copy = ui.secondary("Copy all Scripts into Autoexecute");
        ed.addView(copy, ui.buttonLp());
        copy.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            final List<Scripts.Folder> fs = folders == null ? new ArrayList<Scripts.Folder>() : new ArrayList<>(folders);
            bg(new Runnable() { public void run() {
                int pairs = 0;
                for (Scripts.Folder a : fs) {
                    if (!a.auto) continue;
                    String parent = a.path.substring(0, a.path.lastIndexOf('/'));
                    for (Scripts.Folder s : fs) {
                        if (s.auto || !s.path.substring(0, s.path.lastIndexOf('/')).equals(parent)) continue;
                        int n = Scripts.copyAll(s.path, a.path);
                        Log.add("Copied " + n + " file(s): " + s.path + " → " + a.path);
                        pairs++;
                    }
                }
                if (pairs == 0) Log.add("No Scripts + Autoexecute folder pair found.");
            }});
        }});
        addLog(p);
        return p;
    }

    private void scanFolders(final LinearLayout list) {
        list.removeAllViews();
        list.addView(ui.note("Searching…"));
        bg(new Runnable() { public void run() {
            final List<Scripts.Folder> f = Scripts.find();
            runOnUiThread(new Runnable() { public void run() {
                folders = f;
                folderTicks.clear();
                for (Scripts.Folder x : f) folderTicks.add(x.auto);
                fillFolders(list);
            }});
        }});
    }

    private void fillFolders(LinearLayout list) {
        list.removeAllViews();
        if (folders == null || folders.isEmpty()) {
            list.addView(ui.note("No executor folders yet. Open your executor once (and join a game) so it creates them, then tap refresh."));
            return;
        }
        for (int i = 0; i < folders.size(); i++) {
            final int idx = i;
            final Scripts.Folder f = folders.get(i);
            CheckBox cb = new CheckBox(this);
            cb.setChecked(folderTicks.get(i));
            cb.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                public void onCheckedChanged(CompoundButton b, boolean on) { folderTicks.set(idx, on); }
            });
            String path = f.path.replaceFirst("^/sdcard/", "");
            String title = path.substring(path.lastIndexOf('/') + 1);
            if (i > 0) list.addView(ui.divider());
            list.addView(ui.row(ui.iconBubble(f.auto ? R.drawable.ic_play : R.drawable.ic_folder),
                    title + (f.auto ? "  (auto-runs)" : ""), path, cb, new View.OnClickListener() {
                        public void onClick(View v) { showFiles(f.path); }
                    }));
        }
    }

    private void showFiles(final String folder) {
        bg(new Runnable() { public void run() {
            final List<String> files = Scripts.files(folder);
            runOnUiThread(new Runnable() { public void run() {
                if (files.isEmpty()) { toast("Folder is empty"); return; }
                ListView lv = new ListView(MainActivity.this);
                lv.setAdapter(new ArrayAdapter<String>(MainActivity.this, android.R.layout.simple_list_item_1, files));
                final AlertDialog d = new AlertDialog.Builder(MainActivity.this).setTitle(folder.replaceFirst("^/sdcard/", ""))
                        .setView(lv).setNegativeButton("Close", null).create();
                lv.setOnItemClickListener(new AdapterView.OnItemClickListener() {
                    public void onItemClick(AdapterView<?> a, View v, int pos, long id) { d.dismiss(); fileActions(folder, files.get(pos)); }
                });
                d.show();
            }});
        }});
    }

    private void fileActions(final String folder, final String file) {
        new AlertDialog.Builder(this).setTitle(file)
                .setItems(new String[]{"Open in editor", "Delete"}, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) {
                            bg(new Runnable() { public void run() {
                                String body = Scripts.read(folder, file);
                                st.put("scriptName", file);
                                st.put("scriptBody", body);
                                runOnUiThread(new Runnable() { public void run() { showTab(2); } });
                            }});
                        } else {
                            bg(new Runnable() { public void run() {
                                Log.add(Scripts.delete(folder, file) ? "Deleted " + file : "Couldn't delete " + file);
                            }});
                        }
                    }
                }).show();
    }

    // ------------------------------------------------------------------ DEVICE

    private DeviceInfo lastInfo;

    private LinearLayout devicePage() {
        final LinearLayout p = newPage();
        if (lastInfo == null) {
            LinearLayout c = ui.card(p);
            LinearLayout r = ui.hbox();
            r.setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(16));
            r.addView(new ProgressBar(this), new LinearLayout.LayoutParams(ui.dp(26), ui.dp(26)));
            r.addView(ui.text("   Checking this device…", 15, Ui.SUB, false));
            c.addView(r);
            scanDevice();
            return p;
        }
        final DeviceInfo d = lastInfo;

        // verdict
        LinearLayout v = ui.card(p);
        v.addView(ui.header("Recommendation"));
        LinearLayout big = ui.hbox();
        big.setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(4));
        TextView num = ui.text(String.valueOf(d.recommended), 54, Ui.TEXT, true);
        num.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
        big.addView(num);
        LinearLayout bt = ui.vbox();
        bt.setPadding(ui.dp(14), 0, 0, 0);
        bt.addView(ui.text("Roblox instances run smoothly", 16, Ui.TEXT, true));
        bt.addView(ui.text("Up to " + d.maximum + " if you accept some lag", 13, Ui.SUB, false));
        big.addView(bt, ui.weight(1));
        v.addView(big);
        v.addView(ui.note("Limited by " + d.limit + ". Memory: " + d.perSource + "."
                + (d.cpuLoad >= 0 && d.robloxRunning > 0 ? " CPU is " + d.cpuLoad + "% busy with " + d.robloxRunning + " running." : "")));
        final int grid = DeviceInfo.gridFor(d.recommended);
        Button apply = ui.primary(st.minSlots() == grid ? "Windows per screen is set to " + grid + " ✓" : "Use " + grid + " windows per screen");
        apply.setOnClickListener(new View.OnClickListener() { public void onClick(View x) {
            st.put("minSlots", grid);
            toast("Windows per screen: " + grid);
            showTab(3);
        }});
        v.addView(apply, ui.buttonLp());

        // breakdown
        LinearLayout lim = ui.card(p);
        lim.addView(ui.header("What each part allows"));
        lim.addView(limitRow(R.drawable.ic_memory, "Memory", d.byRam, DeviceInfo.gb(d.ramTotalMb) + " total, about "
                + DeviceInfo.gb(d.perInstanceMb) + " per instance"));
        lim.addView(ui.divider());
        lim.addView(limitRow(R.drawable.ic_chip, "Processor", d.byCpu, d.cores + " cores" + (d.maxGhz > 0 ? String.format(" at %.1f GHz", d.maxGhz) : "")
                + (d.cpuLoad >= 0 ? "  ·  " + d.cpuLoad + "% busy now" : "")));
        lim.addView(ui.divider());
        int ov = st.i("perInstanceMb", 0);
        final TextView ovTv = ui.pill(ov == 0 ? "Auto" : DeviceInfo.gb(ov), Ui.PRIMARY);
        lim.addView(ui.row(ui.iconBubble(R.drawable.ic_tune), "Memory per instance", "Auto = measured on this device. Set it yourself if you know better",
                ovTv, new View.OnClickListener() { public void onClick(View x) {
                    final String[] o = {"Auto (measure)", "1 GB", "1.5 GB", "2 GB", "2.5 GB", "3 GB"};
                    final int[] vals = {0, 1024, 1536, 2048, 2560, 3072};
                    new AlertDialog.Builder(MainActivity.this).setTitle("Memory per instance")
                            .setItems(o, new DialogInterface.OnClickListener() { public void onClick(DialogInterface dd, int w) {
                                st.put("perInstanceMb", vals[w]);
                                lastInfo = null;
                                showTab(3);
                            }}).show();
                }}));
        lim.addView(ui.divider());
        lim.addView(limitRow(R.drawable.ic_storage, "Storage for new clones", d.byStorage, DeviceInfo.gb(d.storageFreeMb) + " free, about "
                + DeviceInfo.CLONE_SIZE_MB + " MB per clone"));

        // specs
        LinearLayout sp = ui.card(p);
        sp.addView(ui.header("Specs"));
        sp.addView(ui.row(ui.iconBubble(R.drawable.ic_phone), d.model, d.android, null, null));
        sp.addView(ui.divider());
        sp.addView(ui.row(ui.iconBubble(R.drawable.ic_chip), "CPU", d.cpu, null, null));
        sp.addView(ui.divider());
        sp.addView(ui.row(ui.iconBubble(R.drawable.ic_game), "GPU", d.gpu, null, null));
        sp.addView(ui.divider());
        sp.addView(ui.row(ui.iconBubble(R.drawable.ic_memory), "RAM", DeviceInfo.gb(d.ramTotalMb) + " total  ·  " + DeviceInfo.gb(d.ramFreeMb) + " free now", null, null));
        sp.addView(ui.divider());
        sp.addView(ui.row(ui.iconBubble(R.drawable.ic_storage), "Storage", DeviceInfo.gb(d.storageFreeMb) + " free of " + DeviceInfo.gb(d.storageTotalMb), null, null));
        sp.addView(ui.divider());
        sp.addView(ui.row(ui.iconBubble(R.drawable.ic_window), "Screen", d.screen, null, null));
        sp.addView(ui.divider());
        sp.addView(ui.row(ui.iconBubble(R.drawable.ic_info), "Root  ·  Freeform windows",
                (d.root ? "Root OK" : "NO ROOT") + "  ·  " + (d.freeform ? "Freeform on" : "Freeform off"), null, null));
        sp.addView(ui.divider());
        sp.addView(ui.row(ui.iconBubble(R.drawable.ic_copy), "Roblox apps", d.robloxApps + " installed  ·  " + d.robloxRunning + " running", null, null));
        if (!d.perApp.isEmpty()) {
            StringBuilder sb = new StringBuilder("Memory used right now:\n");
            for (String a2 : d.perApp) sb.append("• ").append(a2).append('\n');
            sp.addView(ui.note(sb.toString().trim()));
        }

        // advice
        LinearLayout ad = ui.card(p);
        ad.addView(ui.header("Advice"));
        for (String tip : advice(d)) ad.addView(ui.note("•  " + tip));

        Button again = ui.secondary("Check again");
        again.setOnClickListener(new View.OnClickListener() { public void onClick(View x) { lastInfo = null; showTab(3); } });
        p.addView(again, ui.buttonLp());
        p.addView(ui.note("These are estimates. Heavy games, high graphics and some executors use more memory. If instances crash or lag, run fewer."), ui.match());
        return p;
    }

    private View limitRow(int icon, String title, int n, String detail) {
        TextView pill = ui.pill(n + " max", n <= lastInfo.recommended ? Ui.RED : Ui.TEXT);
        return ui.row(ui.iconBubble(icon), title, detail, pill, null);
    }

    private void scanDevice() {
        bg(new Runnable() { public void run() {
            final DeviceInfo d = DeviceInfo.collect(MainActivity.this);
            runOnUiThread(new Runnable() { public void run() {
                lastInfo = d;
                if (!inDetail && tab == 3) showTab(3);
            }});
        }});
    }

    private List<String> advice(DeviceInfo d) {
        List<String> t = new ArrayList<>();
        if (!d.root) t.add("No root. Turn on root (cloud phone settings, or Magisk/KernelSU) — cloning, windows and rejoin all need it.");
        if (d.sdk < 35) t.add("This is Android " + Build.VERSION.RELEASE + ". The app is made for Android 15 — use an Android 15 cloud phone so the windows show properly.");
        if (d.root && !d.freeform) t.add("Freeform windows are off. Turn on \"Always open Roblox in a window\" in Settings and reboot once.");
        if (d.robloxApps > d.recommended)
            t.add("You have " + d.robloxApps + " Roblox apps but this device runs about " + d.recommended + " smoothly. Untick the extra ones on Home.");
        else if (d.robloxApps < d.recommended)
            t.add("You can add " + (d.recommended - d.robloxApps) + " more clone(s) on the Clone tab.");
        if (d.ramTotalMb < 4096)
            t.add("Less than 4 GB of RAM: set Roblox graphics to the lowest (in-game Settings › Graphics Mode: Manual, quality 1) in every clone.");
        else
            t.add("Set graphics to the lowest in every clone — it lowers memory use and heat, so more instances fit.");
        if (d.measuredPerInstanceMb > 1200)
            t.add("Each instance uses over 1.2 GB here — this game (or executor) is heavy. Lower graphics or run fewer.");
        if (d.storageFreeMb < 2048) t.add("Storage is low (" + DeviceInfo.gb(d.storageFreeMb) + " free). Delete old APK files before making more clones.");
        if (d.cores <= 4) t.add("Only " + d.cores + " CPU cores. Raise \"Seconds between launches\" to 25 so instances don't all load at once.");
        t.add("Close other apps (browser, YouTube) while farming — every app takes memory away from Roblox.");
        t.add("On Redfinger / UGPhone, higher plans have more RAM and run more instances. RAM matters most.");
        return t;
    }

    // ------------------------------------------------------------------ SETTINGS

    private LinearLayout settingsPage() {
        LinearLayout p = newPage();

        LinearLayout win = ui.card(p);
        win.addView(ui.header("Windows"));
        final Switch keep = ui.toggle(st.b("keeper", true));
        keep.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean on) {
                st.put("keeper", on);
                KeeperService.sync(MainActivity.this);
                if (on) bg(new Runnable() { public void run() {
                    if (!Device.freeformOn() && Device.enableFreeform())
                        runOnUiThread(new Runnable() { public void run() { askReboot("Freeform windows were just turned on. Reboot once so they work."); } });
                }});
            }
        });
        win.addView(ui.row(ui.iconBubble(R.drawable.ic_window), "Always open Roblox in a window",
                "Roblox apps opened from anywhere become resizable windows, placed in a grid", keep, null));
        win.addView(ui.divider());
        final Switch lock = ui.toggle(st.b("lockWindows", true));
        lock.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean on) { st.put("lockWindows", on); }
        });
        win.addView(ui.row(ui.iconBubble(R.drawable.ic_window), "Lock window size & position",
                "Puts windows back in their spot if they grow or move (Roblox does this when joining a game)", lock, null));
        win.addView(ui.divider());
        final TextView slotsTv = ui.pill(String.valueOf(st.minSlots()), Ui.PRIMARY);
        win.addView(ui.row(ui.iconBubble(R.drawable.ic_window), "Windows per screen",
                "Window size: the screen is split into at least this many spots, even with fewer instances", slotsTv,
                new View.OnClickListener() { public void onClick(View v) {
                    final String[] opts = {"1 (one big window)", "2", "4", "6", "9", "12", "16"};
                    final int[] vals = {1, 2, 4, 6, 9, 12, 16};
                    new AlertDialog.Builder(MainActivity.this).setTitle("Windows per screen")
                            .setItems(opts, new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) {
                                st.put("minSlots", vals[w]);
                                slotsTv.setText(String.valueOf(vals[w]));
                            }}).show();
                }}));
        win.addView(ui.divider());
        final Switch land = ui.toggle(st.landscape());
        land.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean on) { st.put("landscape", on); }
        });
        win.addView(ui.row(ui.iconBubble(R.drawable.ic_window), "Landscape screen", "Turns the screen sideways when starting", land, null));
        win.addView(ui.divider());
        int dm = st.densityMode();
        final TextView densTv = ui.pill(dm == 0 ? "Auto" : dm < 0 ? "Off" : String.valueOf(dm), Ui.PRIMARY);
        win.addView(ui.row(ui.iconBubble(R.drawable.ic_search), "Screen size (density)", "Auto shrinks the screen so every window fits",
                densTv, new View.OnClickListener() { public void onClick(View v) {
                    final String[] opts = {"Auto", "Off (don't change)", "120", "160", "200", "240", "280", "320"};
                    new AlertDialog.Builder(MainActivity.this).setTitle("Screen density")
                            .setItems(opts, new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) {
                                int val = w == 0 ? 0 : w == 1 ? -1 : Integer.parseInt(opts[w]);
                                st.put("density", val);
                                densTv.setText(val == 0 ? "Auto" : val < 0 ? "Off" : String.valueOf(val));
                            }}).show();
                }}));

        LinearLayout rj = ui.card(p);
        rj.addView(ui.header("Auto rejoin"));
        final Switch pres = ui.toggle(st.presenceCheck());
        pres.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean on) { st.put("presence", on); }
        });
        rj.addView(ui.row(ui.iconBubble(R.drawable.ic_person), "Rejoin when kicked / disconnected", "Checks if each account is still in a game", pres, null));
        rj.addView(ui.divider());
        final Switch frz = ui.toggle(st.freezeCheck());
        frz.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean on) { st.put("freeze", on); }
        });
        rj.addView(ui.row(ui.iconBubble(R.drawable.ic_refresh), "Restart frozen apps", "If an app stops responding for 2 minutes", frz, null));
        rj.addView(ui.divider());
        rj.addView(switchRow(R.drawable.ic_play, "Instant kick detection", "Reads Roblox's log and rejoins seconds after a kick or disconnect", "instantKick", true));
        rj.addView(ui.divider());
        final String[] hrsOpts = {"Off", "1 hour", "2 hours", "3 hours", "4 hours", "6 hours", "8 hours", "12 hours", "24 hours"};
        final int[] hrsVals = {0, 1, 2, 3, 4, 6, 8, 12, 24};
        int hv = st.i("restartHours", 0);
        final TextView hrsTv = ui.pill(hv == 0 ? "Off" : "Every " + hv + "h", Ui.PRIMARY);
        rj.addView(ui.row(ui.iconBubble(R.drawable.ic_refresh), "Scheduled restart", "Restarts each instance after this long, one at a time (stops slowdowns)",
                hrsTv, new View.OnClickListener() { public void onClick(View v) {
                    new AlertDialog.Builder(MainActivity.this).setTitle("Restart each instance every…")
                            .setItems(hrsOpts, new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) {
                                st.put("restartHours", hrsVals[w]);
                                hrsTv.setText(hrsVals[w] == 0 ? "Off" : "Every " + hrsVals[w] + "h");
                            }}).show();
                }}));
        rj.addView(ui.divider());
        rj.addView(switchRow(R.drawable.ic_phone, "Start automatically after reboot", "When the device restarts, waits 1 minute then presses Start for you", "autoStart", false));
        rj.addView(ui.divider());
        rj.addView(numberRow("Seconds between launches", "launchDelay", st.launchDelay(), 3));
        rj.addView(ui.divider());
        rj.addView(numberRow("Check every (seconds)", "checkEvery", st.checkEvery(), 5));
        rj.addView(ui.divider());
        rj.addView(numberRow("Wait after launch (seconds)", "grace", st.grace(), 30));

        LinearLayout perf = ui.card(p);
        perf.addView(ui.header("Performance"));
        perf.addView(switchRow(R.drawable.ic_game, "Lowest graphics on start", "Sets every instance to graphics level 1 before launching — less memory and heat", "lowGfx", true));
        perf.addView(ui.divider());
        perf.addView(switchRow(R.drawable.ic_memory, "Free memory before start", "Closes background apps so Roblox gets more RAM", "freeMem", true));

        LinearLayout au = ui.card(p);
        au.addView(ui.header("Executor auto-update"));
        Switch auSw = ui.toggle(st.b("autoUpdate", true));
        auSw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean on) { st.put("autoUpdate", on); KeeperService.sync(MainActivity.this); }
        });
        au.addView(ui.row(ui.iconBubble(R.drawable.ic_refresh), "Update executors automatically",
                "Checks the download sites. When there's a new version it stops, re-clones every app and starts again", auSw, null));
        au.addView(ui.divider());
        final String[] ivOpts = {"Every 30 minutes", "Every hour", "Every 3 hours", "Every 6 hours", "Every 12 hours"};
        final int[] ivVals = {30, 60, 180, 360, 720};
        int iv = st.i("updateEvery", 60);
        final TextView ivTv = ui.pill(iv < 60 ? iv + " min" : (iv / 60) + "h", Ui.PRIMARY);
        au.addView(ui.row(ui.iconBubble(R.drawable.ic_search), "Check every", null, ivTv, new View.OnClickListener() {
            public void onClick(View v) {
                new AlertDialog.Builder(MainActivity.this).setTitle("Check for updates")
                        .setItems(ivOpts, new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) {
                            st.put("updateEvery", ivVals[w]);
                            ivTv.setText(ivVals[w] < 60 ? ivVals[w] + " min" : (ivVals[w] / 60) + "h");
                        }}).show();
            }}));
        au.addView(ui.divider());
        final TextView meTv = ui.pill(AutoUpdater.mainExec(this), Ui.PRIMARY);
        au.addView(ui.row(ui.iconBubble(R.drawable.ic_copy), "Executor of older clones",
                "Clones made before v1.7 don't remember their executor — they're treated as this one", meTv,
                new View.OnClickListener() { public void onClick(View v) {
                    final String[] ex = Executors.names().toArray(new String[0]);
                    new AlertDialog.Builder(MainActivity.this).setTitle("Older clones use…")
                            .setItems(ex, new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) {
                                st.put("mainExec", ex[w]);
                                meTv.setText(ex[w]);
                            }}).show();
                }}));
        au.addView(ui.divider());
        au.addView(switchRow(R.drawable.ic_copy, "Replace clones made by other tools",
                "Clones made with MT Manager / other cloners can't be updated directly. WORST backs up their login, reinstalls them and puts the login back", "replaceForeign", true));
        Button now = ui.secondary("Check for updates now");
        now.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            if (AutoUpdater.isBusy()) { toast("Already checking…"); return; }
            toast("Checking… see the Activity log");
            AutoUpdater.checkAll(MainActivity.this, true);
        }});
        au.addView(now, ui.buttonLp());

        LinearLayout dc = ui.card(p);
        dc.addView(ui.header("Discord"));
        final String hook = st.s("webhook", "");
        final TextView hookTv = ui.pill(hook.isEmpty() ? "Not set" : "Set ✓", hook.isEmpty() ? Ui.SUB : Ui.GREEN);
        dc.addView(ui.row(ui.iconBubble(R.drawable.ic_shortcut), "Webhook link", "Discord channel › Edit › Integrations › Webhooks › Copy URL",
                hookTv, new View.OnClickListener() { public void onClick(View v) {
                    LinearLayout l = ui.vbox();
                    l.setPadding(0, ui.dp(8), 0, 0);
                    final EditText e = ui.field("https://discord.com/api/webhooks/…", st.s("webhook", ""));
                    l.addView(e, ui.fieldLp());
                    l.addView(ui.note("Device name shown in messages:"));
                    final EditText dn = ui.field(Build.MODEL, st.s("deviceName", ""));
                    l.addView(dn, ui.fieldLp());
                    new AlertDialog.Builder(MainActivity.this).setTitle("Discord webhook").setView(l)
                            .setPositiveButton("Save", new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) {
                                st.put("webhook", e.getText().toString().trim());
                                String n = dn.getText().toString().trim();
                                st.put("deviceName", n.isEmpty() ? Build.MODEL : n);
                                showTab(4);
                            }})
                            .setNeutralButton("Send test", new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) {
                                final String u = e.getText().toString().trim();
                                st.put("webhook", u);
                                bg(new Runnable() { public void run() {
                                    final String err = Notify.post(u, "✅ WORST - AUTO REJOIN is connected.");
                                    runOnUiThread(new Runnable() { public void run() { toast(err == null ? "Sent! Check your Discord channel" : "Failed: " + err); } });
                                }});
                            }})
                            .setNegativeButton("Cancel", null).show();
                }}));
        dc.addView(ui.divider());
        dc.addView(switchRow(R.drawable.ic_refresh, "Message on every rejoin", "Says which account was kicked / crashed and that it's rejoining", "discordOn", true));
        dc.addView(ui.divider());
        dc.addView(switchRow(R.drawable.ic_info, "Status every 30 minutes", "Each account's state, uptime and number of rejoins", "discordSummary", false));

        LinearLayout dev = ui.card(p);
        dev.addView(ui.header("Device"));
        final TextView info = ui.note("Checking…");
        dev.addView(info);
        bg(new Runnable() { public void run() {
            final String s = "Root: " + (Root.available() ? "yes" : "NO") + "\n"
                    + "Android " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")\n"
                    + "Freeform windows: " + (Device.freeformOn() ? "on" : "off") + "\n"
                    + Root.out("wm size").replace("\n", ", ") + "\n" + Root.out("wm density").replace("\n", ", ");
            runOnUiThread(new Runnable() { public void run() { info.setText(s); } });
        }});
        Button ff = ui.secondary("Turn on freeform windows");
        ff.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            bg(new Runnable() { public void run() {
                final boolean changed = Device.enableFreeform();
                runOnUiThread(new Runnable() { public void run() {
                    if (changed) askReboot("Freeform windows turned on. Reboot now?"); else toast("Already on");
                }});
            }});
        }});
        dev.addView(ff, ui.buttonLp());
        Button reset = ui.secondary("Reset screen size & rotation");
        reset.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            bg(new Runnable() { public void run() { Device.resetScreen(); Log.add("Screen reset."); } });
        }});
        dev.addView(reset, ui.buttonLp());
        Button reboot = ui.secondary("Reboot device");
        reboot.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { askReboot("Reboot now?"); } });
        dev.addView(reboot, ui.buttonLp());

        TextView ver = ui.note("WORST - AUTO REJOIN  v" + versionName());
        ver.setGravity(Gravity.CENTER);
        p.addView(ver, ui.match());
        return p;
    }

    private View switchRow(int icon, String title, String sub, final String key, boolean def) {
        Switch sw = ui.toggle(st.b(key, def));
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean on) { st.put(key, on); }
        });
        return ui.row(ui.iconBubble(icon), title, sub, sw, null);
    }

    private View numberRow(String label, final String key, int value, final int min) {
        final TextView tv = ui.pill(String.valueOf(value), Ui.PRIMARY);
        return ui.row(null, label, null, tv, new View.OnClickListener() { public void onClick(View v) {
            final EditText e = ui.field("", tv.getText().toString());
            e.setInputType(InputType.TYPE_CLASS_NUMBER);
            LinearLayout l = ui.vbox();
            l.setPadding(0, ui.dp(12), 0, 0);
            l.addView(e, ui.fieldLp());
            new AlertDialog.Builder(MainActivity.this).setTitle(label).setView(l)
                    .setPositiveButton("Save", new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) {
                        try {
                            int v2 = Math.max(min, Integer.parseInt(e.getText().toString().trim()));
                            st.put(key, v2);
                            tv.setText(String.valueOf(v2));
                        } catch (Exception ignored) {}
                    }}).setNegativeButton("Cancel", null).show();
        }});
    }


    private void askReboot(String msg) {
        new AlertDialog.Builder(this).setMessage(msg)
                .setPositiveButton("Reboot", new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) {
                    bg(new Runnable() { public void run() { Root.run("svc power reboot || reboot"); } });
                }}).setNegativeButton("Later", null).show();
    }

    private String versionName() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception e) { return ""; }
    }

    // ------------------------------------------------------------------ shared

    private void addLog(LinearLayout p) {
        LinearLayout c = ui.card(p);
        LinearLayout hdr = ui.hbox();
        hdr.addView(ui.header("Activity log"), ui.weight(1));
        TextView save = ui.text("Save", 14, Ui.SUB, true);
        save.setPadding(ui.dp(12), ui.dp(8), ui.dp(4), ui.dp(8));
        save.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { exportLog(); } });
        hdr.addView(save);
        TextView clear = ui.text("Clear", 14, Ui.SUB, true);
        clear.setPadding(ui.dp(12), ui.dp(8), ui.dp(18), ui.dp(8));
        clear.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { Log.clear(); } });
        hdr.addView(clear);
        c.addView(hdr);
        logView = ui.text("", 11, 0xffb8b8bd, false);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        logView.setBackground(ui.round(Ui.CHIP, 12));
        logView.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10));
        LinearLayout.LayoutParams lp = ui.match();
        lp.setMargins(ui.dp(14), ui.dp(2), ui.dp(14), ui.dp(8));
        c.addView(logView, lp);
        logVersion = -1;
    }

    private void exportLog() {
        final String text = Log.text(400);
        bg(new Runnable() { public void run() {
            String name = "WORST-log-" + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(new java.util.Date()) + ".txt";
            String path = "/sdcard/Download/" + name;
            boolean ok;
            try { ok = Root.writeAsRoot(path, text.getBytes("UTF-8")); } catch (Exception e) { ok = false; }
            final String msg = ok ? "Saved to Download/" + name : "Couldn't save the log";
            runOnUiThread(new Runnable() { public void run() { toast(msg); } });
        }});
    }

    private void startFg(Intent i) {
        if (Build.VERSION.SDK_INT >= 26) {
            try { Context.class.getMethod("startForegroundService", Intent.class).invoke(this, i); return; }
            catch (Exception ignored) {}
        }
        startService(i);
    }

    private TextWatcher saver(final String key) {
        return new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable e) { st.put(key, e.toString().trim()); }
        };
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    private void bg(Runnable r) { new Thread(r).start(); }

    /** Draws a picture "center-crop" (fills the box without stretching). */
    private static final class CropDrawable extends Drawable {
        private final Bitmap bmp;
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG);
        CropDrawable(Bitmap b) { bmp = b; }
        @Override public void draw(Canvas c) {
            android.graphics.Rect b = getBounds();
            if (b.width() == 0 || b.height() == 0) return;
            float scale = Math.max((float) b.width() / bmp.getWidth(), (float) b.height() / bmp.getHeight());
            float w = b.width() / scale, h = b.height() / scale;
            float x = (bmp.getWidth() - w) / 2f, y = (bmp.getHeight() - h) / 2f;
            c.drawColor(0xff000000);
            c.drawBitmap(bmp, new android.graphics.Rect((int) x, (int) y, (int) (x + w), (int) (y + h)), b, paint);
        }
        @Override public void setAlpha(int a) { paint.setAlpha(a); }
        @Override public void setColorFilter(android.graphics.ColorFilter f) { paint.setColorFilter(f); }
        @Override public int getOpacity() { return android.graphics.PixelFormat.OPAQUE; }
    }
}
