package com.multirejoin.app;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * "Always open in a window": watches for Roblox apps that were opened full screen
 * (from the home screen, a notification, anywhere) and moves them into a freeform window at their slot.
 */
public class KeeperService extends Service {
    private static volatile boolean on = false;
    private Thread worker;

    public static boolean isOn() { return on; }

    public static void sync(Context c) {
        Store st0 = new Store(c);
        boolean want = st0.b("keeper", true) || st0.b("autoUpdate", true);
        Intent i = new Intent(c, KeeperService.class);
        if (want && !on) {
            if (Build.VERSION.SDK_INT >= 26) {
                try { Context.class.getMethod("startForegroundService", Intent.class).invoke(c, i); return; }
                catch (Exception ignored) {}
            }
            c.startService(i);
        } else if (!want && on) {
            c.stopService(i);
        }
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() { super.onCreate(); Device.ctx = getApplicationContext(); }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(2, notification());
        if (on) return START_STICKY;
        on = true;
        worker = new Thread(new Runnable() { public void run() { loop(); } }, "keeper");
        worker.start();
        return START_STICKY;
    }

    @Override public void onDestroy() {
        on = false;
        if (worker != null) worker.interrupt();
        super.onDestroy();
    }

    private Notification notification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                Class<?> ch = Class.forName("android.app.NotificationChannel");
                Object channel = ch.getConstructor(String.class, CharSequence.class, int.class)
                        .newInstance("keeper", "Window mode", 1);
                NotificationManager.class.getMethod("createNotificationChannel", ch).invoke(nm, channel);
                b = Notification.Builder.class.getConstructor(Context.class, String.class).newInstance(this, "keeper");
            } catch (Exception e) {
                b = new Notification.Builder(this);
            }
        } else {
            b = new Notification.Builder(this);
        }
        PendingIntent pi = PendingIntent.getActivity(this, 2, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | 0x04000000);
        return b.setContentTitle("WORST - AUTO REJOIN").setContentText("Window mode & executor updates are on")
                .setSmallIcon(android.R.drawable.ic_menu_crop).setOngoing(true).setContentIntent(pi).build();
    }

    private long nextUpdateCheck = 0;
    private int lastW = -1;

    private void loop() {
        Set<Integer> handled = new HashSet<>();
        int n = 0;
        Device.Slots slots = null;
        boolean ff = false;
        while (on) {
            try {
                Thread.sleep(2000);
                // refresh the slot grid and freeform state every ~20s, not every loop
                if (slots == null || n++ % 10 == 0) {
                    slots = new Device.Slots(this);
                    ff = Device.freeformOn();
                }
                Store st = new Store(this);
                // executor auto-update on its schedule (first check 2 minutes after start)
                if (st.b("autoUpdate", true) && !AutoUpdater.isBusy()) {
                    long nowT = System.currentTimeMillis();
                    if (nextUpdateCheck == 0) nextUpdateCheck = nowT + 120000;
                    if (nowT >= nextUpdateCheck) {
                        nextUpdateCheck = nowT + st.i("updateEvery", 60) * 60000L;
                        AutoUpdater.checkAll(this, false);
                    }
                }
                if (!ff || !st.b("keeper", true)) continue;
                boolean lock = st.b("lockWindows", true);
                List<String> roblox = Roblox.installedApps(this);
                StringBuilder cmd = new StringBuilder();
                for (Device.Win w : Device.windows()) {
                    if (!roblox.contains(w.pkg)) continue;
                    if (st.b("float_" + w.pkg, false)) continue;   // floating clones handle their own window
                    if ("fullscreen".equals(w.mode)) {
                        if (handled.contains(w.task) || RunnerService.isRunning()) continue;
                        handled.add(w.task);
                        String comp = Device.launcherComponent(this, w.pkg);
                        if (comp == null) continue;
                        Log.add("Window mode: moving " + w.pkg + " into a window");
                        Root.run("am start --windowingMode 5 -n " + Root.q(comp));
                        Thread.sleep(1500);
                        int id = Device.taskId(w.pkg);
                        if (id >= 0) Root.run(Device.resizeCmd(id, slots.get(w.pkg)));
                    } else if ("freeform".equals(w.mode)) {
                        // keep every window in its slot (Roblox resizes itself when it joins a game)
                        // keep every window in its slot (Roblox resizes itself when it joins a game)
                        if (lock || !handled.contains(w.task)) cmd.append(Device.resizeCmd(w.task, slots.get(w.pkg)));
                        handled.add(w.task);
                    }
                }
                if (cmd.length() > 0) Root.run(cmd.toString());
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                Log.add("Window mode error: " + e);
            }
        }
    }
}
