package com.beeshtv.browser;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.webkit.WebView;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The browser's own screen: a sidebar on the left (Home, Tabs, History, Settings, Help, About) and
 * the page it picks on the right, with the clock above. Home is the search box and your sites.
 * Everything is clicked with the pointer, like a page.
 */
final class Start extends FrameLayout {
    enum Page { HOME, TABS, HISTORY, SETTINGS, SEARCH, BLOCKING, SITES, LOOK, UPDATES, HELP, ABOUT }

    static final String[] BACKGROUNDS = {"Plain", "Frosted TV photo"};
    /** Nothing pinned at first: the row fills with the sites you visit. */
    static final String DEFAULT_SHORTCUTS = "";
    static final int SITES = 5;

    private final BrowserActivity b;
    private final SharedPreferences sp;
    private final ImageView photo;
    private final View shade;
    private final LinearLayout nav;
    private final FrameLayout content;
    private final View home;
    private final ScrollView scroll;
    private final LinearLayout list;
    private final TextView clock, weather, date;
    private final ImageView weatherIcon;
    private final EditText search;
    private final LinearLayout sites;
    private final View side, time, scrim;
    private final Frost card;
    /** Open over a website as a card on the right, instead of the whole screen. */
    private boolean sheet;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private Page page = Page.HOME;

    Start(BrowserActivity b, SharedPreferences sp) {
        super(b);
        this.b = b;
        this.sp = sp;
        Context c = b;
        setBackground(new Ui.Night());
        photo = new ImageView(c);
        photo.setScaleType(ImageView.ScaleType.CENTER_CROP);
        addView(photo, new LayoutParams(-1, -1));
        shade = new View(c);
        addView(shade, new LayoutParams(-1, -1));

        // Sidebar.
        LinearLayout side = column(c);
        this.side = side;
        side.setPadding(dp(16), dp(28), dp(16), dp(24));
        side.setBackgroundColor(Ui.light ? 0x0A1C1717 : 0x0DF4F1F0);
        LinearLayout brand = new LinearLayout(c);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        brand.setPadding(dp(8), 0, 0, 0);
        ImageView mark = new ImageView(c);
        mark.setImageResource(R.drawable.mark);
        mark.setImageTintList(ColorStateList.valueOf(Ui.TEXT));
        brand.addView(mark, lp(dp(28), dp(28), 0, 0, dp(10), 0));
        LinearLayout names = column(c);
        TextView n1 = Ui.text(c, 16, 500, Ui.TEXT);
        n1.setText("BEESH");
        TextView n2 = Ui.text(c, 13, 400, Ui.TEXT_2);
        n2.setText("Browser");
        names.addView(n1, lp(-2, -2, 0, 0, 0, 0));
        names.addView(n2, lp(-2, -2, 0, dp(4), 0, 0));
        brand.addView(names);
        side.addView(brand, lp(-1, -2, 0, 0, 0, dp(32)));
        nav = column(c);
        side.addView(nav, lp(-1, -2, 0, 0, 0, 0));
        side.addView(new View(c), new LinearLayout.LayoutParams(-1, 0, 1f));
        // Dark or light, at the foot of the sidebar: shows what you'd switch to.
        LinearLayout theme = new LinearLayout(c);
        theme.setGravity(Gravity.CENTER_VERTICAL);
        theme.setPadding(dp(20), 0, dp(12), 0);
        theme.setBackground(Ui.surface(c, 10, 0));
        theme.setClickable(true);
        ImageView ti = new ImageView(c);
        ti.setImageResource(Ui.light ? R.drawable.ic_moon : R.drawable.ic_sun);
        ti.setImageTintList(ColorStateList.valueOf(Ui.TEXT_2));
        theme.addView(ti, lp(dp(18), dp(18), 0, 0, dp(12), 0));
        TextView tt = Ui.text(c, 14, 500, Ui.TEXT_2);
        tt.setText(Ui.light ? "Dark mode" : "Light mode");
        theme.addView(tt);
        theme.setOnClickListener(v -> b.setLight(!Ui.light));
        side.addView(theme, lp(-1, dp(40), 0, 0, 0, 0));
        addView(side, new LayoutParams(dp(SIDEBAR), -1));

        content = new FrameLayout(c);
        addView(content, frame(-1, -1, Gravity.START, dp(SIDEBAR), 0, 0, 0));

        // The clock, top right, set like the one on BeeshTV Home: no panel behind it.
        LinearLayout time = column(c);
        this.time = time;
        time.setGravity(Gravity.END);
        clock = Ui.onPhoto(Ui.text(c, 48, 600, Ui.TEXT));
        clock.setLetterSpacing(0.02f);
        time.addView(clock, lp(-2, -2, 0, 0, 0, 0));
        LinearLayout line = new LinearLayout(c);
        line.setGravity(Gravity.CENTER_VERTICAL);
        weatherIcon = new ImageView(c);
        weatherIcon.setImageTintList(ColorStateList.valueOf(Ui.TEXT_2));
        line.addView(weatherIcon, lp(dp(16), dp(16), 0, 0, dp(6), 0));
        weather = Ui.onPhoto(Ui.text(c, 15, 500, Ui.TEXT_2));
        line.addView(weather);
        date = Ui.onPhoto(Ui.text(c, 15, 500, Ui.TEXT));
        line.addView(date);
        time.addView(line, lp(-2, -2, 0, dp(4), 0, 0));
        content.addView(time, frame(-2, -2, Gravity.TOP | Gravity.END, 0, dp(28), dp(40), 0));

        // Home: the mark and name, search, your sites, in the middle.
        LinearLayout h = column(c);
        h.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout box = new LinearLayout(c);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(dp(18), 0, dp(8), 0);
        box.setBackground(Ui.frost(c, 12));
        ImageView glass = new ImageView(c);
        glass.setImageResource(R.drawable.ic_search);
        glass.setImageTintList(ColorStateList.valueOf(Ui.TEXT_2));
        box.addView(glass, lp(dp(18), dp(18), 0, 0, dp(12), 0));
        search = new EditText(c);
        search.setSingleLine(true);
        search.setBackground(null);
        search.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16);
        search.setTypeface(Ui.face(c, 400));
        search.setTextColor(Ui.TEXT);
        search.setHintTextColor(Ui.TEXT_3);
        search.setHint("Search or type a web address");
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        search.setImeOptions(EditorInfo.IME_ACTION_GO | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        search.setOnEditorActionListener((v, a, e) -> { go(); return true; });
        b.attachKeyboard(search, () -> { if (adding) stopAdding(); });
        box.addView(search, new LinearLayout.LayoutParams(0, -1, 1f));
        ImageView mic = iconButton(R.drawable.ic_mic, "Voice search", v -> b.voice());
        box.addView(mic, lp(dp(36), dp(36), 0, 0, dp(4), 0));
        ImageView arrow = iconButton(R.drawable.ic_arrow, "Go", v -> go());
        box.addView(arrow, lp(dp(36), dp(36), 0, 0, 0, 0));
        h.addView(box, lp(dp(WIDE), dp(52), 0, 0, 0, 0));

        LinearLayout head = new LinearLayout(c);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView sitesLabel = Ui.text(c, 15, 500, Ui.TEXT_2);
        sitesLabel.setText("Sites");
        head.addView(sitesLabel, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView seeHistory = link("History", () -> show(Page.HISTORY));
        head.addView(seeHistory);
        h.addView(head, lp(dp(WIDE), -2, 0, dp(32), 0, dp(12)));
        sites = new LinearLayout(c);
        h.addView(sites, lp(dp(WIDE), -2, 0, 0, 0, 0));
        home = h;
        content.addView(h, frame(-2, -2, Gravity.CENTER, 0, 0, 0, 0));

        // Every other page: a title and rows, scrolling.
        scroll = new ScrollView(c);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setPadding(0, 0, 0, dp(48));
        list = column(c);
        scroll.addView(list, new LayoutParams(-1, -2));
        content.addView(scroll, frame(dp(PAGE), -1, Gravity.START, dp(48), dp(36), 0, 0));

        // Over a website: the page stays in view, dimmed, with the card on the right half.
        scrim = new View(c);
        scrim.setBackgroundColor(Ui.light ? 0x1F000000 : 0x40000000);
        scrim.setClickable(true);
        scrim.setOnClickListener(v -> b.closeSheet());
        scrim.setVisibility(GONE);
        addView(scrim, new LayoutParams(-1, -1));
        // Frosted: the website shows through, blurred, where the card sits.
        card = new Frost(c);
        float cr = dp(22);
        card.corners(cr, cr, cr, cr);
        card.setElevation(dp(16));
        card.setClickable(true);
        card.setVisibility(GONE);
        addView(card, frame(getResources().getDisplayMetrics().widthPixels / 2, -1, Gravity.END, 0, dp(16), dp(16), dp(16)));

        setClickable(true);
        show(Page.HOME);
    }

    boolean isSheet() { return sheet; }

    /** Full screen with the sidebar, or a card over the website (Tabs, History, Settings, Help, About). */
    void sheet(boolean on) { sheet(on, null); }

    /** As a card, frosted with the given blurred picture of the website behind it. */
    void sheet(boolean on, android.graphics.Bitmap backdrop) {
        if (on) card.setBackdrop(backdrop);
        if (sheet == on) return;
        sheet = on;
        setBackground(on ? null : new Ui.Night());
        for (View v : new View[]{photo, shade, side, content}) v.setVisibility(on ? GONE : VISIBLE);
        scrim.setVisibility(on ? VISIBLE : GONE);
        card.setVisibility(on ? VISIBLE : GONE);
        ((android.view.ViewGroup) scroll.getParent()).removeView(scroll);
        if (on) {
            card.addView(scroll, frame(-1, -1, Gravity.START, dp(28), dp(24), dp(28), 0));
            card.setTranslationX(dp(48));
            card.setAlpha(0f);
            card.animate().translationX(0).alpha(1f).setDuration(200).start();
            scrim.setAlpha(0f);
            scrim.animate().alpha(1f).setDuration(200).start();
        } else {
            content.addView(scroll, frame(dp(PAGE), -1, Gravity.START, dp(48), dp(36), 0, 0));
        }
    }

    /** Six site cards of TILE with 8 between them make the width of the search box. */
    static final int SIDEBAR = 200, PAGE = 520, TILE = 88, WIDE = 6 * TILE + 5 * 8;

    Page page() { return page; }

    /** Called when the screen comes up: fresh clock, weather, photo and lists. */
    void refresh() {
        tick.run();
        Backdrop.Weather wx = Backdrop.weather(getContext());
        weatherIcon.setVisibility(wx == null ? GONE : VISIBLE);
        if (wx != null) weatherIcon.setImageResource(Backdrop.icon(wx.code, wx.day));
        weather.setText(wx == null ? "" : wx.temp + "°  ·  ");
        loadPhoto();
    }

    /** Back on this screen: up from a settings page, else to Home. False when already on Home. */
    boolean back() {
        if (menu != null) { closeMenu(); return true; }
        if (isSettings(page)) { show(Page.SETTINGS); return true; }
        // Over a website, Back closes the card; full screen, it goes to Home first.
        if (sheet || page == Page.HOME) return false;
        show(Page.HOME);
        return true;
    }

    void scrollBy(float dy) { if (scroll.getVisibility() == VISIBLE) scroll.scrollBy(0, Math.round(dy)); }

    void focusSearch() {
        show(Page.HOME);
        search.requestFocus();
    }

    // ---------------------------------------------------------------- pages

    void show(Page p) {
        boolean same = page == p;
        closeMenu();
        page = p;
        fillNav();
        boolean isHome = p == Page.HOME;
        home.setVisibility(isHome ? VISIBLE : GONE);
        scroll.setVisibility(isHome ? GONE : VISIBLE);
        if (isHome) { fillSites(); return; }
        list.removeAllViews();
        if (!same) scroll.scrollTo(0, 0);
        switch (p) {
            case TABS: fillTabs(); break;
            case HISTORY: fillHistory(); break;
            case SETTINGS: fillSettings(); break;
            case SEARCH: fillSearch(); break;
            case BLOCKING: fillBlocking(); break;
            case SITES: fillSitesSettings(); break;
            case LOOK: fillLook(); break;
            case UPDATES: fillUpdates(); break;
            case HELP: fillHelp(); break;
            case ABOUT: fillAbout(); break;
            default: break;
        }
    }

    private void fillNav() {
        nav.removeAllViews();
        navRow(R.drawable.ic_home, "Home", null, Page.HOME);
        int open = b.openTabs().size();
        navRow(R.drawable.ic_tabs, "Tabs", open == 0 ? null : String.valueOf(open), Page.TABS);
        navRow(R.drawable.ic_history, "History", null, Page.HISTORY);
        if (b.updater().state() == Updater.State.READY) {
            View u = navItem(R.drawable.ic_update, "Update to " + b.updater().latest(), null, false);
            u.setOnClickListener(v -> b.installUpdate());
        }
        View rule = new View(getContext());
        rule.setBackgroundColor(Ui.HAIRLINE);
        nav.addView(rule, lp(-1, Math.max(1, dp(0.75f)), dp(8), dp(16), dp(8), dp(16)));
        navRow(R.drawable.ic_settings, "Settings", null, Page.SETTINGS);
        navRow(R.drawable.ic_help, "Help", null, Page.HELP);
        navRow(R.drawable.ic_info, "About", null, Page.ABOUT);
        View exit = navItem(R.drawable.ic_exit, "Exit", null, false);
        exit.setOnClickListener(v -> b.exit());
    }

    private void navRow(int icon, String label, String count, Page target) {
        boolean current = page == target || target == Page.SETTINGS && isSettings(page);
        View r = navItem(icon, label, count, current);
        r.setOnClickListener(v -> show(target));
    }

    private static boolean isSettings(Page p) {
        return p == Page.SEARCH || p == Page.BLOCKING || p == Page.SITES || p == Page.LOOK || p == Page.UPDATES;
    }

    /** A sidebar row; the current one sits on a faint frosted step with the red standby dot. */
    private View navItem(int icon, String label, String count, boolean current) {
        Context c = getContext();
        LinearLayout r = new LinearLayout(c);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(8), 0, dp(12), 0);
        r.setBackground(Ui.surface(c, 10, 0));
        r.setSelected(current);
        r.setClickable(true);
        View dot = new View(c);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(Ui.RED);
        dot.setBackground(d);
        dot.setVisibility(current ? VISIBLE : INVISIBLE);
        r.addView(dot, lp(dp(4), dp(4), 0, 0, dp(8), 0));
        ImageView i = new ImageView(c);
        i.setImageResource(icon);
        i.setImageTintList(ColorStateList.valueOf(current ? Ui.TEXT : Ui.TEXT_2));
        r.addView(i, lp(dp(18), dp(18), 0, 0, dp(12), 0));
        TextView t = Ui.text(c, 14, 500, current ? Ui.TEXT : Ui.TEXT_2);
        t.setText(label);
        t.setEllipsize(TextUtils.TruncateAt.END);
        r.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        if (count != null) {
            TextView n = Ui.figures(c, 13, 400, Ui.TEXT_3);
            n.setText(count);
            r.addView(n);
        }
        nav.addView(r, lp(-1, dp(40), 0, 0, 0, dp(4)));
        return r;
    }

    // ---- Home: your sites (pinned first, then where you've been lately)

    private void go() {
        String t = search.getText().toString().trim();
        if (t.isEmpty()) return;
        search.setText("");
        if (adding) {
            String url = t.matches("(?i)^https?://.*") ? t : "https://" + t;
            String host = History.hostOf(url);
            stopAdding();
            getRootView().requestFocus();
            if (host != null) pin(nameOf(host), url);
            return;
        }
        b.open(t);
    }

    /** Add site: the search box takes the address for a moment, so it's typed on our keyboard too. */
    private boolean adding;

    private void add() {
        adding = true;
        search.setHint("Web address to add, like youtube.com");
        search.requestFocus();
    }

    private void stopAdding() {
        adding = false;
        search.setHint("Search or type a web address");
    }

    private List<String[]> pinned() {
        List<String[]> out = new ArrayList<>();
        for (String s : sp.getString("shortcuts", DEFAULT_SHORTCUTS).split(";")) {
            int bar = s.indexOf('|');
            if (bar > 0) out.add(new String[]{s.substring(0, bar), s.substring(bar + 1)});
        }
        return out;
    }

    private void savePinned(List<String[]> l) {
        StringBuilder s = new StringBuilder();
        for (String[] e : l) s.append(s.length() == 0 ? "" : ";").append(e[0]).append('|').append(e[1]);
        sp.edit().putString("shortcuts", s.toString()).apply();
    }

    private void fillSites() {
        sites.removeAllViews();
        List<String[]> pins = pinned();
        List<String> hosts = new ArrayList<>();
        for (String[] p : pins) hosts.add(History.domainOf(History.hostOf(p[1])));
        int shown = 0;
        for (String[] p : pins) {
            if (shown == SITES) break;
            int index = shown;
            siteTile(p[0], p[1], v -> { unpin(index, p[0]); return true; });
            shown++;
        }
        for (String[] r : b.history().recentSites(SITES * 2)) {
            if (shown == SITES) break;
            if (hosts.contains(History.domainOf(r[0]))) continue;
            siteTile(nameOf(r[0]), r[1], v -> { pin(nameOf(r[0]), r[1]); return true; });
            shown++;
        }
        if (shown == 0) {
            // Nothing visited yet: say so, and keep a way to add one by hand.
            TextView none = Ui.text(getContext(), 15, 400, Ui.TEXT_3);
            none.setText("Sites you visit will show up here");
            sites.addView(none, new LinearLayout.LayoutParams(0, -2, 1f));
            sites.setGravity(Gravity.CENTER_VERTICAL);
            sites.addView(link("Add a site", this::add));
            return;
        }
        tile("Add site", getContext().getDrawable(R.drawable.ic_plus), v -> add(), null);
    }

    private void siteTile(String name, String url, OnLongClickListener hold) {
        ImageView icon = tile(name, null, v -> b.openUrl(url), hold);
        favicon(Uri.parse(url).getHost(), icon);
    }

    /** A site card: its icon with its name under it. */
    private ImageView tile(String label, android.graphics.drawable.Drawable d, OnClickListener click, OnLongClickListener hold) {
        Context c = getContext();
        LinearLayout t = column(c);
        t.setGravity(Gravity.CENTER);
        t.setBackground(Ui.surface(c, 12, Ui.FROST));
        ImageView icon = new ImageView(c);
        if (d != null) {
            icon.setImageDrawable(d);
            icon.setImageTintList(ColorStateList.valueOf(Ui.TEXT_2));
        }
        t.addView(icon, lp(dp(28), dp(28), 0, 0, 0, 0));
        TextView name = Ui.text(c, 13, 400, d == null ? Ui.TEXT : Ui.TEXT_2);
        name.setText(label);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setMaxWidth(dp(80));
        t.addView(name, lp(-2, -2, 0, dp(10), 0, 0));
        t.setOnClickListener(click);
        if (hold != null) t.setOnLongClickListener(hold);
        boolean first = sites.getChildCount() == 0;
        sites.addView(t, new LinearLayout.LayoutParams(dp(TILE), dp(88)));
        if (!first) ((LinearLayout.LayoutParams) t.getLayoutParams()).leftMargin = dp(8);
        return icon;
    }

    private static String nameOf(String host) { return History.nameOf(host); }

    private void pin(String name, String url) {
        List<String[]> l = pinned();
        l.add(new String[]{name, url});
        savePinned(l);
        fillSites();
    }

    private void unpin(int index, String name) {
        new AlertDialog.Builder(getContext(), Ui.dialog())
                .setTitle("Remove " + name + " from Home?")
                .setPositiveButton("Remove", (dlg, w) -> {
                    List<String[]> l = pinned();
                    if (index < l.size()) l.remove(index);
                    savePinned(l);
                    fillSites();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ---- Tabs

    private void fillTabs() {
        List<BrowserActivity.Tab> open = b.openTabs();
        title("Tabs", open.isEmpty() ? null : open.size() + " open");
        row(R.drawable.ic_plus, "New tab", null, false, () -> b.newTab());
        gap();
        if (!open.isEmpty()) row(R.drawable.ic_close, "Close all tabs", null, false, () -> {
            b.closeAllTabs();
            show(Page.TABS);
        });
        if (open.isEmpty()) note("Sites you open show up here, so you can switch between them.");
        for (BrowserActivity.Tab t : open) {
            boolean current = t == b.currentTab();
            String url = t.url();
            String host = url == null || url.startsWith("about:") ? null : History.hostOf(url);
            String name = t.title();
            if (name == null || name.isEmpty() || name.startsWith("about:")) name = host == null ? "New tab" : host;
            LinearLayout r = twoLine(name, host == null ? "Home" : host, current);
            r.setOnClickListener(v -> b.switchTab(t));
            ImageView close = iconButton(R.drawable.ic_close, "Close tab", v -> { b.closeTab(t); show(Page.TABS); });
            r.addView(close, lp(dp(36), dp(36), dp(12), 0, 0, 0));
        }
    }

    // ---- History

    private void fillHistory() {
        LinearLayout head = title("History", null);
        if (!b.history().all().isEmpty()) {
            TextView clear = link("Clear history", () -> {
                new AlertDialog.Builder(getContext(), Ui.dialog())
                        .setTitle("Clear history?")
                        .setMessage("Pages you've visited are removed from this TV. Cookies and sign-ins stay.")
                        .setPositiveButton("Clear", (d, w) -> { b.history().clear(); show(Page.HISTORY); })
                        .setNegativeButton("Cancel", null)
                        .show();
            });
            head.addView(clear);
        }
        if (b.history().all().isEmpty()) {
            note("Sites you visit show up here.");
            return;
        }
        String day = null;
        SimpleDateFormat t = new SimpleDateFormat(android.text.format.DateFormat.is24HourFormat(getContext())
                ? "H:mm" : "h:mm a", Locale.getDefault());
        int n = 0;
        for (History.Entry e : b.history().all()) {
            if (n++ == 200) break;
            String d = dayOf(e.at);
            if (!d.equals(day)) { section(d); day = d; }
            LinearLayout r = twoLine(e.title, History.hostOf(e.url), false);
            TextView at = Ui.text(getContext(), 13, 400, Ui.TEXT_3);
            at.setText(t.format(new Date(e.at)).toLowerCase(Locale.ROOT));
            r.addView(at, lp(-2, -2, dp(12), 0, 0, 0));
            r.setOnClickListener(v -> b.openUrl(e.url));
        }
    }

    private static String dayOf(long at) {
        if (DateUtils.isToday(at)) return "Today";
        if (DateUtils.isToday(at + DateUtils.DAY_IN_MILLIS)) return "Yesterday";
        Calendar c = Calendar.getInstance();
        int year = c.get(Calendar.YEAR);
        c.setTimeInMillis(at);
        return new SimpleDateFormat(c.get(Calendar.YEAR) == year ? "EEEE, d MMMM" : "d MMMM yyyy",
                Locale.getDefault()).format(new Date(at));
    }

    // ---- Settings, in categories

    private void fillSettings() {
        title("Settings", null);
        row(R.drawable.ic_search, "Search", b.engineName(), true, () -> show(Page.SEARCH));
        row(R.drawable.ic_shield, "Blocking", b.dns().name() + (b.adblock().enabled() ? " · ads blocked" : ""),
                true, () -> show(Page.BLOCKING));
        row(R.drawable.ic_desktop, "Sites", BrowserActivity.MODES[b.mode()] + " mode", true, () -> show(Page.SITES));
        row(Ui.light ? R.drawable.ic_sun : R.drawable.ic_moon, "Appearance", (Ui.light ? "Light" : "Dark") + " · "
                + BACKGROUNDS[background()], true, () -> show(Page.LOOK));
        row(R.drawable.ic_update, "Updates", updateSummary(), true, () -> show(Page.UPDATES));
    }

    private void backToSettings(String name) {
        LinearLayout up = new LinearLayout(getContext());
        up.setGravity(Gravity.CENTER_VERTICAL);
        up.setPadding(dp(8), 0, dp(12), 0);
        up.setBackground(Ui.surface(getContext(), 8, 0));
        up.setClickable(true);
        ImageView i = new ImageView(getContext());
        i.setImageResource(R.drawable.ic_back);
        i.setImageTintList(ColorStateList.valueOf(Ui.TEXT_3));
        up.addView(i, lp(dp(16), dp(16), 0, 0, dp(6), 0));
        TextView t = Ui.text(getContext(), 13, 500, Ui.TEXT_3);
        t.setText("Settings");
        up.addView(t);
        up.setOnClickListener(v -> show(Page.SETTINGS));
        list.addView(up, lp(-2, dp(32), -dp(8), 0, 0, dp(8)));
        title(name, null);
    }

    private static String[] names(String[][] table) {
        String[] out = new String[table.length];
        for (int i = 0; i < table.length; i++) out[i] = table[i][0];
        return out;
    }

    private void fillSearch() {
        backToSettings("Search");
        choice("Search engine", names(BrowserActivity.ENGINES), b.engine(), b::setEngine);
        note("Anything you type that isn't a web address is searched with this. Voice search uses the "
                + "TV's own speech recognition.");
    }

    private void fillBlocking() {
        backToSettings("Blocking");
        choice("DNS protection", names(Dns.SERVICES), b.dns().service(), i -> b.dns().setService(i));
        toggle("Block ads and trackers", b.adblock().enabled(), on -> b.toggleAds());
        long at = b.adblock().updatedAt();
        row(0, "Block list", String.format(Locale.getDefault(), "%,d sites · %s", b.adblock().size(),
                at == 0 ? "built-in" : "updated " + DateUtils.getRelativeTimeSpanString(at)), false,
                () -> b.adblock().refreshIfDue(true, () -> show(Page.BLOCKING)));
        toggle("Block pop-ups", b.popupsBlocked(), on -> b.togglePopups());
        note("DNS protection checks each site with the service you pick and stops what it blocks. "
                + "The shield in the bar lets ads through on one site that won't work without them.");
    }

    private void fillSitesSettings() {
        backToSettings("Sites");
        choice("Browsing mode", BrowserActivity.MODES, b.mode(), b::setMode);
        note(b.mode() == BrowserActivity.TV
                ? "TV mode asks sites for their phone or TV layout, makes text a little larger and shows a "
                + "glow around what has focus. The desktop button in the bar switches one site to Desktop."
                : "Desktop mode asks sites for their computer layout, at normal text size. The desktop button "
                + "in the bar switches one site back to TV mode.");
        toggle("Lighter pages", b.lighterPages(), on -> b.toggleLighterPages());
        note("Makes busy sites, like streams, smoother on a TV: switches off background blur and pauses "
                + "a page's looping animations while a video plays.");
        row(0, "Clear cookies and site data", null, false, () -> {
            new AlertDialog.Builder(getContext(), Ui.dialog())
                    .setTitle("Clear cookies and site data?")
                    .setMessage("You'll be signed out of every site. Tabs and history stay.")
                    .setPositiveButton("Clear", (d, w) -> b.clearSiteData())
                    .setNegativeButton("Cancel", null)
                    .show();
        });
        note("To open links from other apps here, choose BEESH Browser and Always when the TV asks "
                + "which app to use.");
    }

    int background() { return Math.floorMod(sp.getInt("start_bg", 0), BACKGROUNDS.length); }

    private void fillLook() {
        backToSettings("Appearance");
        choice("Theme", new String[]{"Dark", "Light"}, Ui.light ? 1 : 0, i -> b.setLight(i == 1));
        choice("Keyboard size", Keyboard.SIZES, b.keyboardSize(), b::setKeyboardSize);
        choice("Background", BACKGROUNDS, background(), i -> {
            sp.edit().putInt("start_bg", i).apply();
            loadPhoto();
        });
        note("Frosted TV photo blurs the picture on BeeshTV Launcher's Home the way the launcher blurs "
                + "its panels.");
    }

    String updateSummary() {
        Updater u = b.updater();
        switch (u.state()) {
            case CHECKING: return "Checking";
            case DOWNLOADING: return "Downloading " + u.progress() + "%";
            case READY: return u.latest() + " ready to install";
            case UP_TO_DATE: return "Up to date";
            case FAILED: return u.error();
            default: return "Not checked yet";
        }
    }

    /** Laid out the same as BeeshTV Launcher's Updates page. */
    private void fillUpdates() {
        backToSettings("Updates");
        Updater u = b.updater();
        row(0, "Version", u.installed(), false, null);
        row(0, "Status", updateSummary(), false, null);
        boolean newer = u.latest() != null && Updater.isNewer(u.latest(), u.installed());
        if (u.state() == Updater.State.READY || newer && u.state() == Updater.State.IDLE) {
            row(R.drawable.ic_update, "Download and install " + u.latest(), null, true, b::installUpdate);
        }
        row(0, "Check for updates", null, false, () -> u.check(true));
        toggle("Download updates automatically", u.autoDownload(), u::setAutoDownload);
        if (!u.canInstall()) row(0, "Allow installing updates", "Allow", true,
                () -> getContext().startActivity(u.installPermissionScreen()));
        note("New versions come from github.com/" + Updater.REPO + ". The TV asks you to confirm before "
                + "it installs one.");
    }

    void onUpdateChanged() {
        if (page == Page.UPDATES || page == Page.SETTINGS) show(page);
        else fillNav();
    }

    // ---- Help and About

    private void fillHelp() {
        title("Help", null);
        section("Remote");
        key("Arrows", "Move the pointer. Hold to go faster");
        key("OK", "Click. Hold for a long press");
        key("Edges", "Push the pointer past an edge to scroll");
        key("Back", "Previous page, then this screen");
        key("Menu", "Type in the address bar");
        key("Channel", "Scroll a screen at a time");
        section("Mouse");
        note("A Bluetooth or USB mouse works on its own: move it and its pointer takes over, press an "
                + "arrow on the remote and the remote's pointer comes back.");
        section("Sites on Home");
        note("Hold OK on a site to keep it on Home or remove it. Sites you visit fill the rest of the row.");
    }

    private void key(String key, String what) {
        LinearLayout r = new LinearLayout(getContext());
        r.setGravity(Gravity.CENTER_VERTICAL);
        TextView k = Ui.text(getContext(), 13, 500, Ui.TEXT);
        k.setText(key);
        k.setGravity(Gravity.CENTER);
        k.setPadding(dp(8), dp(4), dp(8), dp(4));
        k.setBackground(Ui.frost(getContext(), 6));
        k.setMinWidth(dp(72));
        r.addView(k, lp(-2, -2, 0, 0, dp(16), 0));
        TextView t = Ui.text(getContext(), 15, 400, Ui.TEXT_2);
        t.setText(what);
        r.addView(t);
        list.addView(r, lp(-1, dp(44), 0, 0, 0, 0));
    }

    /** Laid out the same as BeeshTV Launcher's About page. */
    private void fillAbout() {
        title("About", null);
        LinearLayout who = new LinearLayout(getContext());
        who.setGravity(Gravity.CENTER_VERTICAL);
        ImageView mark = new ImageView(getContext());
        mark.setImageResource(R.drawable.mark);
        mark.setImageTintList(ColorStateList.valueOf(Ui.TEXT));
        who.addView(mark, lp(dp(44), dp(44), 0, 0, dp(16), 0));
        LinearLayout names = column(getContext());
        TextView n = Ui.text(getContext(), 18, 500, Ui.TEXT);
        n.setText("BEESH Browser");
        TextView v = Ui.text(getContext(), 14, 400, Ui.TEXT_2);
        v.setText("Version " + b.updater().installed());
        names.addView(n, lp(-2, -2, 0, 0, 0, 0));
        names.addView(v, lp(-2, -2, 0, dp(6), 0, 0));
        who.addView(names);
        list.addView(who, lp(-1, -2, 0, 0, 0, dp(8)));
        note("A web browser for the TV remote, with ad blocking and DNS protection built in.");
        row(0, "Made by Bishwo Bhusal", "github.com/bishwofic", true,
                () -> b.openUrl("https://github.com/bishwofic"));
        section("This TV");
        String maker = Build.MANUFACTURER.isEmpty() ? "" : Character.toUpperCase(Build.MANUFACTURER.charAt(0)) + Build.MANUFACTURER.substring(1);
        row(0, "Model", maker + " " + Build.MODEL.replace('_', ' '), false, null);
        row(0, "Android", Build.VERSION.RELEASE, false, null);
        android.content.pm.PackageInfo wv = WebView.getCurrentWebViewPackage();
        row(0, "Web engine", wv == null ? "Unknown" : "WebView " + wv.versionName, false, null);
        section("Credits");
        row(0, "Ad and tracker list", "StevenBlack/hosts", false, null);
        row(0, "Typeface", "Schibsted Grotesk", false, null);
    }

    // ---------------------------------------------------------------- page parts

    private LinearLayout title(String text, String meta) {
        LinearLayout h = new LinearLayout(getContext());
        h.setGravity(Gravity.BOTTOM);
        TextView t = Ui.text(getContext(), 26, 500, Ui.TEXT);
        t.setText(text);
        h.addView(t);
        LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(0, 1, 1f);
        if (meta != null) {
            TextView m = Ui.text(getContext(), 15, 400, Ui.TEXT_3);
            m.setText(meta);
            h.addView(m, lp(-2, -2, dp(12), 0, 0, dp(3)));
        }
        h.addView(new View(getContext()), space);
        list.addView(h, lp(-1, dp(40), 0, 0, 0, dp(20)));
        return h;
    }

    private void section(String text) {
        TextView t = Ui.text(getContext(), 13, 500, Ui.TEXT_3);
        t.setText(text);
        list.addView(t, lp(-2, -2, dp(12), dp(24), 0, dp(8)));
    }

    private void gap() { list.addView(new View(getContext()), lp(-1, dp(8), 0, 0, 0, 0)); }

    private void note(String text) {
        TextView t = Ui.text(getContext(), 14, 400, Ui.TEXT_3);
        t.setSingleLine(false);
        t.setLineSpacing(dp(4), 1f);
        t.setText(text);
        list.addView(t, lp(-1, -2, dp(12), dp(16), dp(12), 0));
    }

    /** A settings row: label, its value on the right, a chevron when it opens another page. */
    private void row(int icon, String label, String value, boolean opens, Runnable click) {
        Context c = getContext();
        LinearLayout r = new LinearLayout(c);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(12), 0, dp(12), 0);
        if (click != null) {
            r.setBackground(Ui.surface(c, 10, 0));
            r.setClickable(true);
            r.setOnClickListener(v -> click.run());
        }
        if (icon != 0) {
            ImageView i = new ImageView(c);
            i.setImageResource(icon);
            i.setImageTintList(ColorStateList.valueOf(Ui.TEXT_2));
            r.addView(i, lp(dp(18), dp(18), 0, 0, dp(14), 0));
        }
        TextView l = Ui.text(c, 15, 400, Ui.TEXT);
        l.setText(label);
        l.setEllipsize(TextUtils.TruncateAt.END);
        r.addView(l, new LinearLayout.LayoutParams(0, -2, 1f));
        if (value != null) {
            TextView v = Ui.text(c, 14, 400, Ui.TEXT_2);
            v.setText(value);
            v.setEllipsize(TextUtils.TruncateAt.END);
            r.addView(v, lp(-2, -2, dp(16), 0, 0, 0));
        }
        if (opens) {
            ImageView ch = new ImageView(c);
            ch.setImageResource(R.drawable.ic_chevron);
            ch.setImageTintList(ColorStateList.valueOf(Ui.TEXT_3));
            r.addView(ch, lp(dp(16), dp(16), dp(10), 0, 0, 0));
        }
        list.addView(r, lp(-1, dp(48), 0, 0, 0, dp(2)));
    }

    /** The open dropdown menu, floating over the page, or null. */
    private FrameLayout menu;

    /**
     * A setting with a few choices: the current one in a small box with an arrow, like a dropdown.
     * Clicking drops a small menu down from that box, the current choice ticked.
     */
    private void choice(String label, String[] options, int current, java.util.function.IntConsumer pick) {
        Context c = getContext();
        LinearLayout r = new LinearLayout(c);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(12), 0, dp(8), 0);
        r.setBackground(Ui.surface(c, 10, 0));
        r.setClickable(true);
        TextView l = Ui.text(c, 15, 400, Ui.TEXT);
        l.setText(label);
        r.addView(l, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout box = new LinearLayout(c);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(dp(12), 0, dp(8), 0);
        box.setBackground(Ui.frost(c, 8));
        TextView v = Ui.text(c, 14, 500, Ui.TEXT);
        v.setText(options[Math.max(0, Math.min(current, options.length - 1))]);
        box.addView(v);
        ImageView arrow = new ImageView(c);
        arrow.setImageResource(R.drawable.ic_expand);
        arrow.setImageTintList(ColorStateList.valueOf(Ui.TEXT_2));
        box.addView(arrow, lp(dp(16), dp(16), dp(8), 0, 0, 0));
        r.addView(box, lp(-2, dp(32), dp(16), 0, 0, 0));
        r.setOnClickListener(x -> openMenu(box, options, current, pick));
        list.addView(r, lp(-1, dp(48), 0, 0, 0, dp(2)));
    }

    /** The choices in a small panel just under the box (above it when there's no room below). */
    private void openMenu(View anchor, String[] options, int current, java.util.function.IntConsumer pick) {
        closeMenu();
        Context c = getContext();
        menu = new FrameLayout(c);
        menu.setClickable(true);
        menu.setOnClickListener(v -> closeMenu());
        LinearLayout panel = column(c);
        panel.setPadding(dp(4), dp(4), dp(4), dp(4));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        bg.setColor(Ui.SURFACE);
        panel.setBackground(bg);
        panel.setElevation(dp(8));
        panel.setClickable(true);
        for (int i = 0; i < options.length; i++) {
            int index = i;
            LinearLayout o = new LinearLayout(c);
            o.setGravity(Gravity.CENTER_VERTICAL);
            o.setPadding(dp(12), 0, dp(10), 0);
            o.setBackground(Ui.surface(c, 8, 0));
            o.setClickable(true);
            o.setOnClickListener(x -> { closeMenu(); pick.accept(index); show(page); });
            TextView t = Ui.text(c, 14, i == current ? 500 : 400, i == current ? Ui.TEXT : Ui.TEXT_2);
            t.setText(options[i]);
            o.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
            ImageView tick = new ImageView(c);
            tick.setImageResource(R.drawable.ic_check);
            tick.setImageTintList(ColorStateList.valueOf(Ui.TEXT));
            tick.setVisibility(i == current ? VISIBLE : INVISIBLE);
            o.addView(tick, lp(dp(16), dp(16), dp(12), 0, 0, 0));
            panel.addView(o, lp(-1, dp(40), 0, 0, 0, 0));
        }
        int[] at = new int[2], me = new int[2];
        anchor.getLocationInWindow(at);
        getLocationInWindow(me);
        int w = Math.max(anchor.getWidth(), dp(220)), h = options.length * dp(40) + dp(8);
        int x = at[0] - me[0] + anchor.getWidth() - w;
        int below = at[1] - me[1] + anchor.getHeight() + dp(6);
        int y = below + h <= getHeight() - dp(16) ? below : Math.max(dp(16), at[1] - me[1] - h - dp(6));
        menu.addView(panel, frame(w, -2, Gravity.TOP | Gravity.START, x, y, 0, 0));
        addView(menu, new LayoutParams(-1, -1));
        panel.setAlpha(0f);
        panel.setTranslationY(-dp(4));
        panel.animate().alpha(1f).translationY(0).setDuration(140).start();
    }

    private void closeMenu() {
        if (menu == null) return;
        removeView(menu);
        menu = null;
    }

    /** An on/off setting with a switch. */
    private void toggle(String label, boolean on, java.util.function.Consumer<Boolean> set) {
        Context c = getContext();
        LinearLayout r = new LinearLayout(c);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(12), 0, dp(12), 0);
        r.setBackground(Ui.surface(c, 10, 0));
        r.setClickable(true);
        r.setOnClickListener(v -> { set.accept(!on); show(page); });
        TextView l = Ui.text(c, 15, 400, Ui.TEXT);
        l.setText(label);
        r.addView(l, new LinearLayout.LayoutParams(0, -2, 1f));
        r.addView(new Switch(c, on), lp(dp(40), dp(22), dp(16), 0, 0, 0));
        list.addView(r, lp(-1, dp(48), 0, 0, 0, dp(2)));
    }

    /** A small track and knob: filled with the text colour when on, a matte step when off. */
    static final class Switch extends View {
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final boolean on;

        Switch(Context c, boolean on) { super(c); this.on = on; }

        @Override protected void onDraw(android.graphics.Canvas cv) {
            float w = getWidth(), h = getHeight(), r = h / 2f, pad = h * 0.14f;
            paint.setStyle(android.graphics.Paint.Style.FILL);
            paint.setColor(on ? Ui.TEXT : Ui.HOVER);
            cv.drawRoundRect(0, 0, w, h, r, r, paint);
            paint.setColor(on ? Ui.BG : Ui.TEXT_2);
            float knob = r - pad;
            cv.drawCircle(on ? w - r : r, r, knob, paint);
        }
    }

    /** A row with a name and a quiet line under it, for tabs and history. */
    private LinearLayout twoLine(String top, String bottom, boolean current) {
        Context c = getContext();
        LinearLayout r = new LinearLayout(c);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(12), 0, dp(8), 0);
        r.setBackground(Ui.surface(c, 10, 0));
        r.setSelected(current);
        r.setClickable(true);
        View dot = new View(c);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(Ui.RED);
        dot.setBackground(d);
        dot.setVisibility(current ? VISIBLE : GONE);
        r.addView(dot, lp(dp(4), dp(4), 0, 0, dp(10), 0));
        LinearLayout words = column(c);
        TextView t = Ui.text(c, 15, 400, Ui.TEXT);
        t.setText(top == null ? "" : top);
        t.setEllipsize(TextUtils.TruncateAt.END);
        words.addView(t, lp(-1, -2, 0, 0, 0, 0));
        TextView s = Ui.text(c, 13, 400, Ui.TEXT_3);
        s.setText(bottom == null ? "" : bottom);
        s.setEllipsize(TextUtils.TruncateAt.END);
        words.addView(s, lp(-1, -2, 0, dp(5), 0, 0));
        r.addView(words, new LinearLayout.LayoutParams(0, -2, 1f));
        list.addView(r, lp(-1, dp(60), 0, 0, 0, dp(2)));
        return r;
    }

    private TextView link(String text, Runnable click) {
        TextView t = Ui.text(getContext(), 14, 500, Ui.TEXT_2);
        t.setText(text);
        t.setPadding(dp(10), dp(6), dp(10), dp(6));
        t.setBackground(Ui.surface(getContext(), 8, 0));
        t.setClickable(true);
        t.setOnClickListener(v -> click.run());
        return t;
    }

    private ImageView iconButton(int icon, String label, OnClickListener click) {
        ImageView i = new ImageView(getContext());
        i.setImageResource(icon);
        i.setImageTintList(ColorStateList.valueOf(Ui.TEXT_2));
        i.setPadding(dp(9), dp(9), dp(9), dp(9));
        i.setBackground(Ui.surface(getContext(), 8, 0));
        i.setContentDescription(label);
        i.setOnClickListener(click);
        return i;
    }

    // ---------------------------------------------------------------- background, clock, icons

    private void loadPhoto() {
        if (background() == 0) {
            photo.setImageDrawable(null);
            shade.setBackgroundColor(0);
            return;
        }
        // A veil over the photo so the text on it reads: dark, or pale in light mode.
        shade.setBackgroundColor(Ui.light ? 0xA6F5F2F1 : 0x73000000);
        io.execute(() -> {
            Bitmap p = Backdrop.wallpaper(getContext(), true);
            main.post(() -> { if (background() != 0) photo.setImageBitmap(p); });
        });
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            Date now = new Date();
            boolean is24 = android.text.format.DateFormat.is24HourFormat(getContext());
            SpannableStringBuilder t = new SpannableStringBuilder(
                    new SimpleDateFormat(is24 ? "H:mm" : "h:mm", Locale.getDefault()).format(now));
            if (!is24) {
                int at = t.length();
                t.append(" ").append(new SimpleDateFormat("a", Locale.ENGLISH).format(now).toLowerCase(Locale.ROOT));
                t.setSpan(new RelativeSizeSpan(0.4f), at, t.length(), 0);
                t.setSpan(new ForegroundColorSpan(Ui.TEXT_2), at, t.length(), 0);
            }
            clock.setText(t);
            date.setText(new SimpleDateFormat("d MMM, EEE", Locale.getDefault()).format(now));
            removeCallbacks(this);
            postDelayed(this, 60_000 - now.getTime() % 60_000);
        }
    };

    @Override protected void onDetachedFromWindow() { removeCallbacks(tick); super.onDetachedFromWindow(); }

    /** Site icons in memory, and their own threads so one slow site doesn't hold up the rest. */
    private static final java.util.Map<String, Bitmap> ICONS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final ExecutorService ICON_IO = Executors.newFixedThreadPool(4);

    /** The site's own icon, fetched once and kept. */
    void favicon(String host, ImageView into) {
        if (host == null) return;
        Bitmap known = ICONS.get(host);
        if (known != null) { into.setImageBitmap(known); return; }
        File f = new File(new File(getContext().getFilesDir(), "icons"), host + ".png");
        ICON_IO.execute(() -> {
            try {
                if (!f.exists()) {
                    File dir = f.getParentFile();
                    if (dir != null) dir.mkdirs();
                    HttpURLConnection c = (HttpURLConnection) new URL(
                            "https://www.google.com/s2/favicons?sz=128&domain=" + host).openConnection();
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(8000);
                    try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(f)) {
                        byte[] buf = new byte[8192];
                        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                    } finally {
                        c.disconnect();
                    }
                }
                Bitmap bm = BitmapFactory.decodeFile(f.getPath());
                if (bm != null) {
                    ICONS.put(host, bm);
                    main.post(() -> into.setImageBitmap(bm));
                }
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

    private static LinearLayout.LayoutParams lp(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(l, t, r, b);
        return p;
    }

    private static LayoutParams frame(int w, int h, int gravity, int l, int t, int r, int b) {
        LayoutParams p = new LayoutParams(w, h, gravity);
        p.setMargins(l, t, r, b);
        return p;
    }
}
