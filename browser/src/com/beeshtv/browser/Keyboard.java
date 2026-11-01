package com.beeshtv.browser;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * BeeshTV's own on-screen keyboard, in place of the TV's. Arrows move between keys, OK types, Back
 * closes it; the pointer and a mouse can click keys too. Letters and a page of symbols, with keys
 * for web addresses (.com, /, @).
 */
final class Keyboard extends FrameLayout {
    /** Where the typing goes: one of our fields, or a field on a web page. */
    interface Target {
        void type(String text);
        void backspace();
        void enter();
        void closed();
    }

    // Special keys.
    private static final String SHIFT = "⇧", BACK = "⌫", SYMBOLS = "#+=", LETTERS = "abc", SPACE = " ", GO = "Go",
            HIDE = "hide";

    private static final String[][] LETTER_ROWS = {
            {"1", "2", "3", "4", "5", "6", "7", "8", "9", "0"},
            {"q", "w", "e", "r", "t", "y", "u", "i", "o", "p"},
            {"a", "s", "d", "f", "g", "h", "j", "k", "l", BACK},
            {SHIFT, "z", "x", "c", "v", "b", "n", "m", ".", "-"},
            {SYMBOLS, ".com", "/", SPACE, "@", GO},
    };
    private static final String[][] SYMBOL_ROWS = {
            {"1", "2", "3", "4", "5", "6", "7", "8", "9", "0"},
            {"!", "@", "#", "$", "%", "&", "*", "(", ")", "?"},
            {"-", "_", "=", "+", ":", ";", "'", "\"", ",", BACK},
            {"~", "`", "|", "\\", "<", ">", "[", "]", "{", "}"},
            {LETTERS, ".com", "/", SPACE, ".", GO},
    };
    /** Width of a key in units, ten units to a row. */
    private static float units(String k) {
        switch (k) {
            case SPACE: return 3f;
            case GO: return 2f;
            case SYMBOLS: case LETTERS: case ".com": return 1.5f;
            default: return 1f;
        }
    }

    private final LinearLayout rows;
    private final List<List<View>> keys = new ArrayList<>();
    private View goKey;
    private Target target;
    private boolean symbols, shift;
    private int row = 1, col = 4;

    Keyboard(Context c) {
        super(c);
        setClickable(true);
        LinearLayout panel = new LinearLayout(c);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(12), dp(12), dp(12), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(18));
        bg.setColor(Ui.SURFACE);
        panel.setBackground(bg);
        panel.setElevation(dp(12));
        panel.setClickable(true);
        // A small handle row: what you're typing into, and a button to put the keyboard away.
        LinearLayout top = new LinearLayout(c);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView hint = Ui.text(c, 13, 500, Ui.TEXT_3);
        hint.setText("Arrows to move · OK to type · Back to close");
        top.addView(hint, new LinearLayout.LayoutParams(0, -2, 1f));
        ImageView hide = new ImageView(c);
        hide.setImageResource(R.drawable.ic_keyboard_hide);
        hide.setImageTintList(ColorStateList.valueOf(Ui.TEXT_2));
        hide.setPadding(dp(8), dp(6), dp(8), dp(6));
        hide.setBackground(Ui.surface(c, 8, 0));
        hide.setOnClickListener(v -> close());
        top.addView(hide, new LinearLayout.LayoutParams(dp(40), dp(32)));
        panel.addView(top, lp(-1, -2, dp(4), 0, dp(4), dp(8)));
        rows = new LinearLayout(c);
        rows.setOrientation(LinearLayout.VERTICAL);
        panel.addView(rows);
        addView(panel, new LayoutParams(-1, -2, Gravity.BOTTOM));
        build();
    }

    boolean open() { return getVisibility() == VISIBLE; }

    void show(Target t) {
        if (target != null && target != t) target.closed();
        target = t;
        shift = false;
        build();
        if (!open()) {
            setVisibility(VISIBLE);
            setAlpha(0f);
            setTranslationY(dp(24));
            animate().alpha(1f).translationY(0).setDuration(180).start();
        }
    }

    void close() {
        if (!open()) return;
        setVisibility(GONE);
        Target t = target;
        target = null;
        if (t != null) t.closed();
    }

    /** Arrows and OK from the remote while the keyboard is up. */
    boolean key(KeyEvent e) {
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT: if (down) move(0, -1); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: if (down) move(0, 1); return true;
            case KeyEvent.KEYCODE_DPAD_UP: if (down) move(-1, 0); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: if (down) move(1, 0); return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (!down) keys.get(row).get(col).performClick();
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (!down) close();
                return true;
            default:
                return false;
        }
    }

    private void build() {
        rows.removeAllViews();
        keys.clear();
        String[][] layout = symbols ? SYMBOL_ROWS : LETTER_ROWS;
        for (String[] r : layout) {
            LinearLayout line = new LinearLayout(getContext());
            line.setGravity(Gravity.CENTER_HORIZONTAL);
            List<View> inRow = new ArrayList<>();
            for (String k : r) {
                View v = keyView(k);
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(46), units(k));
                p.setMargins(dp(3), dp(3), dp(3), dp(3));
                line.addView(v, p);
                inRow.add(v);
            }
            line.setWeightSum(10f);
            rows.addView(line, new LinearLayout.LayoutParams(-1, -2));
            keys.add(inRow);
        }
        row = Math.min(row, keys.size() - 1);
        col = Math.min(col, keys.get(row).size() - 1);
        mark();
    }

    private View keyView(String k) {
        Context c = getContext();
        boolean special = k.equals(SHIFT) || k.equals(BACK) || k.equals(SYMBOLS) || k.equals(LETTERS)
                || k.equals(GO) || k.equals(".com");
        View v;
        if (k.equals(SHIFT) || k.equals(BACK)) {
            ImageView i = new ImageView(c);
            i.setImageResource(k.equals(SHIFT) ? R.drawable.ic_shift : R.drawable.ic_backspace);
            i.setImageTintList(ColorStateList.valueOf(k.equals(SHIFT) && shift ? Ui.TEXT : Ui.TEXT_2));
            i.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            i.setPadding(0, dp(12), 0, dp(12));
            v = i;
        } else {
            TextView t = Ui.text(c, k.length() == 1 ? 18 : 14, k.equals(GO) ? 500 : 400,
                    k.equals(GO) ? Ui.BG : special ? Ui.TEXT_2 : Ui.TEXT);
            t.setGravity(Gravity.CENTER);
            t.setText(k.equals(SPACE) ? "space" : shift && k.length() == 1 ? k.toUpperCase(Locale.ROOT) : k);
            if (k.equals(SPACE)) t.setTextColor(Ui.TEXT_3);
            v = t;
        }
        if (k.equals(GO)) {
            goKey = v;
            // The one filled key: the text colour, like the primary button.
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(dp(10));
            g.setColor(Ui.TEXT);
            v.setBackground(g);
        } else {
            v.setBackground(Ui.surface(c, 10, special ? Ui.FROST : Ui.SELECTED));
        }
        v.setClickable(true);
        v.setOnClickListener(x -> press(k));
        return v;
    }

    private void press(String k) {
        if (target == null) return;
        switch (k) {
            case SHIFT: shift = !shift; build(); return;
            case SYMBOLS: symbols = true; build(); return;
            case LETTERS: symbols = false; build(); return;
            case BACK: target.backspace(); return;
            case GO: target.enter(); return;
            default:
                target.type(shift && k.length() == 1 ? k.toUpperCase(Locale.ROOT) : k);
                if (shift) { shift = false; build(); }
        }
    }

    /** Next key in that direction; up and down land on the key nearest across. */
    private void move(int dRow, int dCol) {
        if (dRow == 0) {
            col = Math.max(0, Math.min(keys.get(row).size() - 1, col + dCol));
        } else {
            int r = Math.max(0, Math.min(keys.size() - 1, row + dRow));
            if (r != row) {
                View from = keys.get(row).get(col);
                float x = from.getLeft() + from.getWidth() / 2f;
                int best = 0;
                float dist = Float.MAX_VALUE;
                for (int i = 0; i < keys.get(r).size(); i++) {
                    View v = keys.get(r).get(i);
                    float d = Math.abs(v.getLeft() + v.getWidth() / 2f - x);
                    if (d < dist) { dist = d; best = i; }
                }
                row = r;
                col = best;
            }
        }
        mark();
    }

    private void mark() {
        for (int r = 0; r < keys.size(); r++) {
            for (int i = 0; i < keys.get(r).size(); i++) keys.get(r).get(i).setActivated(r == row && i == col);
        }
        // The filled Go key shows it's picked by growing a little instead.
        if (goKey != null) {
            float s = goKey.isActivated() ? 1.06f : 1f;
            goKey.setScaleX(s);
            goKey.setScaleY(s);
        }
    }

    private int dp(float v) { return Ui.dp(getContext(), v); }

    private static LinearLayout.LayoutParams lp(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(l, t, r, b);
        return p;
    }
}
