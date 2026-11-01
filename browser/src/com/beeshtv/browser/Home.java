package com.beeshtv.browser;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The page the browser opens on: Home's photo behind everything, the weather and time top right,
 * the BeeshTV mark and a large search box in the middle, and your shortcuts under it. Everything is
 * clicked with the pointer, like a page.
 */
final class Home extends FrameLayout {
    interface Host {
        /** Open a web address, or search for anything else. */
        void open(String text);
    }

    static final String DEFAULT_SHORTCUTS = "YouTube|https://www.youtube.com;Google|https://www.google.com;"
            + "Wikipedia|https://www.wikipedia.org;Reddit|https://www.reddit.com";
    /** Background: Home's photo as Home shows it, or frosted like the glass over Home. */
    static final String[] BACKGROUNDS = {"TV Home photo", "Off (frosted)"};

    private final Host host;
    private final SharedPreferences sp;
    private final ImageView photo;
    private final View shade;
    private final ImageView weatherIcon;
    private final TextView temp, condition, date, time;
    private final View weatherBlock, weatherRule;
    private final EditText search;
    private final LinearLayout shortcuts;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    Home(Context c, SharedPreferences sp, int pageTop, Host host) {
        super(c);
        this.sp = sp;
        this.host = host;
        // A deep blue evening in case the launcher isn't there to lend its photo.
        setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF1A1F4A, 0xFF2B2160, 0xFF120F24}));
        photo = new ImageView(c);
        photo.setScaleType(ImageView.ScaleType.CENTER_CROP);
        addView(photo, new LayoutParams(-1, -1));
        shade = new View(c);
        addView(shade, new LayoutParams(-1, -1));

        // Weather and time, top right.
        LinearLayout widget = new LinearLayout(c);
        widget.setGravity(Gravity.CENTER_VERTICAL);
        widget.setPadding(dp(12), dp(8), dp(14), dp(8));
        widget.setBackground(Ui.glass(c, 14, Ui.HOME_GLASS));
        weatherIcon = new ImageView(c);
        weatherIcon.setImageTintList(android.content.res.ColorStateList.valueOf(Ui.TEXT));
        LinearLayout w = new LinearLayout(c);
        w.setGravity(Gravity.CENTER_VERTICAL);
        w.addView(weatherIcon, lp(dp(20), dp(20), 0, dp(8)));
        LinearLayout wt = column(c);
        temp = Ui.onPhoto(Ui.text(c, 13, 650, Ui.TEXT));
        condition = Ui.onPhoto(Ui.text(c, 9, 500, Ui.TEXT_2));
        // Explicitly as wide as their own text: a vertical row would stretch them to its width.
        wt.addView(temp, lp(-2, -2, 0, 0, 0));
        wt.addView(condition, lp(-2, -2, dp(2), 0, 0));
        w.addView(wt);
        weatherBlock = w;
        widget.addView(w);
        weatherRule = new View(c);
        weatherRule.setBackgroundColor(0x40FFFFFF);
        widget.addView(weatherRule, lp(Math.max(1, dp(0.75f)), dp(22), 0, dp(12), dp(12)));
        LinearLayout dt = column(c);
        date = Ui.onPhoto(Ui.text(c, 10, 600, Ui.TEXT));
        time = Ui.onPhoto(Ui.text(c, 10, 500, Ui.TEXT_2));
        dt.addView(date, lp(-2, -2, 0, 0, 0));
        dt.addView(time, lp(-2, -2, dp(3), 0, 0));
        widget.addView(dt);
        addView(widget, frame(-2, -2, Gravity.TOP | Gravity.END, 0, pageTop + dp(4), dp(24), 0));

        // The mark, the name, the search box and the shortcuts, in the middle.
        LinearLayout middle = column(c);
        middle.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout brand = new LinearLayout(c);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        ImageView mark = new ImageView(c);
        mark.setImageResource(R.drawable.mark);
        brand.addView(mark, lp(dp(54), dp(54), 0, 0));
        TextView name = Ui.onPhoto(Ui.text(c, 42, 700, Ui.TEXT));
        name.setText(new android.text.SpannableStringBuilder("Beesh").append("TV",
                new android.text.style.ForegroundColorSpan(0xFFFFC21A), 0));
        name.setLetterSpacing(-0.01f);
        brand.addView(name, lp(-2, -2, 0, dp(16), 0));
        middle.addView(brand);

        LinearLayout box = new LinearLayout(c);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(dp(18), 0, dp(6), 0);
        box.setBackground(Ui.glass(c, 25, Ui.HOME_GLASS));
        ImageView glass = new ImageView(c);
        glass.setImageResource(R.drawable.ic_search);
        glass.setImageTintList(android.content.res.ColorStateList.valueOf(Ui.TEXT_2));
        box.addView(glass, lp(dp(18), dp(18), 0, dp(10)));
        search = new EditText(c);
        search.setSingleLine(true);
        search.setBackground(null);
        search.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14);
        search.setTypeface(Ui.face(c, 500));
        search.setTextColor(Ui.TEXT);
        search.setHintTextColor(Ui.TEXT_3);
        search.setHint("Search the web or type a web address");
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        search.setImeOptions(EditorInfo.IME_ACTION_GO | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        search.setOnEditorActionListener((v, a, e) -> { go(); return true; });
        box.addView(search, new LinearLayout.LayoutParams(0, -1, 1f));
        ImageView arrow = new ImageView(c);
        arrow.setImageResource(R.drawable.ic_arrow);
        arrow.setImageTintList(android.content.res.ColorStateList.valueOf(Ui.TEXT));
        arrow.setPadding(dp(9), dp(9), dp(9), dp(9));
        GradientDrawable ab = new GradientDrawable();
        ab.setShape(GradientDrawable.OVAL);
        ab.setColor(0x33FFFFFF);
        arrow.setBackground(ab);
        arrow.setOnClickListener(v -> go());
        box.addView(arrow, lp(dp(38), dp(38), 0, 0));
        middle.addView(box, lp(dp(540), dp(50), dp(26), 0, 0));

        shortcuts = new LinearLayout(c);
        shortcuts.setGravity(Gravity.CENTER_HORIZONTAL);
        middle.addView(shortcuts, lp(-2, -2, dp(26), 0, 0));
        addView(middle, frame(-2, -2, Gravity.CENTER, 0, pageTop / 2, 0, 0));

        // A small note, bottom left.
        TextView note = Ui.onPhoto(Ui.text(c, 11, 500, Ui.TEXT_2));
        note.setText("Made for the couch.");
        note.setTypeface(Ui.face(c, 500), android.graphics.Typeface.ITALIC);
        note.setCompoundDrawablesRelative(Ui.icon(c, R.drawable.ic_sparkle, Ui.TEXT_2, 14), null, null, null);
        note.setCompoundDrawablePadding(dp(7));
        note.setGravity(Gravity.CENTER_VERTICAL);
        note.setPadding(dp(12), dp(8), dp(15), dp(8));
        note.setBackground(Ui.glass(c, 16, Ui.HOME_GLASS));
        addView(note, frame(-2, -2, Gravity.BOTTOM | Gravity.START, dp(24), 0, 0, dp(20)));

        fillShortcuts();
        setFocusable(true);
        setFocusableInTouchMode(true);
    }

    /** Called when the page is shown: fresh time, weather and photo. */
    void refresh() {
        tick.run();
        Backdrop.Weather wx = Backdrop.weather(getContext());
        weatherBlock.setVisibility(wx == null ? GONE : VISIBLE);
        weatherRule.setVisibility(wx == null ? GONE : VISIBLE);
        if (wx != null) {
            weatherIcon.setImageResource(Backdrop.icon(wx.code, wx.day));
            temp.setText(wx.temp + "°");
            condition.setText(wx.label);
        }
        loadPhoto();
    }

    int background() { return Math.floorMod(sp.getInt("home_background", 0), BACKGROUNDS.length); }

    void setBackground(int i) {
        sp.edit().putInt("home_background", Math.floorMod(i, BACKGROUNDS.length)).apply();
        refresh();
    }

    private void loadPhoto() {
        boolean frosted = background() == 1;
        // Frosted sits darker, like the glass over Home, so the white on it reads.
        shade.setBackgroundColor(frosted ? 0x59000000 : 0x26000000);
        io.execute(() -> {
            Bitmap b = Backdrop.wallpaper(getContext(), frosted);
            main.post(() -> photo.setImageBitmap(b));
        });
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            Date now = new Date();
            date.setText(new SimpleDateFormat("EEE, d MMM", Locale.getDefault()).format(now));
            boolean is24 = android.text.format.DateFormat.is24HourFormat(getContext());
            time.setText(new SimpleDateFormat(is24 ? "H:mm" : "h:mm a", Locale.getDefault()).format(now));
            removeCallbacks(this);
            postDelayed(this, 60_000 - now.getTime() % 60_000);
        }
    };

    @Override protected void onDetachedFromWindow() { removeCallbacks(tick); super.onDetachedFromWindow(); }

    private void go() {
        String t = search.getText().toString().trim();
        if (t.isEmpty()) return;
        search.setText("");
        host.open(t);
    }

    // ---- shortcuts: name|url pairs, in order; hold OK on one to remove it

    private List<String[]> list() {
        List<String[]> out = new ArrayList<>();
        for (String s : sp.getString("shortcuts", DEFAULT_SHORTCUTS).split(";")) {
            int bar = s.indexOf('|');
            if (bar > 0) out.add(new String[]{s.substring(0, bar), s.substring(bar + 1)});
        }
        return out;
    }

    private void save(List<String[]> l) {
        StringBuilder b = new StringBuilder();
        for (String[] s : l) b.append(b.length() == 0 ? "" : ";").append(s[0]).append('|').append(s[1]);
        sp.edit().putString("shortcuts", b.toString()).apply();
    }

    private void fillShortcuts() {
        shortcuts.removeAllViews();
        List<String[]> l = list();
        for (int i = 0; i < l.size(); i++) {
            String[] s = l.get(i);
            int index = i;
            ImageView icon = tile(s[0], null, v -> host.open(s[1]), v -> { remove(index, s[0]); return true; });
            favicon(Uri.parse(s[1]).getHost(), icon);
        }
        if (l.size() < 8) tile("Add shortcut", getContext().getDrawable(R.drawable.ic_plus), v -> add(), null);
    }

    /** The icon box with its name under it; returns the icon, for the site's own to be filled in. */
    private ImageView tile(String label, android.graphics.drawable.Drawable d, OnClickListener click,
                           OnLongClickListener hold) {
        Context c = getContext();
        LinearLayout col = column(c);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout boxView = new FrameLayout(c);
        boxView.setBackground(Ui.glass(c, 16, Ui.HOME_GLASS));
        ImageView icon = new ImageView(c);
        if (d != null) {
            icon.setImageDrawable(d);
            icon.setImageTintList(android.content.res.ColorStateList.valueOf(Ui.TEXT));
        }
        boxView.addView(icon, new LayoutParams(dp(28), dp(28), Gravity.CENTER));
        col.addView(boxView, lp(dp(58), dp(58), 0, 0, 0));
        TextView name = Ui.onPhoto(Ui.text(c, 10, 500, Ui.TEXT));
        name.setText(label);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setMaxWidth(dp(80));
        col.addView(name, lp(-2, -2, dp(8), 0, 0));
        col.setOnClickListener(click);
        if (hold != null) col.setOnLongClickListener(hold);
        shortcuts.addView(col, lp(dp(84), -2, 0, dp(5), dp(5)));
        return icon;
    }

    private void add() {
        EditText field = new EditText(getContext());
        field.setSingleLine(true);
        field.setHint("Web address, e.g. youtube.com");
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        new AlertDialog.Builder(getContext(), android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Add shortcut")
                .setView(field)
                .setPositiveButton("Add", (dlg, w) -> {
                    String t = field.getText().toString().trim();
                    if (t.isEmpty()) return;
                    String url = t.matches("(?i)^https?://.*") ? t : "https://" + t;
                    String hostName = Uri.parse(url).getHost();
                    if (hostName == null) return;
                    String name = hostName.replaceFirst("^www\\.", "");
                    name = name.contains(".") ? name.substring(0, name.indexOf('.')) : name;
                    name = name.isEmpty() ? hostName : Character.toUpperCase(name.charAt(0)) + name.substring(1);
                    List<String[]> l = list();
                    l.add(new String[]{name, url});
                    save(l);
                    fillShortcuts();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void remove(int index, String name) {
        new AlertDialog.Builder(getContext(), android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Remove " + name + "?")
                .setPositiveButton("Remove", (dlg, w) -> {
                    List<String[]> l = list();
                    if (index < l.size()) l.remove(index);
                    save(l);
                    fillShortcuts();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** The site's own icon, fetched once and kept. */
    private void favicon(String hostName, ImageView into) {
        if (hostName == null) return;
        File f = new File(new File(getContext().getFilesDir(), "icons"), hostName + ".png");
        io.execute(() -> {
            try {
                if (!f.exists()) {
                    File dir = f.getParentFile();
                    if (dir != null) dir.mkdirs();
                    HttpURLConnection c = (HttpURLConnection) new URL(
                            "https://www.google.com/s2/favicons?sz=128&domain=" + hostName).openConnection();
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(8000);
                    try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(f)) {
                        byte[] buf = new byte[8192];
                        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                    } finally {
                        c.disconnect();
                    }
                }
                Bitmap b = BitmapFactory.decodeFile(f.getPath());
                if (b != null) main.post(() -> into.setImageBitmap(b));
                else f.delete();
            } catch (Exception e) {
                f.delete();
            }
        });
    }

    // ---- layout helpers

    private int dp(float v) { return Ui.dp(getContext(), v); }

    private static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private static LinearLayout.LayoutParams lp(int w, int h, int top, int left, int right) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(left, top, right, 0);
        return p;
    }

    private static LinearLayout.LayoutParams lp(int w, int h, int left, int right) {
        return lp(w, h, 0, left, right);
    }

    private static LayoutParams frame(int w, int h, int gravity, int l, int t, int r, int b) {
        LayoutParams p = new LayoutParams(w, h, gravity);
        p.setMargins(l, t, r, b);
        return p;
    }
}
