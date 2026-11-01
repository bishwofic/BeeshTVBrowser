package com.beeshtv.browser;

import android.app.Activity;
import android.app.SearchManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.speech.RecognizerIntent;
import android.text.InputType;
import android.text.TextUtils;
import android.text.format.DateUtils;
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
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.webkit.ScriptHandler;
import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import java.io.ByteArrayInputStream;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A browser for the remote. The bar sits above the page: back, forward, reload, the address (a web
 * address opens, anything else is searched), voice, ad blocking, desktop site, zoom and settings.
 * A pointer moves over everything with the D-pad, like a mouse: OK clicks whatever is under it, the
 * bar's buttons included, and pushing it against an edge scrolls the page. Menu jumps to the
 * address to type.
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

    // White on glass, as on BeeshTV's Home.
    static final int TEXT = 0xFFFFFFFF, TEXT_2 = 0xCCFFFFFF, FOCUS = 0x3DFFFFFF, CURRENT = 0x1FFFFFFF;
    static final int GLASS = 0xEB151518, AMBER = 0xFFFFB01F;

    private SharedPreferences sp;
    private AdBlock adblock;
    private Dns dns;
    private Home home;
    private boolean onHome;
    /** The site in the tab now, read on the network threads for its ad-blocking exception. */
    private volatile String pageHost;
    private ScriptHandler frameFix;
    private Updater updater;
    private FrameLayout root;
    private WebView web;
    private CursorView cursor;
    private LinearLayout bar, panel;
    private ScrollView panelScroll;
    private EditText address;
    private TextView blockedCount, updateChip;
    private ImageView desktopBtn, settingsBtn, shieldBtn, zoomOutBtn, zoomInBtn;
    private View progress;
    private View fullscreen;
    private WebChromeClient.CustomViewCallback fullscreenDone;
    private final AtomicInteger blocked = new AtomicInteger();
    private String mobileUA, desktopUA;
    /** The D-pad is moving between the bar's buttons (after Menu) rather than the pointer. */
    private boolean inBar;
    private long touchDownAt, backAt;
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
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        // The bar is always there, above the page; the pointer moves over both.
        int barTop = dp(14), barH = dp(60), pageTop = barTop + barH + dp(12);
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0B0B0D);
        // The home page fills the whole screen, under the bar; pages show below the bar.
        home = new Home(this, sp, pageTop, text -> load(searchOrUrl(text)));
        home.setVisibility(View.GONE);
        root.addView(home, new FrameLayout.LayoutParams(-1, -1));
        web = buildWebView();
        root.addView(web, lp(-1, -1, Gravity.TOP, 0, pageTop, 0, 0));
        progress = new View(this);
        progress.setBackgroundColor(AMBER);
        progress.setPivotX(0);
        progress.setScaleX(0);
        root.addView(progress, lp(-1, dp(3), Gravity.TOP, 0, pageTop, 0, 0));
        root.addView(buildBar(), lp(-1, barH, Gravity.TOP, dp(28), barTop, dp(28), 0));
        root.addView(buildPanel(), lp(dp(440), -1, Gravity.END, 0, pageTop, dp(28), dp(18)));
        cursor = new CursorView(this);
        cursor.setListener(new CursorView.Listener() {
            @Override public void onMove(float x, float y) { hover(x, y); }
            @Override public void onEdge(float dx, float dy) { mouse(MotionEvent.ACTION_SCROLL, dx, dy); }
        });
        int c = CursorView.sizePx(this);
        root.addView(cursor, new FrameLayout.LayoutParams(c, c, Gravity.TOP | Gravity.START));
        setContentView(root);

        String url = urlFrom(getIntent());
        if (url == null) showHome();
        else load(url);
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        String url = urlFrom(i);
        if (url != null) load(url);
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
        // Home's photo may have changed while we were away.
        if (onHome) home.refresh();
        updater.check(false);
        adblock.refreshIfDue(false, null);
        onUpdateChanged();
    }

    @Override
    protected void onPause() {
        web.onPause();
        CookieManager.getInstance().flush();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        web.destroy();
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

    // ---------------------------------------------------------------- the page

    private WebView buildWebView() {
        WebView w = new WebView(this);
        w.setBackgroundColor(0xFF0B0B0D);
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
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true);

        // The stock WebView agent says "wv" and "Version/4.0", which some sites treat as a lesser
        // browser; without them it reads as Chrome on Android. Desktop mode reads as Chrome on Linux.
        String base = WebSettings.getDefaultUserAgent(this);
        mobileUA = base.replace("; wv", "").replaceAll("Version/[\\d.]+ ", "");
        Matcher m = Pattern.compile("Chrome/([\\d.]+)").matcher(base);
        String chrome = m.find() ? m.group(1) : "124.0.0.0";
        desktopUA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" + chrome
                + " Safari/537.36";
        s.setUserAgentString(mobileUA);
        // WebView tells every site which app it's in (X-Requested-With); players use it to refuse
        // "apps". Send it to nobody.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(s, java.util.Collections.emptySet());
        }
        installFrameFix(w);

        w.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                String scheme = u.getScheme() == null ? "" : u.getScheme();
                if (!scheme.equals("http") && !scheme.equals("https")) return openElsewhere(u.toString());
                // Switching between the phone and desktop agent means loading the page afresh.
                if (r.isForMainFrame() && applyAgentFor(u.toString())) { v.loadUrl(u.toString()); return true; }
                return false;
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                String host = r.getUrl().getHost();
                // A whole site the DNS service blocks (malware, phishing, and for AdGuard ads too).
                if (r.isForMainFrame()) return dns.blocks(host, true) ? blockedPage(host) : null;
                // Ads and trackers inside the page, unless they're allowed on this site.
                if (adsAllowed(pageHost) || !adblock.blocks(host) && !dns.blocks(host, false)) return null;
                int n = blocked.incrementAndGet();
                runOnUiThread(() -> showBlocked(n));
                return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
            }

            @Override
            public void onPageStarted(WebView v, String url, Bitmap favicon) {
                pageHost = hostOf(url);
                blocked.set(0);
                showBlocked(0);
                paintShield();
                if (!address.hasFocus() && !onHome) address.setText(shown(url));
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                if (!address.hasFocus()) address.setText(shown(url));
                applyZoom();
                paintDesktop();
            }

            @Override
            public boolean onRenderProcessGone(WebView v, android.webkit.RenderProcessGoneDetail d) {
                // The page's renderer crashed or was killed for memory: start over rather than close.
                recreate();
                return true;
            }
        });
        w.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView v, int p) {
                progress.animate().cancel();
                progress.setAlpha(1f);
                progress.animate().scaleX(p / 100f).setDuration(200).start();
                if (p >= 100) progress.animate().alpha(0f).setStartDelay(300).setDuration(300).start();
            }

            @Override public void onShowCustomView(View v, CustomViewCallback done) { enterFullscreen(v, done); }
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
                        load(r.getUrl().toString());
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
                toast("Downloads aren't supported. Open it on another device."));
        return w;
    }

    /**
     * Before any of a page's own scripts, in every frame: keep frames unsandboxed and, with pop-ups
     * blocked, give window.open a stand-in (assets/frames.js). Set again when the pop-up setting changes.
     */
    private void installFrameFix(WebView w) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return;
        if (frameFix != null) frameFix.remove();
        String script;
        try (java.io.InputStream in = getAssets().open("frames.js")) {
            byte[] b = new byte[in.available()];
            int n = in.read(b);
            script = new String(b, 0, Math.max(0, n), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            return;
        }
        if (popupsBlocked()) script = "window.__beeshtvBlockPopups = true;\n" + script;
        frameFix = WebViewCompat.addDocumentStartJavaScript(w, script, java.util.Collections.singleton("*"));
    }

    private boolean popupsBlocked() { return sp.getBoolean("block_popups", true); }

    /** Shown in place of a site the DNS service blocks. */
    private WebResourceResponse blockedPage(String host) {
        String html = "<!doctype html><meta name=viewport content='width=device-width'>"
                + "<body style='margin:0;height:100vh;display:flex;flex-direction:column;align-items:center;"
                + "justify-content:center;background:#0b0b0d;color:#f5f5f5;font:20px system-ui,sans-serif'>"
                + "<div style='font-size:44px;font-weight:700;margin-bottom:14px'>Blocked</div>"
                + "<div style='color:#c8c8cc'>" + TextUtils.htmlEncode(host) + " is blocked by " + dns.name() + ".</div>"
                + "<div style='color:#8c8c92;margin-top:10px;font-size:16px'>Settings → DNS protection chooses the service, or turns it off.</div>";
        return new WebResourceResponse("text/html", "utf-8", new ByteArrayInputStream(html.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
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

    private void load(String url) {
        if (onHome) hideHome();
        applyAgentFor(url);
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
        return url == null || url.equals("about:blank") ? "" : url;
    }

    private static String hostOf(String url) {
        if (url == null || url.startsWith("about:")) return null;
        String h = Uri.parse(url).getHost();
        return h == null ? null : h.startsWith("www.") ? h.substring(4) : h;
    }

    // ---- desktop site and zoom, both remembered per site

    private boolean desktopFor(String host) {
        boolean byDefault = sp.getBoolean("desktop_default", false);
        return host == null ? byDefault : sp.getBoolean("desktop:" + host, byDefault);
    }

    /** Sets the user agent this address wants; true if it changed. */
    private boolean applyAgentFor(String url) {
        String want = desktopFor(hostOf(url)) ? desktopUA : mobileUA;
        WebSettings s = web.getSettings();
        if (want.equals(s.getUserAgentString())) return false;
        s.setUserAgentString(want);
        return true;
    }

    private void toggleDesktop() {
        String host = hostOf(web.getUrl());
        if (host == null) { toast("Open a site first"); return; }
        boolean on = !desktopFor(host);
        sp.edit().putBoolean("desktop:" + host, on).apply();
        toast(on ? "Desktop site for " + host : "Phone site for " + host);
        applyAgentFor(web.getUrl());
        web.reload();
        paintDesktop();
    }

    private void paintDesktop() {
        if (desktopBtn == null) return;
        boolean on = desktopFor(hostOf(web.getUrl()));
        desktopBtn.setImageTintList(ColorStateList.valueOf(on ? AMBER : TEXT));
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
        applyZoom();
        toast("Zoom " + Math.round(ZOOMS[i] * 100) + "%");
    }

    private void applyZoom() {
        float z = zoomFor(hostOf(web.getUrl()));
        web.evaluateJavascript("document.documentElement.style.zoom='" + (z == 1f ? "" : String.valueOf(z)) + "'", null);
    }

    // ---- full-screen video

    private void enterFullscreen(View v, WebChromeClient.CustomViewCallback done) {
        if (fullscreen != null) { done.onCustomViewHidden(); return; }
        fullscreen = v;
        fullscreenDone = done;
        exitBar();
        // Only the video on screen: the page, bar and pointer underneath aren't drawn meanwhile.
        cursor.setVisibility(View.GONE);
        web.setVisibility(View.INVISIBLE);
        bar.setVisibility(View.GONE);
        progress.setVisibility(View.GONE);
        root.addView(v, new FrameLayout.LayoutParams(-1, -1));
    }

    private void exitFullscreen() {
        if (fullscreen == null) return;
        root.removeView(fullscreen);
        fullscreen = null;
        cursor.setVisibility(View.VISIBLE);
        bar.setVisibility(View.VISIBLE);
        if (!onHome) {
            web.setVisibility(View.VISIBLE);
            progress.setVisibility(View.VISIBLE);
        }
        if (fullscreenDone != null) fullscreenDone.onCustomViewHidden();
        fullscreenDone = null;
    }

    // ---------------------------------------------------------------- the pointer

    /** The pointer moved: hover the page under it, or light up the bar button under it. */
    private void hover(float x, float y) {
        View hit = null;
        if (y < web.getTop()) {
            float bx = x - bar.getLeft(), by = y - bar.getTop();
            for (int i = 0; i < bar.getChildCount(); i++) {
                View v = bar.getChildAt(i);
                if (v.getVisibility() == View.VISIBLE && v.isClickable() && bx >= v.getLeft() && bx < v.getRight()
                        && by >= v.getTop() && by < v.getBottom()) hit = v;
            }
        } else {
            mouse(MotionEvent.ACTION_HOVER_MOVE, 0, 0);
        }
        if (hit == hovered) return;
        if (hovered != null) paintHover(hovered, false);
        if (hit != null) paintHover(hit, true);
        hovered = hit;
    }

    private void paintHover(View v, boolean on) {
        if (v.getBackground() instanceof GradientDrawable && !(v == updateChip)) {
            ((GradientDrawable) v.getBackground()).setColor(on || v.hasFocus() ? FOCUS : v == address ? CURRENT : Color.TRANSPARENT);
        }
    }

    /** Hover and wheel scrolls go to the page as a mouse at the pointer, so menus open and inner panes scroll. */
    private void mouse(int action, float dx, float dy) {
        MotionEvent.PointerProperties pp = new MotionEvent.PointerProperties();
        pp.id = 0;
        pp.toolType = MotionEvent.TOOL_TYPE_MOUSE;
        MotionEvent.PointerCoords pc = new MotionEvent.PointerCoords();
        // In the page's own coordinates; scrolling over the bar scrolls the top of the page.
        pc.x = cursor.pointerX() - web.getLeft();
        pc.y = Math.max(0, Math.min(web.getHeight() - 1, cursor.pointerY() - web.getTop()));
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
     * whatever is there: a bar button, the address, or the page.
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
        mouse(MotionEvent.ACTION_SCROLL, 0, dir * web.getHeight() * 0.8f);
    }

    // ---------------------------------------------------------------- keys

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;

        if (fullscreen != null) {
            if (code == KeyEvent.KEYCODE_BACK) { if (!down) web.evaluateJavascript("document.exitFullscreen()", null); return true; }
            return super.dispatchKeyEvent(e);
        }
        if (panel.getVisibility() == View.VISIBLE) {
            if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_MENU) { if (!down) hidePanel(); return true; }
            return super.dispatchKeyEvent(e);
        }
        if (code == KeyEvent.KEYCODE_MENU) {
            if (!down) { if (inBar) exitBar(); else enterBar(); }
            return true;
        }
        if (inBar) {
            if (code == KeyEvent.KEYCODE_BACK) { if (!down) exitBar(); return true; }
            if (code == KeyEvent.KEYCODE_DPAD_DOWN) { if (down) exitBar(); return true; }
            if (code == KeyEvent.KEYCODE_DPAD_UP) return true;
            return super.dispatchKeyEvent(e);
        }

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
                if (!onHome && web.canGoBack()) { web.goBack(); return true; }
                if (!onHome) { showHome(); return true; }
                // On the home page, Back twice closes the browser.
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
        bar.setPadding(dp(10), 0, dp(10), 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(30));
        bg.setColor(GLASS);
        bg.setStroke(Math.max(1, dp(0.75f)), 0x26FFFFFF);
        bar.setBackground(bg);
        bar.setElevation(dp(6));

        bar.addView(button(R.drawable.ic_back, "Back", v -> { if (web.canGoBack()) web.goBack(); }));
        bar.addView(button(R.drawable.ic_forward, "Forward", v -> { if (web.canGoForward()) web.goForward(); }));
        bar.addView(button(R.drawable.ic_reload, "Reload", v -> { if (!onHome) web.reload(); }));
        bar.addView(button(R.drawable.ic_home, "Home", v -> showHome()));

        address = new EditText(this);
        address.setSingleLine(true);
        address.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        address.setTypeface(face(500));
        address.setTextColor(TEXT);
        address.setHintTextColor(0x99FFFFFF);
        address.setHint("Search " + engine()[0] + " or type a web address");
        address.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        address.setImeOptions(EditorInfo.IME_ACTION_GO | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        address.setSelectAllOnFocus(true);
        address.setPadding(dp(18), 0, dp(18), 0);
        address.setCompoundDrawablesRelative(icon(R.drawable.ic_search, TEXT_2, 18), null, null, null);
        address.setCompoundDrawablePadding(dp(10));
        GradientDrawable field = new GradientDrawable();
        field.setCornerRadius(dp(22));
        field.setColor(CURRENT);
        address.setBackground(field);
        address.setOnFocusChangeListener((v, has) -> {
            field.setColor(has ? FOCUS : CURRENT);
            field.setStroke(has ? dp(2) : 0, TEXT);
        });
        address.setOnEditorActionListener((v, action, ev) -> {
            String t = address.getText().toString();
            if (t.trim().isEmpty()) return true;
            InputMethodManager imm = getSystemService(InputMethodManager.class);
            if (imm != null) imm.hideSoftInputFromWindow(address.getWindowToken(), 0);
            load(searchOrUrl(t));
            return true;
        });
        bar.addView(address, llp(0, dp(44), 1f, dp(8), dp(8)));

        bar.addView(button(R.drawable.ic_mic, "Voice search", v -> voice()));
        // Ads on or off for this site (a site that won't play with ads blocked can be let through).
        shieldBtn = button(R.drawable.ic_shield, "Ad blocking on this site", v -> {
            String h = hostOf(web.getUrl());
            if (h == null) return;
            if (!adblock.enabled()) { toast("Ad blocking is off in Settings"); return; }
            boolean allow = !adsAllowed(h);
            sp.edit().putBoolean("ads_ok:" + h, allow).apply();
            toast(allow ? "Ads allowed on " + h : "Blocking ads on " + h);
            paintShield();
            web.reload();
        });
        bar.addView(shieldBtn);
        blockedCount = text(13, 700, AMBER);
        bar.addView(blockedCount, llp(-2, -2, -dp(2), dp(6)));
        desktopBtn = button(R.drawable.ic_desktop, "Desktop site", v -> toggleDesktop());
        bar.addView(desktopBtn);
        zoomOutBtn = button(R.drawable.ic_zoom_out, "Zoom out", v -> stepZoom(-1));
        zoomInBtn = button(R.drawable.ic_zoom_in, "Zoom in", v -> stepZoom(1));
        bar.addView(zoomOutBtn);
        bar.addView(zoomInBtn);

        updateChip = text(13, 700, 0xFF111111);
        updateChip.setPadding(dp(14), dp(7), dp(14), dp(7));
        updateChip.setFocusable(true);
        GradientDrawable ub = new GradientDrawable();
        ub.setCornerRadius(dp(16));
        ub.setColor(0xD9FFFFFF);
        updateChip.setBackground(ub);
        updateChip.setOnFocusChangeListener((v, has) -> ub.setColor(has ? 0xFFFFFFFF : 0xD9FFFFFF));
        updateChip.setOnClickListener(v -> installUpdate());
        updateChip.setVisibility(View.GONE);
        bar.addView(updateChip, llp(-2, -2, dp(6), dp(6)));

        settingsBtn = button(R.drawable.ic_settings, "Settings", v -> showPanel());
        bar.addView(settingsBtn);
        paintShield();
        return bar;
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
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (imm != null) imm.hideSoftInputFromWindow(address.getWindowToken(), 0);
        if (address.hasFocus()) address.setText(onHome ? "" : shown(web.getUrl()));
        if (onHome) home.requestFocus(); else web.requestFocus();
        cursor.wake();
    }

    private void showBlocked(int n) {
        blockedCount.setText(n > 0 && adblock.enabled() ? String.valueOf(n) : "");
    }

    private void paintShield() {
        boolean on = adblock.enabled() && !adsAllowed(hostOf(web.getUrl()));
        shieldBtn.setImageTintList(ColorStateList.valueOf(on ? AMBER : TEXT_2));
    }

    // ---- the home page

    private void showHome() {
        onHome = true;
        // Nothing keeps playing behind it.
        web.evaluateJavascript("document.querySelectorAll('video,audio').forEach(function(m){m.pause()})", null);
        web.setVisibility(View.INVISIBLE);
        progress.setVisibility(View.INVISIBLE);
        home.setVisibility(View.VISIBLE);
        home.refresh();
        address.setText("");
        setHomeMode(true);
        exitBar();
    }

    private void hideHome() {
        onHome = false;
        home.setVisibility(View.GONE);
        web.setVisibility(View.VISIBLE);
        progress.setVisibility(View.VISIBLE);
        setHomeMode(false);
    }

    /** On the home page the bar is see-through and only has what makes sense without a site. */
    private void setHomeMode(boolean on) {
        for (View v : new View[]{shieldBtn, blockedCount, desktopBtn, zoomOutBtn, zoomInBtn}) {
            v.setVisibility(on ? View.GONE : View.VISIBLE);
        }
        ((GradientDrawable) bar.getBackground()).setColor(on ? 0x8C101014 : GLASS);
    }

    private void voice() {
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

    // ---------------------------------------------------------------- settings

    private View buildPanel() {
        panelScroll = new ScrollView(this);
        panelScroll.setVerticalScrollBarEnabled(false);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(24));
        bg.setColor(GLASS);
        bg.setStroke(Math.max(1, dp(0.75f)), 0x26FFFFFF);
        panelScroll.setBackground(bg);
        panelScroll.setElevation(dp(8));
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(10), dp(14), dp(10), dp(14));
        panelScroll.addView(panel, new FrameLayout.LayoutParams(-1, -2));
        panel.setVisibility(View.GONE);
        panelScroll.setVisibility(View.GONE);
        return panelScroll;
    }

    private void showPanel() {
        fillPanel();
        panel.setVisibility(View.VISIBLE);
        panelScroll.setVisibility(View.VISIBLE);
        panelScroll.setAlpha(0f);
        panelScroll.animate().alpha(1f).setDuration(180).start();
        if (panel.getChildCount() > 1) panel.getChildAt(1).requestFocus();
    }

    private void hidePanel() {
        panel.setVisibility(View.GONE);
        panelScroll.setVisibility(View.GONE);
        if (inBar) settingsBtn.requestFocus(); else web.requestFocus();
    }

    /** Rebuilds the rows in place, keeping focus on the same row. */
    private void refillPanel() {
        View f = getCurrentFocus();
        int at = f == null ? -1 : panel.indexOfChild(f);
        fillPanel();
        if (at >= 0 && at < panel.getChildCount()) panel.getChildAt(at).requestFocus();
    }

    private void fillPanel() {
        panel.removeAllViews();
        TextView title = text(19, 650, TEXT);
        title.setText("Settings");
        title.setPadding(dp(12), 0, dp(12), dp(10));
        panel.addView(title);

        row("Search engine", engine()[0], () -> {
            sp.edit().putInt("engine", Math.floorMod(sp.getInt("engine", 0) + 1, ENGINES.length)).apply();
            address.setHint("Search " + engine()[0] + " or type a web address");
            refillPanel();
        });
        row("Home page background", Home.BACKGROUNDS[home.background()], () -> {
            home.setBackground(home.background() + 1);
            refillPanel();
        });
        row("DNS protection", dns.name(), () -> {
            dns.setService(dns.service() + 1);
            refillPanel();
            if (!onHome) web.reload();
        });
        row("Block pop-ups", popupsBlocked() ? "On" : "Off", () -> {
            sp.edit().putBoolean("block_popups", !popupsBlocked()).apply();
            installFrameFix(web);
            refillPanel();
            if (!onHome) web.reload();
        });
        row("Block ads and trackers", adblock.enabled() ? "On" : "Off", () -> {
            adblock.setEnabled(!adblock.enabled());
            paintShield();
            refillPanel();
        });
        long at = adblock.updatedAt();
        row("Block list", String.format(Locale.getDefault(), "%,d sites", adblock.size()) + " · "
                + (at == 0 ? "starter list" : "updated " + DateUtils.getRelativeTimeSpanString(at)), () -> {
            toast("Updating the block list…");
            adblock.refreshIfDue(true, () -> { toast("Block list: " + String.format(Locale.getDefault(), "%,d sites", adblock.size())); refillPanel(); });
        });
        row("Desktop sites by default", sp.getBoolean("desktop_default", false) ? "On" : "Off", () -> {
            sp.edit().putBoolean("desktop_default", !sp.getBoolean("desktop_default", false)).apply();
            refillPanel();
            if (applyAgentFor(web.getUrl())) web.reload();
            paintDesktop();
        });
        row("Clear cookies and history", null, () -> {
            CookieManager.getInstance().removeAllCookies(null);
            WebStorage.getInstance().deleteAllData();
            web.clearCache(true);
            web.clearHistory();
            toast("Cookies, site data and history cleared");
        });
        row("Open links from other apps", "How", () -> toast(
                "When an app opens a web page, choose BeeshTV Browser and Always"));
        row("Updates", updateSummary(), () -> {
            if (updater.state() == Updater.State.READY) installUpdate(); else updater.check(true);
            refillPanel();
        });
        if (!updater.canInstall()) row("Allow installing updates", "Allow", () -> startActivity(updater.installPermissionScreen()));
        row("About", "Version " + updater.installed() + " · Bishwo Bhusal", null);
    }

    private void row(String label, String value, Runnable click) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(14), 0, dp(14), 0);
        r.setFocusable(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        bg.setColor(Color.TRANSPARENT);
        r.setBackground(bg);
        r.setOnFocusChangeListener((v, has) -> bg.setColor(has ? FOCUS : Color.TRANSPARENT));
        if (click != null) r.setOnClickListener(v -> click.run());
        TextView l = text(15, 500, TEXT);
        l.setText(label);
        l.setEllipsize(TextUtils.TruncateAt.END);
        r.addView(l, new LinearLayout.LayoutParams(0, -2, 1f));
        if (value != null) {
            TextView v = text(13, 500, TEXT_2);
            v.setText(value);
            v.setEllipsize(TextUtils.TruncateAt.END);
            r.addView(v, llp(-2, -2, dp(12), 0));
        }
        panel.addView(r, new LinearLayout.LayoutParams(-1, dp(46)));
    }

    // ---------------------------------------------------------------- updates

    private String updateSummary() {
        switch (updater.state()) {
            case CHECKING: return "Checking…";
            case DOWNLOADING: return "Downloading " + updater.progress() + "%";
            case READY: return "Install " + updater.latest();
            case UP_TO_DATE: return "Up to date";
            case FAILED: return updater.error();
            default: return "Check now";
        }
    }

    private void onUpdateChanged() {
        if (updateChip == null) return;
        boolean ready = updater.state() == Updater.State.READY;
        updateChip.setText("Update " + updater.latest());
        updateChip.setVisibility(ready ? View.VISIBLE : View.GONE);
        if (panel != null && panel.getVisibility() == View.VISIBLE) refillPanel();
    }

    private void installUpdate() {
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
        b.setImageTintList(ColorStateList.valueOf(TEXT));
        b.setPadding(dp(10), dp(10), dp(10), dp(10));
        b.setFocusable(true);
        b.setContentDescription(label);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(Color.TRANSPARENT);
        b.setBackground(bg);
        b.setOnFocusChangeListener((v, has) -> bg.setColor(has ? FOCUS : Color.TRANSPARENT));
        b.setOnClickListener(click);
        b.setLayoutParams(llp(dp(44), dp(44), dp(2), dp(2)));
        return b;
    }

    private TextView text(float sp, int weight, int color) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(face(weight));
        t.setTextColor(color);
        t.setSingleLine(true);
        t.setIncludeFontPadding(false);
        return t;
    }

    private final java.util.Map<Integer, Typeface> faces = new java.util.HashMap<>();

    /** Schibsted Grotesk at an exact weight, as on BeeshTV's Home. */
    private Typeface face(int weight) {
        Typeface t = faces.get(weight);
        if (t != null) return t;
        try {
            t = new Typeface.Builder(getAssets(), "fonts/SchibstedGrotesk.ttf")
                    .setFontVariationSettings("'wght' " + weight).build();
        } catch (RuntimeException e) {
            t = null;
        }
        if (t == null) t = Typeface.DEFAULT;
        faces.put(weight, t);
        return t;
    }

    private android.graphics.drawable.Drawable icon(int res, int color, int sizeDp) {
        android.graphics.drawable.Drawable d = getDrawable(res).mutate();
        d.setTint(color);
        d.setBounds(0, 0, dp(sizeDp), dp(sizeDp));
        return d;
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

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

    private static LinearLayout.LayoutParams llp(int w, int h, int l, int r) {
        return llp(w, h, 0f, l, r);
    }
}
