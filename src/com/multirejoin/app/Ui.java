package com.multirejoin.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

/** Small UI kit so every screen looks the same. */
public final class Ui {
    // WORST theme: black, ash white, blood red
    public static final int PRIMARY = 0xffe8e8e8, PRIMARY_DARK = 0xff000000, BG = 0xff0b0b0c, SURFACE = 0xff161618,
            TEXT = 0xfff2f2f2, SUB = 0xff8e8e93, LINE = 0xff26262a, GREEN = 0xff22c55e, RED = 0xffd0202e,
            ORANGE = 0xfff59e0b, ICON_BG = 0xff242427, CHIP = 0xff1e1e21, ON_PRIMARY = 0xff0b0b0c;

    private final Context c;
    private final float d;

    public Ui(Context c) { this.c = c; d = c.getResources().getDisplayMetrics().density; }

    public int dp(float v) { return (int) (v * d + 0.5f); }

    public GradientDrawable round(int color, float r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(r));
        return g;
    }

    public Drawable ripple(int color, float r) {
        return new RippleDrawable(ColorStateList.valueOf(0x22ffffff), round(color, r), round(Color.WHITE, r));
    }

    public LinearLayout vbox() {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public LinearLayout hbox() {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    public LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    public LinearLayout.LayoutParams match() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    public LinearLayout.LayoutParams weight(float w) {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w);
    }

    /** White rounded card with a soft shadow, added to parent. */
    public LinearLayout card(LinearLayout parent) {
        LinearLayout l = vbox();
        GradientDrawable cg = round(SURFACE, 18);
        cg.setStroke(1, 0xff2a2a2e);
        l.setBackground(cg);
        l.setPadding(dp(4), dp(8), dp(4), dp(8));
        LinearLayout.LayoutParams lp = match();
        lp.setMargins(dp(14), dp(7), dp(14), dp(7));
        parent.addView(l, lp);
        return l;
    }

    public TextView text(String s, float size, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    /** Blue section title inside a card, like "App Cloner" / "Login & Store". */
    public TextView header(String s) {
        TextView t = text(s.toUpperCase(), 12, RED, true);
        t.setLetterSpacing(0.14f);
        t.setPadding(dp(16), dp(12), dp(16), dp(6));
        return t;
    }

    public TextView note(String s) {
        TextView t = text(s, 13, SUB, false);
        t.setPadding(dp(16), dp(2), dp(16), dp(8));
        t.setLineSpacing(0, 1.15f);
        return t;
    }

    public ImageView icon(int res, int tint, int sizeDp) {
        ImageView iv = new ImageView(c);
        iv.setImageResource(res);
        iv.setImageTintList(ColorStateList.valueOf(tint));
        iv.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return iv;
    }

    /** Round grey bubble with an icon inside (left side of list rows). */
    public View iconBubble(int res) {
        LinearLayout b = hbox();
        b.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(ICON_BG);
        b.setBackground(g);
        b.addView(icon(res, TEXT, 22));
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(44), dp(44)));
        return b;
    }

    public View appIcon(Drawable dr) {
        ImageView iv = new ImageView(c);
        if (dr != null) iv.setImageDrawable(dr);
        else { iv.setImageResource(R.drawable.ic_game); iv.setImageTintList(ColorStateList.valueOf(TEXT)); }
        iv.setLayoutParams(new LinearLayout.LayoutParams(dp(44), dp(44)));
        return iv;
    }

    /** A list row: [left] title / subtitle [right]. */
    public LinearLayout row(View left, String title, String sub, View right, View.OnClickListener click) {
        LinearLayout r = hbox();
        r.setPadding(dp(14), dp(10), dp(12), dp(10));
        r.setMinimumHeight(dp(60));
        if (left != null) {
            r.addView(left);
            View gap = new View(c);
            r.addView(gap, new LinearLayout.LayoutParams(dp(14), 1));
        }
        LinearLayout texts = vbox();
        TextView t = text(title, 16, TEXT, false);
        texts.addView(t);
        if (sub != null && !sub.isEmpty()) {
            TextView s = text(sub, 13, SUB, false);
            s.setPadding(0, dp(2), 0, 0);
            texts.addView(s);
        }
        r.addView(texts, weight(1));
        if (right != null) r.addView(right);
        if (click != null) {
            r.setBackground(ripple(SURFACE, 12));
            r.setOnClickListener(click);
        }
        return r;
    }

    public View chevron() { return icon(R.drawable.ic_chevron, SUB, 22); }

    public Switch toggle(boolean on) {
        Switch s = new Switch(c);
        s.setChecked(on);
        return s;
    }

    public View divider() {
        View v = new View(c);
        v.setBackgroundColor(LINE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1);
        lp.setMargins(dp(72), 0, dp(12), 0);
        v.setLayoutParams(lp);
        return v;
    }

    public Button button(String s, int bg, int fg) {
        Button b = new Button(c);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setTextColor(fg);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setStateListAnimator(null);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(fg == ON_PRIMARY ? 0x33000000 : 0x33ffffff), round(bg, 14), null));
        b.setPadding(dp(16), dp(12), dp(16), dp(12));
        return b;
    }

    public Button primary(String s) { return button(s, PRIMARY, ON_PRIMARY); }

    public Button secondary(String s) { return button(s, ICON_BG, TEXT); }

    public LinearLayout.LayoutParams buttonLp() {
        LinearLayout.LayoutParams lp = match();
        lp.setMargins(dp(14), dp(6), dp(14), dp(6));
        return lp;
    }

    public EditText field(String hint, String value) {
        EditText e = new EditText(c);
        e.setHint(hint);
        e.setText(value);
        e.setTextColor(TEXT);
        e.setHintTextColor(0xff5f5f66);
        e.setTextSize(15);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        e.setBackground(round(CHIP, 12));
        e.setPadding(dp(14), dp(12), dp(14), dp(12));
        return e;
    }

    public LinearLayout.LayoutParams fieldLp() {
        LinearLayout.LayoutParams lp = match();
        lp.setMargins(dp(16), dp(4), dp(16), dp(8));
        return lp;
    }

    public TextView pill(String s, int color) {
        TextView t = text(s, 12, color, true);
        t.setBackground(round((color & 0x00ffffff) | 0x26000000, 20));
        t.setPadding(dp(10), dp(4), dp(10), dp(4));
        return t;
    }
}
