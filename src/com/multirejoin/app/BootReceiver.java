package com.multirejoin.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** After a reboot: turn window mode back on, and (if enabled in Settings) start all instances. */
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        try { KeeperService.sync(c); } catch (Exception ignored) {}
        Store st = new Store(c);
        if (!st.b("autoStart", false) || Roblox.toUri(st.gameLink()) == null) return;
        Intent run = new Intent(c, RunnerService.class).setAction(RunnerService.ACTION_START)
                .putExtra(RunnerService.EXTRA_BOOT, true);
        try {
            if (Build.VERSION.SDK_INT >= 26) Context.class.getMethod("startForegroundService", Intent.class).invoke(c, run);
            else c.startService(run);
        } catch (Exception e) {
            c.startService(run);
        }
    }
}
