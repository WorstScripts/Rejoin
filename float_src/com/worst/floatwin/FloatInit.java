package com.worst.floatwin;

import android.app.Application;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;

/**
 * Added into every floating clone by WORST - AUTO REJOIN. Android starts it automatically when the clone
 * starts (it is declared as a content provider), and it turns every screen of the app into a floating window.
 * The window slot number is passed in the provider's authority: "<package>.worstfloat.<slot>".
 */
public class FloatInit extends ContentProvider {
    private int slot = 0;

    @Override
    public void attachInfo(Context context, ProviderInfo info) {
        try {
            String a = info.authority;
            slot = Integer.parseInt(a.substring(a.lastIndexOf('.') + 1));
        } catch (Throwable ignored) {}
        super.attachInfo(context, info);
    }

    @Override
    public boolean onCreate() {
        try {
            Application app = (Application) getContext().getApplicationContext();
            app.registerActivityLifecycleCallbacks(new FloatWindows(app, slot));
        } catch (Throwable ignored) {}
        return true;
    }

    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
    @Override public String getType(Uri u) { return null; }
    @Override public Uri insert(Uri u, ContentValues v) { return null; }
    @Override public int delete(Uri u, String s, String[] a) { return 0; }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }
}
