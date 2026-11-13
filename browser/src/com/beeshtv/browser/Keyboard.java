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
 * BeeshTV's own on-screen keyboard, in place of the TV's: a slim panel docked to the bottom edge that
 * slides up when a field wants typing. Arrows move between keys, OK types, Back closes it; the pointer
 * and a mouse can click keys too. Letters and a page of symbols, with keys for web addresses.
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
            HIDE = "hide", MIC = "mic", PHONE = "phone";
    /** Keys of the suggestion strip: this, then the suggestion's place in the list. */
    private static final String PICK = "\u0001";

    /** Sizes in Settings: key height and keyboard width in dp, and letter size in sp. */
    static final String[] SIZES = {"Small", "Medium", "Large"};
    private static final int[] KEY_H = {28, 32, 38}, WIDTH = {480, 560, 680}, TEXT_SP = {13, 14, 16};
    /** The key you're on: a yellow-green that reads from across the room, dark letters on it. */
    static final int LIME = 0xFFCFEA3C, ON_LIME = 0xFF1B1F08;

    private static final String[][] LETTER_ROWS = {
            {"1", "2", "3", "4", "5", "6", "7", "8", "9", "0"},
            {"q", "w", "e", "r", "t", "y", "u", "i", "o", "p"},
            {"a", "s", "d", "f", "g", "h", "j", "k", "l", BACK},
            {SHIFT, "z", "x", "c", "v", "b", "n", "m", ".", "-"},
            {SYMBOLS, ".com", "/", MIC, PHONE, SPACE, "@", HIDE, GO},
    };
    private static final String[][] SYMBOL_ROWS = {
            {"1", "2", "3", "4", "5", "6", "7", "8", "9", "0"},
            {"!", "@", "#", "$", "%", "&", "*", "(", ")", "?"},
            {"-", "_", "=", "+", ":", ";", "'", "\"", ",", BACK},
            {"~", "`", "|", "\\", "<", ">", "[", "]", "{", "}"},
            {LETTERS, ".com", "/", MIC, PHONE, SPACE, ".", HIDE, GO},
    };
    /** Width of a key in units, ten units to a row. */
    private static float units(String k) {
        switch (k) {
            case SPACE: return 1.5f;
            case GO: return 1.5f;
            default: return 1f;
        }
    }

    private final LinearLayout rows;
    private final Frost frost;
    private int size = 1;
    private Runnable mic, phone;
    /** Pages from history that match what's typed, as {label, address}; shown in a strip above the keys. */
    private List<String[]> suggestions = new ArrayList<>();
    private java.util.function.Consumer<String> onPick;
    private final List<List<View>> keys = new ArrayList<>();
    private Target target;
    private boolean symbols, shift;
    private int row = 1, col = 4;

    Keyboard(Context c) {
        super(c);
        // Frosted glass the size of the keys, a little above the bottom edge and rounded all round;
        // only the glass takes clicks, so the page either side of it still gets the pointer.
        frost = new Frost(c);
        float r = dp(24);
        frost.corners(r, r, r, r);
        frost.dense();
        frost.setClickable(true);
        LinearLayout panel = new LinearLayout(c);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER_HORIZONTAL);
        panel.setPadding(dp(16), dp(10), dp(16), dp(16));
        View grip = new View(c);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(2));
        g.setColor(Ui.alpha(Ui.TEXT, 0.25f));
        grip.setBackground(g);
        panel.addView(grip, lp(dp(32), dp(4), 0, 0, 0, dp(10)));
        rows = new LinearLayout(c);
        rows.setOrientation(LinearLayout.VERTICAL);
        panel.addView(rows, new LinearLayout.LayoutParams(dp(WIDTH[size]), -2));
        frost.addView(panel, new LayoutParams(-2, -2));
        LayoutParams fl = new LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        fl.bottomMargin = dp(16);
        addView(frost, fl);
        build();
    }

    /** The blurred screen behind the keyboard, taken as it opens. */
    void setBackdrop(android.graphics.Bitmap b) { frost.setBackdrop(b); }

    void setMic(Runnable r) { mic = r; }

    /** The phone key: hands typing to the TV's own keyboard, which a phone or remote keyboard can type into. */
    void setPhone(Runnable r) { phone = r; }

    void setOnPick(java.util.function.Consumer<String> r) { onPick = r; }

    /** New suggestions for what's typed; the selection stays on the same key. */
    void suggest(List<String[]> s) {
        if (s == null) s = new ArrayList<>();
        if (same(s, suggestions)) return;
        int before = suggestions.isEmpty() ? 0 : 1, after = s.isEmpty() ? 0 : 1;
        if (before == 1 && row == 0) {
            // On the strip: stay on it, or drop to the number row (row 0 once the strip goes).
            if (after == 1) col = Math.min(col, s.size() - 1);
        } else {
            row = Math.max(0, row + after - before);
        }
        suggestions = s;
        build();
    }

    private static boolean same(List<String[]> a, List<String[]> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (!a.get(i)[1].equals(b.get(i)[1])) return false;
        return true;
    }

    void setSize(int s) {
        size = Math.max(0, Math.min(SIZES.length - 1, s));
        rows.getLayoutParams().width = Math.min(dp(WIDTH[size]), getResources().getDisplayMetrics().widthPixels - dp(32));
        rows.requestLayout();
        build();
    }

    boolean open() { return getVisibility() == VISIBLE; }

    void show(Target t) {
        if (target != null && target != t) target.closed();
        target = t;
        shift = false;
        build();
        if (!open() || closing) {
            closing = false;
            setVisibility(VISIBLE);
            animate().cancel();
            // Slides up from the bottom edge.
            post(() -> {
                setTranslationY(getHeight() == 0 ? dp(260) : getHeight());
                animate().translationY(0).setDuration(240).setInterpolator(EASE).start();
            });
        }
    }

    private static final android.view.animation.Interpolator EASE =
            new android.view.animation.PathInterpolator(0.2f, 0.9f, 0.25f, 1f);
    private boolean closing;

    boolean isOpen() { return open() && !closing; }

    void close() {
        if (!open() || closing) return;
        closing = true;
        Target t = target;
        target = null;
        animate().cancel();
        animate().translationY(getHeight()).setDuration(180).setInterpolator(EASE)
                .withEndAction(() -> { if (closing) { setVisibility(GONE); closing = false; } }).start();
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
        if (!suggestions.isEmpty()) {
            // Up from the number row: pages from history, OK opens one.
            LinearLayout line = new LinearLayout(getContext());
            List<View> inRow = new ArrayList<>();
            for (int i = 0; i < suggestions.size(); i++) {
                View v = chipView(i);
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(KEY_H[size]), 1f);
                p.setMargins(dp(2), dp(2), dp(2), dp(6));
                line.addView(v, p);
                inRow.add(v);
            }
            rows.addView(line, new LinearLayout.LayoutParams(-1, -2));
            keys.add(inRow);
        }
        for (String[] r : layout) {
            LinearLayout line = new LinearLayout(getContext());
            line.setGravity(Gravity.CENTER_HORIZONTAL);
            List<View> inRow = new ArrayList<>();
            for (String k : r) {
                View v = keyView(k);
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(KEY_H[size]), units(k));
                p.setMargins(dp(2), dp(2), dp(2), dp(2));
                line.addView(v, p);
                inRow.add(v);
            }
            line.setWeightSum(10f);
            line.setGravity(Gravity.CENTER_HORIZONTAL);
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
                || k.equals(GO) || k.equals(".com") || k.equals(HIDE) || k.equals(MIC) || k.equals(PHONE);
        int ink = k.equals(GO) ? Ui.BG : special ? Ui.TEXT_2 : Ui.TEXT;
        // Ink turns dark on the lime key.
        ColorStateList inks = inks(k.equals(SHIFT) && shift ? Ui.TEXT : k.equals(SPACE) ? Ui.TEXT_3 : ink);
        View v;
        int icon = k.equals(SHIFT) ? R.drawable.ic_shift : k.equals(BACK) ? R.drawable.ic_backspace
                : k.equals(HIDE) ? R.drawable.ic_keyboard_hide : k.equals(MIC) ? R.drawable.ic_mic
                : k.equals(PHONE) ? R.drawable.ic_phone : 0;
        if (icon != 0) {
            ImageView i = new ImageView(c);
            i.setImageResource(icon);
            i.setImageTintList(inks);
            i.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            int pad = dp(KEY_H[size] / 4f);
            i.setPadding(0, pad, 0, pad);
            i.setDuplicateParentStateEnabled(false);
            v = i;
        } else {
            TextView t = Ui.text(c, k.length() == 1 ? TEXT_SP[size] : TEXT_SP[size] - 2, k.equals(GO) ? 500 : 400, ink);
            t.setGravity(Gravity.CENTER);
            t.setText(k.equals(SPACE) ? "space" : shift && k.length() == 1 ? k.toUpperCase(Locale.ROOT) : k);
            t.setTextColor(inks);
            v = t;
        }
        int rest = k.equals(GO) ? Ui.TEXT : Ui.alpha(Ui.TEXT, special ? 0.06f : 0.11f);
        android.graphics.drawable.StateListDrawable bg = new android.graphics.drawable.StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_activated}, keyFill(LIME));
        bg.addState(new int[]{android.R.attr.state_hovered}, keyFill(LIME));
        bg.addState(new int[]{android.R.attr.state_pressed}, keyFill(LIME));
        bg.addState(new int[]{}, keyFill(rest));
        bg.setEnterFadeDuration(70);
        bg.setExitFadeDuration(110);
        v.setBackground(bg);
        v.setClickable(true);
        v.setOnClickListener(x -> press(k));
        return v;
    }

    /** A suggestion: the page's title, its site under it, on a key the full width of its share of the strip. */
    private View chipView(int i) {
        String[] s = suggestions.get(i);
        TextView t = Ui.text(getContext(), TEXT_SP[size] - 3, 500, Ui.TEXT);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(10), 0, dp(10), 0);
        t.setText(s[0]);
        t.setTextColor(inks(Ui.TEXT));
        android.graphics.drawable.StateListDrawable bg = new android.graphics.drawable.StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_activated}, keyFill(LIME));
        bg.addState(new int[]{android.R.attr.state_hovered}, keyFill(LIME));
        bg.addState(new int[]{android.R.attr.state_pressed}, keyFill(LIME));
        bg.addState(new int[]{}, keyFill(Ui.alpha(Ui.TEXT, 0.16f)));
        t.setBackground(bg);
        t.setClickable(true);
        t.setOnClickListener(x -> press(PICK + i));
        return t;
    }

    private static ColorStateList inks(int rest) {
        return new ColorStateList(new int[][]{
                {android.R.attr.state_activated}, {android.R.attr.state_hovered}, {android.R.attr.state_pressed}, {}},
                new int[]{ON_LIME, ON_LIME, ON_LIME, rest});
    }

    private GradientDrawable keyFill(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(8));
        g.setColor(color);
        return g;
    }

    private void press(String k) {
        if (target == null) return;
        switch (k) {
            case SHIFT: shift = !shift; build(); return;
            case SYMBOLS: symbols = true; build(); return;
            case LETTERS: symbols = false; build(); return;
            case BACK: target.backspace(); return;
            case HIDE: close(); return;
            case MIC: if (mic != null) mic.run(); return;
            case PHONE: if (phone != null) phone.run(); return;
            case GO: target.enter(); return;
            default:
                if (k.startsWith(PICK)) {
                    int i = Integer.parseInt(k.substring(1));
                    if (onPick != null && i < suggestions.size()) onPick.accept(suggestions.get(i)[1]);
                    return;
                }
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

    }

    private int dp(float v) { return Ui.dp(getContext(), v); }

    private static LinearLayout.LayoutParams lp(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(l, t, r, b);
        return p;
    }
}
