package com.worst.floatwin;

import android.view.ActionMode;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.SearchEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

/** Passes every window event through unchanged; on a finger-down it also brings the window to the front. */
final class TouchProxy implements Window.Callback {
    private final Window.Callback o;
    private final Runnable onTouch;

    TouchProxy(Window.Callback o, Runnable onTouch) { this.o = o; this.onTouch = onTouch; }

    @Override public boolean dispatchTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) try { onTouch.run(); } catch (Throwable ignored) {}
        return o.dispatchTouchEvent(e);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) { return o.dispatchKeyEvent(e); }
    @Override public boolean dispatchKeyShortcutEvent(KeyEvent e) { return o.dispatchKeyShortcutEvent(e); }
    @Override public boolean dispatchTrackballEvent(MotionEvent e) { return o.dispatchTrackballEvent(e); }
    @Override public boolean dispatchGenericMotionEvent(MotionEvent e) { return o.dispatchGenericMotionEvent(e); }
    @Override public boolean dispatchPopulateAccessibilityEvent(AccessibilityEvent e) { return o.dispatchPopulateAccessibilityEvent(e); }
    @Override public View onCreatePanelView(int f) { return o.onCreatePanelView(f); }
    @Override public boolean onCreatePanelMenu(int f, Menu m) { return o.onCreatePanelMenu(f, m); }
    @Override public boolean onPreparePanel(int f, View v, Menu m) { return o.onPreparePanel(f, v, m); }
    @Override public boolean onMenuOpened(int f, Menu m) { return o.onMenuOpened(f, m); }
    @Override public boolean onMenuItemSelected(int f, MenuItem i) { return o.onMenuItemSelected(f, i); }
    @Override public void onWindowAttributesChanged(WindowManager.LayoutParams a) { o.onWindowAttributesChanged(a); }
    @Override public void onContentChanged() { o.onContentChanged(); }
    @Override public void onWindowFocusChanged(boolean f) { o.onWindowFocusChanged(f); }
    @Override public void onAttachedToWindow() { o.onAttachedToWindow(); }
    @Override public void onDetachedFromWindow() { o.onDetachedFromWindow(); }
    @Override public void onPanelClosed(int f, Menu m) { o.onPanelClosed(f, m); }
    @Override public boolean onSearchRequested() { return o.onSearchRequested(); }
    @Override public boolean onSearchRequested(SearchEvent e) { return o.onSearchRequested(e); }
    @Override public ActionMode onWindowStartingActionMode(ActionMode.Callback c) { return o.onWindowStartingActionMode(c); }
    @Override public ActionMode onWindowStartingActionMode(ActionMode.Callback c, int t) { return o.onWindowStartingActionMode(c, t); }
    @Override public void onActionModeStarted(ActionMode m) { o.onActionModeStarted(m); }
    @Override public void onActionModeFinished(ActionMode m) { o.onActionModeFinished(m); }
}
