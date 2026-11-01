package com.beeshtv.browser;

import android.app.Activity;
import android.app.SearchManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.RecognizerIntent;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.webkit.ScriptHandler;
import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONArray;

import java.io.ByteArrayInputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A browser for the remote. Pages sit under a bar (back, forward, reload, home, the address, voice,
 * ad blocking, desktop site, zoom, tabs, settings); the start screen (Start) has Home, Tabs, History,
 * Settings, Help and About. A pointer moves over everything with the D-pad, like a mouse: OK clicks
 * whatever is under it and pushing it against an edge scrolls. Menu jumps to the address to type.
 */
public class BrowserActivity extends Activity {

    static final String[][] ENGINES = {
            {"Google", "https://www.google.com/search?q="},
            {"DuckDuckGo", "https://duckduckgo.com/?q="},
            {"Bing", "https://www.bing.com/search?q="},
            {"Brave", "https://search.brave.com/search?q="},
    };
    static final float[] ZOOMS = {0.5f, 0.67f, 0.75f, 0.9f, 1f, 1.1f, 1.25f, 1.5f, 1.75f, 2f};
    static final int REQ_VOICE = 7;
    /** Each open page takes memory on a TV that has little; more than this and pages start reloading. */
    static final int MAX_TABS = 6;
    /** Hover is passed to the page at most this often while the pointer moves (players react to every move). */
    static final long HOVER_EVERY_MS = 60;

    /** One open page. Its WebView is made when it's first shown, so restored tabs cost nothing until then. */
    final class Tab {
        WebView web;
        String url, title;
        volatile String host;
        final AtomicInteger blocked = new AtomicInteger();
        ScriptHandler frameFix;

        String url() { return web != null && web.getUrl() != null ? web.getUrl() : url; }
        String title() { return web != null && web.getTitle() != null ? web.getTitle() : title; }
        boolean empty() { String u = url(); return u == null || u.startsWith("about:"); }
    }

    private SharedPreferences sp;
    private AdBlock adblock;
    private Dns dns;
    private Updater updater;
    private History history;
    private final Handler main = new Handler(Looper.getMainLooper());

    private FrameLayout root, pages;
    private Start start;
    private CursorView cursor;
    private LinearLayout bar;
    private EditText address;
    private TextView blockedCount, tabCount, updateBtn;
    private ImageView desktopBtn, shieldBtn;
    private View progress;
    private View fullscreen;
    private WebChromeClient.CustomViewCallback fullscreenDone;

    private final List<Tab> tabs = new ArrayList<>();
    private Tab tab;
    /** The current tab's page. */
    private WebView web;

    private String mobileUA, desktopUA;
    /** The D-pad is moving between the bar's buttons (after Menu) rather than the pointer. */
    private boolean inBar;
    private long touchDownAt, backAt, hoverAt;
    private View hovered;

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        sp = getSharedPreferences("browser", MODE_PRIVATE);
        adblock = new AdBlock(this, sp);
        dns = new Dns(sp);
        dns.warmUp();
        updater = new Updater(this, sp);
        updater.onChange = this::onUpdateChanged;
        history = new History(this);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        String base = WebSettings.getDefaultUserAgent(this);
        // The stock WebView agent says "wv" and "Version/4.0", which some sites treat as a lesser
        // browser; without them it reads as Chrome on Android. Desktop mode reads as Chrome on Linux.
        mobileUA = base.replace("; wv", "").replaceAll("Version/[\\d.]+ ", "");
        Matcher m = Pattern.compile("Chrome/([\\d.]+)").matcher(base);
        String chrome = m.find() ? m.group(1) : "124.0.0.0";
        desktopUA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" + chrome
                + " Safari/537.36";

        // Pages under the bar; the start screen over both; the pointer over everything.
        int barTop = dp(16), barH = dp(56), pageTop = barTop + barH + dp(12);
        root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);
        // Holds focus while the pointer is in charge, so no field takes the D-pad.
        root.setFocusable(true);
        root.setFocusableInTouchMode(true);
        pages = new FrameLayout(this);
        root.addView(pages, lp(-1, -1, Gravity.TOP, 0, pageTop, 0, 0));
        progress = new View(this);
        progress.setBackgroundColor(Ui.RED);
        progress.setPivotX(0);
        progress.setScaleX(0);
        root.addView(progress, lp(-1, dp(2), Gravity.TOP, 0, pageTop, 0, 0));
        root.addView(buildBar(), lp(-1, barH, Gravity.TOP, dp(24), barTop, dp(24), 0));
        start = new Start(this, sp);
        root.addView(start, new FrameLayout.LayoutParams(-1, -1));
        cursor = new CursorView(this);
        cursor.setListener(new CursorView.Listener() {
            @Override public void onMove(float x, float y) { hover(x, y); }
            @Override public void onEdge(float dx, float dy) {
                if (start.getVisibility() == View.VISIBLE) start.scrollBy(dy);
                else mouse(MotionEvent.ACTION_SCROLL, dx, dy);
            }
        });
        int c = CursorView.sizePx(this);
        root.addView(cursor, new FrameLayout.LayoutParams(c, c, Gravity.TOP | Gravity.START));
        setContentView(root);

        restoreTabs();
        String url = urlFrom(getIntent());
        if (url == null) showStart(Start.Page.HOME);
        else openFromOutside(url);
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        String url = urlFrom(i);
        if (url != null) openFromOutside(url);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
        updater.check(false);
        adblock.refreshIfDue(false, null);
        if (start.getVisibility() == View.VISIBLE) start.refresh();
        onUpdateChanged();
    }

    @Override
    protected void onPause() {
        saveTabs();
        if (web != null) web.onPause();
        CookieManager.getInstance().flush();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        for (Tab t : tabs) if (t.web != null) t.web.destroy();
        super.onDestroy();
    }

    /** A link opened from another app, or a search handed to us. */
    private String urlFrom(Intent i) {
        if (i == null) return null;
        if (Intent.ACTION_VIEW.equals(i.getAction()) && i.getData() != null) return i.getDataString();
        if (Intent.ACTION_WEB_SEARCH.equals(i.getAction())) {
            String q = i.getStringExtra(SearchManager.QUERY);
            if (q != null && !q.trim().isEmpty()) return searchOrUrl(q);
        }
        return null;
    }

    /** A link from another app gets its own tab, unless the current one is empty. */
    private void openFromOutside(String url) {
        if (!tab.empty() && tabs.size() < MAX_TABS) select(addTab());
        load(url);
    }

    // ---------------------------------------------------------------- tabs

    List<Tab> tabs() { return tabs; }
    Tab currentTab() { return tab; }

    private Tab addTab() {
        Tab t = new Tab();
        tabs.add(t);
        return t;
    }

    /** Shows a tab; the one it replaces stops playing and is paused, so it can't slow this one down. */
    private void select(Tab t) {
        if (tab != null && tab != t && tab.web != null) {
            WebView old = tab.web;
            old.evaluateJavascript("document.querySelectorAll('video,audio').forEach(function(m){m.pause()})", null);
            old.onPause();
            old.setVisibility(View.INVISIBLE);
            old.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_WAIVED, true);
        }
        tab = t;
        if (t.web == null) {
            t.web = buildWebView(t);
            pages.addView(t.web, new FrameLayout.LayoutParams(-1, -1));
            if (t.url != null) {
                applyAgentFor(t.web, t.url);
                t.web.loadUrl(t.url);
            }
        }
        web = t.web;
        web.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);
        web.onResume();
        web.setVisibility(View.VISIBLE);
        refreshBar();
    }

    void switchTab(Tab t) {
        select(t);
        if (t.empty()) showStart(Start.Page.HOME); else hideStart();
    }

    void newTab() {
        if (tabs.size() >= MAX_TABS) {
            toast("Close a tab first. " + MAX_TABS + " is the most this TV can keep open");
            return;
        }
        select(addTab());
        showStart(Start.Page.HOME);
        start.focusSearch();
    }

    void closeTab(Tab t) {
        int i = tabs.indexOf(t);
        if (i < 0) return;
        tabs.remove(i);
        if (t.web != null) {
            pages.removeView(t.web);
            t.web.destroy();
        }
        if (t == tab) {
            tab = null;
            if (tabs.isEmpty()) addTab();
            select(tabs.get(Math.min(i, tabs.size() - 1)));
        }
        refreshBar();
    }

    private void saveTabs() {
        JSONArray a = new JSONArray();
        for (Tab t : tabs) a.put(t.empty() ? "" : t.url());
        sp.edit().putString("tabs", a.toString()).putInt("tab_now", tabs.indexOf(tab)).apply();
    }

    private void restoreTabs() {
        try {
            JSONArray a = new JSONArray(sp.getString("tabs", "[]"));
            for (int i = 0; i < a.length() && i < MAX_TABS; i++) {
                Tab t = addTab();
                String u = a.getString(i);
                t.url = u.isEmpty() ? null : u;
                t.host = hostOf(t.url);
            }
        } catch (Exception ignored) { }
        if (tabs.isEmpty()) addTab();
        select(tabs.get(Math.max(0, Math.min(sp.getInt("tab_now", 0), tabs.size() - 1))));
    }

    // ---------------------------------------------------------------- a page

    private WebView buildWebView(Tab t) {
        WebView w = new WebView(this);
        w.setBackgroundColor(Ui.BG);
        w.setFocusable(true);
        w.setVerticalScrollBarEnabled(false);
        w.setHorizontalScrollBarEnabled(false);
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        // No pinch zoom on a TV; zoom is per site, from the bar.
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        // Pop-ups come to onCreateWindow: blocked, or opened in this tab when they come from a click.
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setAllowFileAccess(false);
        s.setUserAgentString(mobileUA);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true);
        // WebView tells every site which app it's in (X-Requested-With); players use it to refuse
        // "apps". Send it to nobody.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(s, java.util.Collections.emptySet());
        }
        installFrameFix(t, w);

        w.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                String scheme = u.getScheme() == null ? "" : u.getScheme();
                if (!scheme.equals("http") && !scheme.equals("https")) return openElsewhere(u.toString());
                // Switching between the phone and desktop agent means loading the page afresh.
                if (r.isForMainFrame() && applyAgentFor(v, u.toString())) { v.loadUrl(u.toString()); return true; }
                return false;
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                String host = r.getUrl().getHost();
                // A whole site the DNS service blocks (malware, phishing, and for AdGuard ads too).
                if (r.isForMainFrame()) return dns.blocks(host, true) ? blockedPage(host) : null;
                // Ads and trackers inside the page, unless they're allowed on this site.
                if (adsAllowed(t.host) || !adblock.blocks(host) && !dns.blocks(host, false)) return null;
                int n = t.blocked.incrementAndGet();
                if (t == tab) main.post(() -> { if (t == tab) showBlocked(n); });
                return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
            }

            @Override
            public void onPageStarted(WebView v, String url, Bitmap favicon) {
                t.host = hostOf(url);
                t.blocked.set(0);
                if (t == tab) refreshBar();
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                history.add(url, v.getTitle());
                applyZoom(v);
                if (t == tab) refreshBar();
            }

            @Override
            public boolean onRenderProcessGone(WebView v, android.webkit.RenderProcessGoneDetail d) {
                // The page's renderer crashed or was killed for memory: start over rather than close.
                saveTabs();
                recreate();
                return true;
            }
        });
        w.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView v, int p) {
                if (t != tab) return;
                progress.animate().cancel();
                progress.setAlpha(1f);
                progress.animate().scaleX(p / 100f).setDuration(200).start();
                if (p >= 100) progress.animate().alpha(0f).setStartDelay(300).setDuration(300).start();
            }

            @Override public void onShowCustomView(View v, CustomViewCallback done) {
                if (t == tab) enterFullscreen(v, done); else done.onCustomViewHidden();
            }
            @Override public void onHideCustomView() { exitFullscreen(); }

            @Override public void onPermissionRequest(PermissionRequest r) { r.deny(); }

            /**
             * window.open and target=_blank. A click on a link opens it here, in this tab; anything a
             * page opens on its own is a pop-up, dropped while pop-ups are blocked.
             */
            @Override
            public boolean onCreateWindow(WebView v, boolean dialog, boolean userGesture, android.os.Message result) {
                if (!userGesture && popupsBlocked()) return false;
                WebView catcher = new WebView(BrowserActivity.this);
                catcher.setWebViewClient(new WebViewClient() {
                    @Override public boolean shouldOverrideUrlLoading(WebView c, WebResourceRequest r) {
                        String u = r.getUrl().toString();
                        applyAgentFor(v, u);
                        v.loadUrl(u);
                        c.destroy();
                        return true;
                    }
                });
                ((WebView.WebViewTransport) result.obj).setWebView(catcher);
                result.sendToTarget();
                return true;
            }

            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback cb) {
                cb.invoke(origin, false, false);
            }

            /** No grey play-button placeholder over videos before they start. */
            @Override public Bitmap getDefaultVideoPoster() {
                return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
            }
        });
        w.setDownloadListener((url, ua, disposition, mime, length) ->
                toast("Downloads aren't supported. Open it on another device"));
        return w;
    }

    /**
     * Before any of a page's own scripts, in every frame: keep frames unsandboxed and, with pop-ups
     * blocked, give window.open a stand-in (assets/frames.js). Set again when the pop-up setting changes.
     */
    private void installFrameFix(Tab t, WebView w) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return;
        if (t.frameFix != null) t.frameFix.remove();
        String script;
        try (java.io.InputStream in = getAssets().open("frames.js")) {
            byte[] b = new byte[in.available()];
            int n = in.read(b);
            script = new String(b, 0, Math.max(0, n), StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            return;
        }
        if (popupsBlocked()) script = "window.__beeshtvBlockPopups = true;\n" + script;
        t.frameFix = WebViewCompat.addDocumentStartJavaScript(w, script, java.util.Collections.singleton("*"));
    }

    /** Shown in place of a site the DNS service blocks. */
    private WebResourceResponse blockedPage(String host) {
        String html = "<!doctype html><meta name=viewport content='width=device-width'>"
                + "<body style='margin:0;height:100vh;display:flex;flex-direction:column;align-items:center;"
                + "justify-content:center;background:#0b0a0a;color:#f4f1f0;font:20px system-ui,sans-serif'>"
                + "<div style='font-size:36px;font-weight:500;margin-bottom:14px'>Blocked</div>"
                + "<div style='color:#b8b5b4'>" + TextUtils.htmlEncode(host) + " is blocked by " + dns.name() + "</div>"
                + "<div style='color:#7c7978;margin-top:10px;font-size:16px'>Settings, Blocking, DNS protection picks the service or turns it off</div>";
        return new WebResourceResponse("text/html", "utf-8", new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)));
    }

    private boolean adsAllowed(String host) { return host != null && sp.getBoolean("ads_ok:" + host, false); }

    /** intent:, market:, mailto: and the like go to whatever app handles them, if any. */
    private boolean openElsewhere(String url) {
        try {
            Intent i = url.startsWith("intent:") ? Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                    : new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addCategory(Intent.CATEGORY_BROWSABLE);
            i.setComponent(null);
            startActivity(i);
        } catch (Exception e) {
            toast("No app on this TV opens that link");
        }
        return true;
    }

    /** Type or say anything: a web address opens, the rest is searched. */
    void open(String text) { load(searchOrUrl(text)); }
    void openUrl(String url) { load(url); }

    private void load(String url) {
        hideStart();
        applyAgentFor(web, url);
        web.loadUrl(url);
        address.setText(shown(url));
        exitBar();
    }

    /** A web address opens as is (https:// added); anything else is searched. */
    private String searchOrUrl(String text) {
        String t = text.trim();
        if (t.matches("(?i)^[a-z][a-z0-9+.-]*://.*")) return t;
        if (!t.contains(" ") && (t.matches("(?i)^[a-z0-9-]+(\\.[a-z0-9-]+)+(:\\d+)?(/.*)?$") || t.startsWith("localhost"))) {
            return "https://" + t;
        }
        try {
            return engine()[1] + URLEncoder.encode(t, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return engine()[1] + t;
        }
    }

    private String[] engine() { return ENGINES[Math.floorMod(sp.getInt("engine", 0), ENGINES.length)]; }

    private static String shown(String url) {
        return url == null || url.startsWith("about:") ? "" : url;
    }

    private static String hostOf(String url) {
        if (url == null || url.startsWith("about:")) return null;
        return History.hostOf(url);
    }

    // ---- desktop site and zoom, both remembered per site

    private boolean desktopFor(String host) {
        boolean byDefault = desktopDefault();
        return host == null ? byDefault : sp.getBoolean("desktop:" + host, byDefault);
    }

    /** Sets the user agent this address wants; true if it changed. */
    private boolean applyAgentFor(WebView w, String url) {
        String want = desktopFor(hostOf(url)) ? desktopUA : mobileUA;
        WebSettings s = w.getSettings();
        if (want.equals(s.getUserAgentString())) return false;
        s.setUserAgentString(want);
        return true;
    }

    private void toggleDesktop() {
        String host = hostOf(web.getUrl());
        if (host == null) return;
        boolean on = !desktopFor(host);
        sp.edit().putBoolean("desktop:" + host, on).apply();
        toast(on ? "Desktop site for " + host : "Phone site for " + host);
        applyAgentFor(web, web.getUrl());
        web.reload();
        refreshBar();
    }

    private float zoomFor(String host) { return host == null ? 1f : sp.getFloat("zoom:" + host, 1f); }

    private void stepZoom(int dir) {
        String host = hostOf(web.getUrl());
        if (host == null) return;
        float z = zoomFor(host);
        int i = 0;
        for (int k = 0; k < ZOOMS.length; k++) if (Math.abs(ZOOMS[k] - z) < 0.01f) i = k;
        i = Math.max(0, Math.min(ZOOMS.length - 1, i + dir));
        sp.edit().putFloat("zoom:" + host, ZOOMS[i]).apply();
        applyZoom(web);
        toast("Zoom " + Math.round(ZOOMS[i] * 100) + "%");
    }

    private void applyZoom(WebView w) {
        float z = zoomFor(hostOf(w.getUrl()));
        if (z == 1f && w.getTag() == null) return;
        w.setTag(z == 1f ? null : z);
        w.evaluateJavascript("document.documentElement.style.zoom='" + (z == 1f ? "" : String.valueOf(z)) + "'", null);
    }

    // ---- settings, used by Start

    String engineName() { return engine()[0]; }

    void cycleEngine() {
        sp.edit().putInt("engine", Math.floorMod(sp.getInt("engine", 0) + 1, ENGINES.length)).apply();
        address.setHint("Search " + engineName() + " or type a web address");
    }

    Dns dns() { return dns; }
    AdBlock adblock() { return adblock; }
    Updater updater() { return updater; }
    History history() { return history; }

    void cycleDns() { dns.setService(dns.service() + 1); }

    void toggleAds() {
        adblock.setEnabled(!adblock.enabled());
        refreshBar();
    }

    boolean popupsBlocked() { return sp.getBoolean("block_popups", true); }

    void togglePopups() {
        sp.edit().putBoolean("block_popups", !popupsBlocked()).apply();
        for (Tab t : tabs) if (t.web != null) installFrameFix(t, t.web);
    }

    boolean desktopDefault() { return sp.getBoolean("desktop_default", false); }

    void toggleDesktopDefault() {
        sp.edit().putBoolean("desktop_default", !desktopDefault()).apply();
        refreshBar();
    }

    void clearSiteData() {
        CookieManager.getInstance().removeAllCookies(null);
        WebStorage.getInstance().deleteAllData();
        for (Tab t : tabs) if (t.web != null) t.web.clearCache(true);
        toast("Cookies and site data cleared");
    }

    // ---- full-screen video

    private void enterFullscreen(View v, WebChromeClient.CustomViewCallback done) {
        if (fullscreen != null) { done.onCustomViewHidden(); return; }
        fullscreen = v;
        fullscreenDone = done;
        exitBar();
        // Only the video on screen: the page, bar and pointer underneath aren't drawn meanwhile.
        cursor.setVisibility(View.GONE);
        pages.setVisibility(View.INVISIBLE);
        bar.setVisibility(View.GONE);
        progress.setVisibility(View.GONE);
        root.addView(v, root.indexOfChild(cursor), new FrameLayout.LayoutParams(-1, -1));
    }

    private void exitFullscreen() {
        if (fullscreen == null) return;
        root.removeView(fullscreen);
        fullscreen = null;
        cursor.setVisibility(View.VISIBLE);
        pages.setVisibility(View.VISIBLE);
        bar.setVisibility(View.VISIBLE);
        progress.setVisibility(View.VISIBLE);
        if (fullscreenDone != null) fullscreenDone.onCustomViewHidden();
        fullscreenDone = null;
    }

    // ---------------------------------------------------------------- the pointer

    /** The pointer moved: light up what's under it on our own screens, or pass the hover to the page. */
    private void hover(float x, float y) {
        View hit = null;
        if (start.getVisibility() == View.VISIBLE) hit = Ui.hit(start, x, y);
        else if (y < pages.getTop()) hit = Ui.hit(bar, x, y);
        else hoverPage();
        if (hit == hovered) return;
        if (hovered != null) hovered.setHovered(false);
        if (hit != null) hit.setHovered(true);
        hovered = hit;
    }

    /**
     * Pages hear the pointer a few times a second, not every frame: video players show their controls
     * on every mouse move, and a stream page re-drawing them sixty times a second stalls the whole TV.
     */
    private void hoverPage() {
        main.removeCallbacks(hoverLast);
        long now = SystemClock.uptimeMillis();
        if (now - hoverAt >= HOVER_EVERY_MS) {
            hoverAt = now;
            mouse(MotionEvent.ACTION_HOVER_MOVE, 0, 0);
        } else {
            main.postDelayed(hoverLast, HOVER_EVERY_MS);
        }
    }

    private final Runnable hoverLast = () -> {
        hoverAt = SystemClock.uptimeMillis();
        mouse(MotionEvent.ACTION_HOVER_MOVE, 0, 0);
    };

    /** Hover and wheel scrolls go to the page as a mouse at the pointer, so menus open and inner panes scroll. */
    private void mouse(int action, float dx, float dy) {
        if (web == null) return;
        MotionEvent.PointerProperties pp = new MotionEvent.PointerProperties();
        pp.id = 0;
        pp.toolType = MotionEvent.TOOL_TYPE_MOUSE;
        MotionEvent.PointerCoords pc = new MotionEvent.PointerCoords();
        // In the page's own coordinates; scrolling over the bar scrolls the top of the page.
        pc.x = cursor.pointerX() - pages.getLeft();
        pc.y = Math.max(0, Math.min(pages.getHeight() - 1, cursor.pointerY() - pages.getTop()));
        if (action == MotionEvent.ACTION_SCROLL) {
            ViewConfiguration vc = ViewConfiguration.get(this);
            pc.setAxisValue(MotionEvent.AXIS_VSCROLL, -dy / vc.getScaledVerticalScrollFactor());
            pc.setAxisValue(MotionEvent.AXIS_HSCROLL, dx / vc.getScaledHorizontalScrollFactor());
        }
        long now = SystemClock.uptimeMillis();
        MotionEvent e = MotionEvent.obtain(now, now, action, 1, new MotionEvent.PointerProperties[]{pp},
                new MotionEvent.PointerCoords[]{pc}, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0);
        web.dispatchGenericMotionEvent(e);
        e.recycle();
    }

    /**
     * OK is a finger at the pointer, down when pressed and up when let go (hold = long press), on
     * whatever is there: a button, a field, a site on Home, or the page.
     */
    private void touch(int action) {
        long now = SystemClock.uptimeMillis();
        if (action == MotionEvent.ACTION_DOWN) touchDownAt = now;
        MotionEvent e = MotionEvent.obtain(touchDownAt, now, action, cursor.pointerX(), cursor.pointerY(), 0);
        e.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        root.dispatchTouchEvent(e);
        e.recycle();
    }

    private void scrollPage(int dir) {
        cursor.wake();
        if (start.getVisibility() == View.VISIBLE) start.scrollBy(dir * root.getHeight() * 0.6f);
        else mouse(MotionEvent.ACTION_SCROLL, 0, dir * pages.getHeight() * 0.8f);
    }

    // ---------------------------------------------------------------- keys

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        boolean onStart = start.getVisibility() == View.VISIBLE;

        if (fullscreen != null) {
            if (code == KeyEvent.KEYCODE_BACK) { if (!down) web.evaluateJavascript("document.exitFullscreen()", null); return true; }
            return super.dispatchKeyEvent(e);
        }
        if (code == KeyEvent.KEYCODE_MENU) {
            if (!down) {
                if (onStart) start.focusSearch();
                else if (inBar) exitBar();
                else enterBar();
            }
            return true;
        }
        if (inBar) {
            if (code == KeyEvent.KEYCODE_BACK) { if (!down) exitBar(); return true; }
            if (code == KeyEvent.KEYCODE_DPAD_DOWN) { if (down) exitBar(); return true; }
            if (code == KeyEvent.KEYCODE_DPAD_UP) return true;
            return super.dispatchKeyEvent(e);
        }
        // Typing in a field on the start screen: the keyboard has the keys.
        View f = getCurrentFocus();
        if (onStart && f instanceof EditText && code != KeyEvent.KEYCODE_BACK
                && code != KeyEvent.KEYCODE_DPAD_UP && code != KeyEvent.KEYCODE_DPAD_DOWN) {
            return super.dispatchKeyEvent(e);
        }

        // Moving the pointer up or down leaves a field on the start screen.
        if (onStart && f instanceof EditText && down) { hideKeyboard(f); root.requestFocus(); }

        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP: cursor.press(CursorView.UP, down); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: cursor.press(CursorView.DOWN, down); return true;
            case KeyEvent.KEYCODE_DPAD_LEFT: cursor.press(CursorView.LEFT, down); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: cursor.press(CursorView.RIGHT, down); return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                cursor.wake();
                if (down && e.getRepeatCount() == 0) touch(MotionEvent.ACTION_DOWN);
                else if (!down) touch(MotionEvent.ACTION_UP);
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (down) return true;
                if (onStart) {
                    if (f instanceof EditText) { root.requestFocus(); hideKeyboard(f); return true; }
                    if (start.back()) return true;
                    if (!tab.empty()) { hideStart(); return true; }
                } else {
                    if (web.canGoBack()) { web.goBack(); return true; }
                    showStart(Start.Page.HOME);
                    return true;
                }
                // On Home with nothing behind it, Back twice closes the browser.
                long now = SystemClock.uptimeMillis();
                if (now - backAt < 2000) finish();
                else { backAt = now; toast("Press Back again to close"); }
                return true;
            case KeyEvent.KEYCODE_PAGE_UP:
            case KeyEvent.KEYCODE_CHANNEL_UP:
                if (down) scrollPage(-1);
                return true;
            case KeyEvent.KEYCODE_PAGE_DOWN:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
                if (down) scrollPage(1);
                return true;
        }
        return super.dispatchKeyEvent(e);
    }

    // ---------------------------------------------------------------- the bar

    private View buildBar() {
        bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(6), 0, dp(6), 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        bg.setColor(Ui.SURFACE);
        bg.setStroke(Math.max(1, dp(0.75f)), Ui.HAIRLINE);
        bar.setBackground(bg);

        bar.addView(button(R.drawable.ic_back, "Back", v -> { if (web.canGoBack()) web.goBack(); }));
        bar.addView(button(R.drawable.ic_forward, "Forward", v -> { if (web.canGoForward()) web.goForward(); }));
        bar.addView(button(R.drawable.ic_reload, "Reload", v -> web.reload()));
        bar.addView(button(R.drawable.ic_home, "Home", v -> showStart(Start.Page.HOME)));

        address = new EditText(this);
        address.setSingleLine(true);
        address.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        address.setTypeface(Ui.face(this, 400));
        address.setTextColor(Ui.TEXT);
        address.setHintTextColor(Ui.TEXT_3);
        address.setHint("Search " + engineName() + " or type a web address");
        address.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        address.setImeOptions(EditorInfo.IME_ACTION_GO | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        address.setSelectAllOnFocus(true);
        address.setPadding(dp(14), 0, dp(14), 0);
        address.setCompoundDrawablesRelative(Ui.icon(this, R.drawable.ic_search, Ui.TEXT_3, 16), null, null, null);
        address.setCompoundDrawablePadding(dp(10));
        address.setBackground(Ui.surface(this, 10, Ui.FROST));
        address.setOnEditorActionListener((v, action, ev) -> {
            String t = address.getText().toString();
            if (t.trim().isEmpty()) return true;
            hideKeyboard(address);
            load(searchOrUrl(t));
            return true;
        });
        bar.addView(address, llp(0, dp(40), 1f, dp(8), dp(8)));

        bar.addView(button(R.drawable.ic_mic, "Voice search", v -> voice()));
        // Ads on or off for this site (a site that won't play with ads blocked can be let through).
        shieldBtn = button(R.drawable.ic_shield, "Ad blocking on this site", v -> {
            String h = hostOf(web.getUrl());
            if (h == null) return;
            if (!adblock.enabled()) { toast("Ad blocking is off in Settings"); return; }
            boolean allow = !adsAllowed(h);
            sp.edit().putBoolean("ads_ok:" + h, allow).apply();
            toast(allow ? "Ads allowed on " + h : "Blocking ads on " + h);
            web.reload();
            refreshBar();
        });
        bar.addView(shieldBtn);
        blockedCount = Ui.figures(this, 13, 400, Ui.TEXT_3);
        bar.addView(blockedCount, llp(-2, -2, 0f, 0, dp(4)));
        desktopBtn = button(R.drawable.ic_desktop, "Desktop site", v -> toggleDesktop());
        bar.addView(desktopBtn);
        bar.addView(button(R.drawable.ic_zoom_out, "Zoom out", v -> stepZoom(-1)));
        bar.addView(button(R.drawable.ic_zoom_in, "Zoom in", v -> stepZoom(1)));
        bar.addView(button(R.drawable.ic_tabs, "Tabs", v -> showStart(Start.Page.TABS)));
        tabCount = Ui.figures(this, 13, 400, Ui.TEXT_3);
        bar.addView(tabCount, llp(-2, -2, 0f, 0, dp(4)));

        updateBtn = Ui.text(this, 13, 500, Ui.TEXT);
        updateBtn.setPadding(dp(12), dp(8), dp(12), dp(8));
        updateBtn.setBackground(Ui.surface(this, 8, Ui.FROST));
        updateBtn.setFocusable(true);
        updateBtn.setClickable(true);
        updateBtn.setOnClickListener(v -> installUpdate());
        updateBtn.setVisibility(View.GONE);
        bar.addView(updateBtn, llp(-2, -2, 0f, dp(4), dp(4)));

        bar.addView(button(R.drawable.ic_settings, "Settings", v -> showStart(Start.Page.SETTINGS)));
        return bar;
    }

    /** The bar shows the current tab: its address, what's blocked, desktop or phone site. */
    private void refreshBar() {
        if (tab == null || address == null) return;
        if (!address.hasFocus()) address.setText(shown(tab.url()));
        String h = tab.host;
        boolean site = h != null;
        boolean shielded = adblock.enabled() && !adsAllowed(h);
        shieldBtn.setImageTintList(ColorStateList.valueOf(shielded ? Ui.TEXT : Ui.TEXT_3));
        showBlocked(tab.blocked.get());
        desktopBtn.setSelected(site && desktopFor(h));
        desktopBtn.setImageTintList(ColorStateList.valueOf(desktopBtn.isSelected() ? Ui.TEXT : Ui.TEXT_2));
        tabCount.setText(String.valueOf(tabs.size()));
    }

    /** Menu: the D-pad moves between the bar's buttons, starting at the address, until Down or Back. */
    private void enterBar() {
        inBar = true;
        address.setText(shown(web.getUrl()));
        address.requestFocus();
    }

    /** Back to the pointer. */
    private void exitBar() {
        inBar = false;
        hideKeyboard(address);
        if (address.hasFocus()) address.setText(shown(web.getUrl()));
        web.requestFocus();
        cursor.wake();
    }

    private void hideKeyboard(View v) {
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
    }

    private void showBlocked(int n) {
        blockedCount.setText(n > 0 && adblock.enabled() && !adsAllowed(tab.host) ? String.valueOf(n) : "");
    }

    // ---- the start screen

    void showStart(Start.Page p) {
        // Nothing keeps playing behind it.
        if (web != null) web.evaluateJavascript("document.querySelectorAll('video,audio').forEach(function(m){m.pause()})", null);
        exitBar();
        if (start.getVisibility() != View.VISIBLE) {
            start.setVisibility(View.VISIBLE);
            start.setAlpha(0f);
            start.animate().alpha(1f).setDuration(160).start();
        }
        start.show(p);
        start.refresh();
        pages.setVisibility(View.INVISIBLE);
        hovered = null;
        root.requestFocus();
    }

    private void hideStart() {
        start.setVisibility(View.GONE);
        pages.setVisibility(View.VISIBLE);
        hovered = null;
        refreshBar();
    }

    void voice() {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
                .putExtra(RecognizerIntent.EXTRA_PROMPT, "Say a search or a website");
        try {
            startActivityForResult(i, REQ_VOICE);
        } catch (ActivityNotFoundException e) {
            toast("Voice search isn't available on this TV");
        }
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req != REQ_VOICE || result != RESULT_OK || data == null) return;
        ArrayList<String> said = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        if (said != null && !said.isEmpty()) load(searchOrUrl(said.get(0)));
    }

    // ---------------------------------------------------------------- updates

    private void onUpdateChanged() {
        if (updateBtn == null) return;
        boolean ready = updater.state() == Updater.State.READY;
        updateBtn.setText("Update to " + updater.latest());
        updateBtn.setVisibility(ready ? View.VISIBLE : View.GONE);
        if (start != null) start.onUpdateChanged();
    }

    void installUpdate() {
        if (!updater.canInstall()) {
            toast("Allow BeeshTV Browser to install apps, then press Back and choose Update again");
            startActivity(updater.installPermissionScreen());
            return;
        }
        if (updater.state() == Updater.State.READY) updater.install(); else updater.download();
    }

    // ---------------------------------------------------------------- small helpers

    private ImageView button(int icon, String label, View.OnClickListener click) {
        ImageView b = new ImageView(this);
        b.setImageResource(icon);
        b.setImageTintList(ColorStateList.valueOf(Ui.TEXT_2));
        b.setPadding(dp(11), dp(11), dp(11), dp(11));
        b.setFocusable(true);
        b.setContentDescription(label);
        b.setBackground(Ui.surface(this, 10, 0));
        b.setOnClickListener(click);
        b.setLayoutParams(llp(dp(42), dp(42), 0f, dp(1), dp(1)));
        return b;
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    private int dp(float v) { return Ui.dp(this, v); }

    private static FrameLayout.LayoutParams lp(int w, int h, int gravity, int l, int t, int r, int b) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(w, h, gravity);
        p.setMargins(l, t, r, b);
        return p;
    }

    private static LinearLayout.LayoutParams llp(int w, int h, float weight, int l, int r) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h, weight);
        p.setMargins(l, 0, r, 0);
        return p;
    }
}
