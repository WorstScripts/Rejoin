package com.multirejoin.app;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Toast;

/** Invisible activity used by home-screen shortcuts: opens a Roblox app in a freeform window. */
public class OpenActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        final String pkg = getIntent().getStringExtra("pkg");
        if (pkg == null) { finish(); return; }
        final android.content.Context app = getApplicationContext();
        new Thread(new Runnable() { public void run() {
            final String err = Device.openInWindow(app, pkg);
            if (err != null) {
                new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() { public void run() { Toast.makeText(app, err, Toast.LENGTH_LONG).show(); } });
            }
        }}).start();
        finish();
    }
}
