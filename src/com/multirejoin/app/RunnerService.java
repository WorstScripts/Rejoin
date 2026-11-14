package com.multirejoin.app;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Launches all instances, then keeps watching them and relaunches any that crash, freeze or leave the game. */
public class RunnerService extends Service {
    public static final String ACTION_START = "start";
    public static final String ACTION_STOP = "stop";
    private static final String CHANNEL = "runner";

    private static volatile boolean running = false;
    private static volatile int gen = 0;
    private static volatile String status = "Stopped";
    /** Live state per app for the Home screen: "Launching", "In game", "Not in game", ... */
    public static final java.util.concurrent.ConcurrentHashMap<String, String> state = new java.util.concurrent.ConcurrentHashMap<>();
    /** When each app was last (re)launched, and how many times it was rejoined this run. */
    public static final java.util.concurrent.ConcurrentHashMap<String, Long> startedAt = new java.util.concurrent.ConcurrentHashMap<>();
    public static final java.util.concurrent.ConcurrentHashMap<String, Integer> rejoins = new java.util.concurrent.ConcurrentHashMap<>();
    public static final String EXTRA_BOOT = "boot";
    private volatile boolean bootStart = false;
    private volatile Thread worker;
    private PowerManager.WakeLock wakeLock;

    public static boolean isRunning() { return running; }
    public static String status() { return status; }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() { super.onCreate(); Device.ctx = getApplicationContext(); }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(a)) {
            stopAll();
            return START_NOT_STICKY;
        }
        if (running) return START_STICKY;
        running = true;
        status = "Starting…";
        final int g = ++gen;
        state.clear();
        startForeground(1, notification("Starting..."));
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "multirejoin:run");
        wakeLock.acquire();
        if (worker != null) worker.interrupt();
        worker = new Thread(new Runnable() { public void run() { work(g); } }, "runner");
        worker.start();
        return START_STICKY;
    }

    private void stopAll() {
        running = false;
        gen++;
        status = "Stopped";
        state.clear();
        if (worker != null) worker.interrupt();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        stopForeground(true);
        stopSelf();
        Log.add("Stopped.");
        Notify.send(this, "⏹ Stopped.");
    }

    @Override public void onDestroy() {
        running = false;
        if (worker != null) worker.interrupt();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }

    private Notification notification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                Class<?> ch = Class.forName("android.app.NotificationChannel");
                Object channel = ch.getConstructor(String.class, CharSequence.class, int.class)
                        .newInstance(CHANNEL, "Running", 2);
                NotificationManager.class.getMethod("createNotificationChannel", ch).invoke(nm, channel);
                b = Notification.Builder.class.getConstructor(Context.class, String.class).newInstance(this, CHANNEL);
            } catch (Exception e) {
                b = new Notification.Builder(this);
            }
        } else {
            b = new Notification.Builder(this);
        }
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | 0x04000000);
        Intent stop = new Intent(this, RunnerService.class).setAction(ACTION_STOP);
        PendingIntent ps = PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_UPDATE_CURRENT | 0x04000000);
        b.setContentTitle("WORST - AUTO REJOIN").setContentText(text).setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true).setContentIntent(pi).addAction(android.R.drawable.ic_delete, "Stop", ps);
        return b.build();
    }

    private void setStatus(String s) {
        if (!alive()) return;
        status = s;
        try {
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(1, notification(s));
        } catch (Exception ignored) {}
    }

    private void sleep(long ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (alive() && System.currentTimeMillis() < end) Thread.sleep(Math.min(500, end - System.currentTimeMillis() + 1));
        if (!alive()) throw new InterruptedException();
    }

    /** True only for the current run's thread, so an old run can't interfere with a new one. */
    private boolean alive() { return running && Thread.currentThread() == worker; }

    // -------------------------------------------------------------------

    private Store st;
    private List<String> pkgs;
    private Device.Layout layout;
    private boolean freeform;
    private final Map<String, Long> launchedAt = new HashMap<>();
    private final Map<String, Integer> strikes = new HashMap<>();
    private final Map<String, Long> lastCpu = new HashMap<>();
    private final Map<String, Long> cpuSince = new HashMap<>();
    private final Map<Long, String> cookies = new HashMap<>();
    private final Map<String, Long> userIds = new HashMap<>();
    private final List<String> noAccount = new ArrayList<>();
    private final java.util.concurrent.ConcurrentLinkedQueue<String[]> kicks = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private Process logcat;
    private long lastSummary = 0;
    private long availBeforeMb = 0;
    private boolean measured = false;

    private void work(int g) {
        try {
            st = new Store(this);
            if (bootStart) {
                Log.add("Device restarted — auto start in 60 seconds…");
                setStatus("Auto start after reboot…");
                sleep(60000);
            }
            if (!Root.available()) { Log.add("No root access. Give this app root (superuser) and try again."); return; }
            pkgs = new ArrayList<>();
            for (String p : Roblox.installedApps(this)) if (st.enabled(p)) pkgs.add(p);
            if (pkgs.isEmpty()) { Log.add("No Roblox apps selected. Tick at least one on the Run tab."); return; }
            Log.add("Starting " + pkgs.size() + " instance(s): " + pkgs);

            // screen
            Device.keepAwake();
            int gridApps = 0;
            for (String p : pkgs) if (!st.b("float_" + p, false)) gridApps++;
            freeform = gridApps > 0 && (gridApps > 1 || Device.freeformOn());
            if (gridApps > 1 && Device.enableFreeform()) {
                Log.add("Freeform windows were just turned on. REBOOT the device once (Setup tab > Reboot), then press Start again.");
                setStatus("Reboot needed");
                return;
            }
            if (gridApps > 0 && st.landscape()) Device.forceLandscape();
            // only floating clones: no rotation is forced, so plan the grid for the screen as it is now
            boolean land = gridApps > 0 ? st.landscape() : Device.currentSize()[0] > Device.currentSize()[1];
            layout = Device.layout(st.gridSlots(pkgs.size()), land, st.densityMode());
            if (gridApps > 0) Device.applyDensity(st.densityMode(), layout.density);
            Log.add("Screen " + layout.screenW + "x" + layout.screenH + ", grid " + layout.cols + "x" + layout.rows
                    + ", window " + layout.cellW + "x" + layout.cellH + ", density " + layout.density);
            sleep(1500);

            // accounts
            for (String p : pkgs) {
                Roblox.Found found = Roblox.detect(this, p);
                long id = 0;
                if (found != null) {
                    id = found.account.id;
                    cookies.put(id, found.cookie);
                    st.setUser(p, found.account.name);
                    st.setUserId(p, id);
                } else {
                    Log.add(p + ": account check — " + Roblox.lastReason);
                }
                if (id == 0) {
                    id = st.userId(p);
                    if (id == 0 && !st.user(p).isEmpty()) {
                        id = Roblox.userIdOf(st.user(p));
                        if (id != 0) st.setUserId(p, id);
                    }
                }
                userIds.put(p, id);
                if (id == 0 && st.user(p).isEmpty()) {
                    // nobody is logged in on this app: switch it off so it doesn't sit on the login screen
                    st.setEnabled(p, false);
                    noAccount.add(p);
                    Log.add(p + ": no account logged in — turned it off. Open it, log in, then turn it back on.");
                    continue;
                }
                Log.add(p + " → @" + st.user(p) + (id != 0 ? " #" + id : ""));
            }
            pkgs.removeAll(noAccount);
            if (pkgs.isEmpty()) {
                Log.add("None of the ticked apps has an account logged in. Open each app once and log in.");
                setStatus("No logged-in accounts");
                return;
            }

            // launch all
            for (String p : pkgs) Device.forceStop(p);
            if (st.b("freeMem", true)) {
                Root.run("am kill-all");
                Log.add("Freed memory (closed background apps).");
            }
            sleep(2000);
            availBeforeMb = availMb();
            for (int i = 0; i < pkgs.size(); i++) {
                String p = pkgs.get(i);
                setStatus("Launching " + (i + 1) + "/" + pkgs.size());
                launch(p, i, null);
                sleep(st.launchDelay() * 1000L);
                placeAll();
            }
            sleep(5000);
            placeAll();
            setStatus("Watching " + pkgs.size() + " instance(s)");
            Log.add("All launched. Watching for crashes, kicks and disconnects.");
            Notify.send(this, "▶ Started " + pkgs.size() + " instance(s).");
            if (st.b("instantKick", true)) startLogWatcher();

            // watchdog loop: kick events are handled within ~1 s, full checks every "check every" seconds
            long nextCheck = 0;
            while (alive()) {
                handleKicks();
                if (System.currentTimeMillis() >= nextCheck) {
                    check();
                    if (freeform && !KeeperService.isOn()) placeAll();
                    summary();
                    nextCheck = System.currentTimeMillis() + st.checkEvery() * 1000L;
                }
                sleep(1000);
            }
        } catch (InterruptedException ignored) {
        } catch (Exception e) {
            Log.add("Error: " + e);
        } finally {
            stopLogWatcher();
            // only the current run may shut the service down
            if (running && gen == g) {
                running = false;
                status = "Stopped";
                state.clear();
                stopForeground(true);
                stopSelf();
            }
        }
    }

    private String uriFor(String p) {
        String u = Roblox.toUri(st.link(p));
        if (u == null) u = Roblox.toUri(st.gameLink());
        return u;
    }

    /** reason == null: first launch. Otherwise a relaunch, and the reason is logged / sent to Discord. */
    private void launch(String p, int index, String reason) throws InterruptedException {
        boolean restart = reason != null;
        String uri = uriFor(p);
        if (uri == null) { Log.add("No game link set. Put a Place ID or game link on the Home tab."); return; }
        String who = st.user(p).isEmpty() ? p : st.user(p) + " (" + p + ")";
        if (restart) {
            int n = (rejoins.containsKey(p) ? rejoins.get(p) : 0) + 1;
            rejoins.put(p, n);
            Log.add("Rejoining " + who + " — " + reason);
            Notify.send(this, "🔁 **" + who + "** " + reason + " → rejoining (rejoin #" + n + ")");
        } else {
            Log.add("Launching " + who);
        }
        Device.forceStop(p);
        if (st.b("lowGfx", true)) Device.lowGraphics(p);
        sleep(restart ? 2000 : 500);
        state.put(p, "Launching");
        boolean floating = st.b("float_" + p, false);
        // floating clones get their spot in the same grid as every other instance, so nothing overlaps
        if (floating && layout != null && index >= 0 && index < layout.rects.size()) Device.writeFloatSpot(p, layout.rects.get(index));
        String err = Device.launch(this, p, uri, freeform && !floating);
        if (err != null) {
            Log.add(p + ": Android refused to open it — " + err);
            state.put(p, "Couldn't open");
        }
        launchedAt.put(p, System.currentTimeMillis());
        startedAt.put(p, System.currentTimeMillis());
        strikes.put(p, 0);
        cpuSince.remove(p);
        lastCpu.remove(p);
        if (freeform && !floating) {
            sleep(6000);
            Device.place(p, layout.rects.get(index));
            sleep(1000);
            Log.add(Device.windowDiag(p));
        }
        else sleep(4000);
        if (Device.pid(p) == null) Log.add(p + ": still not running a few seconds after opening — check the log above.");
    }

    // ---------------- instant kick detection (reads Roblox's own log lines) ----------------

    private static final java.util.regex.Pattern REASON = java.util.regex.Pattern.compile(
            "(?:disconnect with reason|Reason)\\s*:?\\s*(\\d{3})|Error Code:?\\s*(\\d{3})", java.util.regex.Pattern.CASE_INSENSITIVE);
    /** Disconnect codes that always mean "you are out of the game". */
    private static final java.util.Set<String> BAD = new java.util.HashSet<>(java.util.Arrays.asList(
            "260", "261", "262", "264", "266", "267", "268", "271", "272", "274", "275", "277", "278", "279",
            "280", "282", "292", "517", "523", "524", "529"));
    private final Map<String, Long> suspectSince = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, String> pidPkg = new java.util.concurrent.ConcurrentHashMap<>();

    private void startLogWatcher() {
        final Thread owner = Thread.currentThread();
        Thread t = new Thread(new Runnable() { public void run() {
            try {
                String su = Root.su();
                logcat = new ProcessBuilder(su, "-c", "logcat -v threadtime -T 1 Roblox:V roblox:V *:S").start();
                logcat.getOutputStream().close();
                java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(logcat.getInputStream()));
                String line;
                Log.add("Instant kick detection is on.");
                while (running && worker == owner && (line = r.readLine()) != null) onLogLine(line);
            } catch (Exception e) {
                if (running) Log.add("Kick detection stopped: " + e.getMessage());
            }
        }}, "logwatch");
        t.setDaemon(true);
        t.start();
    }

    private void stopLogWatcher() {
        if (logcat != null) try { logcat.destroy(); } catch (Exception ignored) {}
        logcat = null;
    }

    private String pkgOfPid(String pid) {
        String p = pidPkg.get(pid);
        if (p != null) return p;
        String cmd = Root.out("cat /proc/" + pid + "/cmdline 2>/dev/null | tr '\\0' ' '").trim();
        if (cmd.contains(" ")) cmd = cmd.substring(0, cmd.indexOf(' '));
        if (cmd.contains(":")) cmd = cmd.substring(0, cmd.indexOf(':'));
        pidPkg.put(pid, cmd);
        return cmd;
    }

    private void onLogLine(String line) {
        // threadtime: "MM-DD HH:MM:SS.mmm  PID  TID L TAG: message"
        String[] f = line.trim().split("\\s+", 6);
        if (f.length < 6) return;
        String pkg = pkgOfPid(f[2]);
        if (pkgs == null || !pkgs.contains(pkg)) return;
        String msg = f[5];
        String low = msg.toLowerCase();
        if (low.contains("replicator created") || low.contains("joining game") || low.contains("connection accepted")
                || low.contains("teleport")) {
            suspectSince.remove(pkg);   // it is (re)connecting by itself, e.g. a teleport
            return;
        }
        boolean disconnect = low.contains("disconnect") || low.contains("connection lost") || low.contains("lost connection")
                || low.contains("kicked");
        if (!disconnect) return;
        java.util.regex.Matcher m = REASON.matcher(msg);
        String code = null;
        if (m.find()) code = m.group(1) != null ? m.group(1) : m.group(2);
        if (code != null && BAD.contains(code)) {
            kicks.add(new String[]{pkg, "disconnected (code " + code + ")"});
        } else if (low.contains("kicked")) {
            kicks.add(new String[]{pkg, "was kicked"});
        } else if (!suspectSince.containsKey(pkg)) {
            suspectSince.put(pkg, System.currentTimeMillis());  // maybe a teleport — confirm in 30 s
        }
    }

    private void handleKicks() throws InterruptedException {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Long> e : suspectSince.entrySet()) {
            if (now - e.getValue() > 30000) {
                suspectSince.remove(e.getKey());
                kicks.add(new String[]{e.getKey(), "lost connection and didn't reconnect"});
            }
        }
        String[] k;
        java.util.Set<String> done = new java.util.HashSet<>();
        while ((k = kicks.poll()) != null && alive()) {
            String p = k[0];
            if (done.contains(p)) continue;
            Long la = launchedAt.get(p);
            if (la != null && now - la < 20000) continue;   // ignore noise right after a launch
            done.add(p);
            suspectSince.remove(p);
            pidPkg.clear();
            if (k[1].startsWith("lost connection")) {
                // unclear case (could have been a teleport): ask Roblox if the account is still in a game
                Long id = userIds.get(p);
                if (id != null && id != 0) {
                    Map<Long, Integer> pr = Roblox.presence(java.util.Collections.singletonList(id), cookies);
                    if (pr != null && Integer.valueOf(2).equals(pr.get(id))) continue;
                }
            }
            launch(p, pkgs.indexOf(p), k[1]);
        }
    }

    /** Optional Discord status every 30 minutes. */
    private void summary() {
        if (!st.b("discordSummary", false)) return;
        long now = System.currentTimeMillis();
        if (now - lastSummary < 30 * 60000L) return;
        lastSummary = now;
        StringBuilder sb = new StringBuilder("📊 Status:\n");
        for (String p : pkgs) {
            String sname = state.containsKey(p) ? state.get(p) : "?";
            Long sa = startedAt.get(p);
            sb.append("• ").append(st.user(p).isEmpty() ? p : st.user(p)).append(" — ").append(sname)
              .append(sa == null ? "" : ", up " + Notify.duration(now - sa))
              .append(", rejoins ").append(rejoins.containsKey(p) ? rejoins.get(p) : 0).append('\n');
        }
        Notify.send(this, sb.toString().trim());
    }

    private long availMb() {
        android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
        android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        return mi.availMem / (1024 * 1024);
    }

    private void placeAll() {
        if (!freeform) return;
        for (int i = 0; i < pkgs.size(); i++)
            if (!st.b("float_" + pkgs.get(i), false)) Device.place(pkgs.get(i), layout.rects.get(i));
    }

    private void check() throws InterruptedException {
        long now = System.currentTimeMillis();
        Map<Long, Integer> pres = null;
        if (st.presenceCheck()) {
            List<Long> ids = new ArrayList<>();
            for (String p : pkgs) if (userIds.get(p) != null && userIds.get(p) != 0) ids.add(userIds.get(p));
            if (!ids.isEmpty()) pres = Roblox.presence(ids, cookies);
        }
        int inGame = 0;
        boolean restartedThisCheck = false;
        for (int i = 0; i < pkgs.size() && alive(); i++) {
            String p = pkgs.get(i);
            long since = now - (launchedAt.containsKey(p) ? launchedAt.get(p) : 0);
            String pid = Device.pid(p);
            if (pid == null) {
                state.put(p, "Closed");
                if (since > 30000) launch(p, i, "closed / crashed");
                continue;
            }
            // frozen app: CPU time not moving for 2 minutes
            if (st.freezeCheck() && since > st.grace() * 1000L) {
                long cpu = Device.cpuTicks(pid);
                Long last = lastCpu.get(p);
                if (cpu >= 0 && last != null && cpu == last) {
                    if (!cpuSince.containsKey(p)) cpuSince.put(p, now);
                    else if (now - cpuSince.get(p) > 120000) {
                        launch(p, i, "froze");
                        continue;
                    }
                } else {
                    cpuSince.remove(p);
                }
                lastCpu.put(p, cpu);
            }
            int hours = st.i("restartHours", 0);
            if (hours > 0 && since > hours * 3600000L && !restartedThisCheck) {
                restartedThisCheck = true;   // one at a time so they don't all reload together
                launch(p, i, "scheduled restart (every " + hours + "h)");
                continue;
            }
            Long id = userIds.get(p);
            if (pres == null || id == null || id == 0 || !pres.containsKey(id)) continue;
            int t = pres.get(id);
            if (t == 2) { strikes.put(p, 0); inGame++; state.put(p, "In game"); continue; }
            state.put(p, t == 1 ? "Online, not in game" : t == 0 ? "Offline" : "Not in game");
            if (since < st.grace() * 1000L) continue;
            int s = (strikes.containsKey(p) ? strikes.get(p) : 0) + 1;
            strikes.put(p, s);
            Log.add(p + " (" + st.user(p) + "): not in game, strike " + s + "/3");
            if (s >= 3) launch(p, i, "not in a game");
        }
        if (pres != null) setStatus("In game: " + inGame + "/" + pkgs.size());
        // once everyone is in game: real memory per instance = how much free RAM disappeared / instances
        if (!measured && pres != null && inGame == pkgs.size() && availBeforeMb > 0) {
            measured = true;
            long per = (availBeforeMb - availMb()) / Math.max(1, pkgs.size());
            if (per >= 300 && per <= 3500) {
                st.put("measuredPerMb", (int) per);
                st.put("measuredWith", pkgs.size());
                Log.add("Measured: each instance really uses about " + per + " MB on this device.");
            }
        }
    }
}
