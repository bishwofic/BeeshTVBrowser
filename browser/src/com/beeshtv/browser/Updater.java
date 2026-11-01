package com.beeshtv.browser;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Updates from the GitHub releases of the repo: the newest release's APK is downloaded in the
 * background, then the top bar offers to install it. Installing goes through the system installer,
 * which asks once to confirm; updates have to be signed with the same key as the installed app.
 */
final class Updater {
    static final String REPO = "bishwofic/BeeshTVBrowser";
    static final long CHECK_EVERY_MS = 6L * 60 * 60 * 1000;
    static final String ACTION_STATUS = "com.beeshtv.browser.INSTALL_STATUS";

    enum State { IDLE, CHECKING, UP_TO_DATE, DOWNLOADING, READY, FAILED }

    private final Context app;
    private final SharedPreferences sp;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile State state = State.IDLE;
    private volatile String latest, apkUrl, error;
    private volatile long apkSize;
    private volatile int progress;
    /** Told about every change of state, on the main thread. */
    Runnable onChange;

    Updater(Context c, SharedPreferences sp) {
        app = c.getApplicationContext();
        this.sp = sp;
        latest = sp.getString("update_latest", null);
        // A download that finished before the browser last stopped is still good to install.
        if (latest != null && isNewer(latest, installed()) && apk(latest).exists()) state = State.READY;
    }

    State state() { return state; }
    String latest() { return latest; }
    String error() { return error; }
    int progress() { return progress; }

    boolean autoDownload() { return sp.getBoolean("update_auto", true); }
    void setAutoDownload(boolean v) { sp.edit().putBoolean("update_auto", v).apply(); }

    String installed() {
        try { return app.getPackageManager().getPackageInfo(app.getPackageName(), 0).versionName; }
        catch (PackageManager.NameNotFoundException e) { return "0"; }
    }

    /** Looks for a new release at most every few hours; force checks now. Downloads it if allowed. */
    void check(boolean force) {
        if (state == State.CHECKING || state == State.DOWNLOADING) return;
        if (!force && System.currentTimeMillis() - sp.getLong("update_checked_at", 0) < CHECK_EVERY_MS) return;
        set(State.CHECKING, null);
        io.execute(() -> {
            try {
                JSONObject rel = new JSONObject(get("https://api.github.com/repos/" + REPO + "/releases/latest"));
                String tag = rel.getString("tag_name").replaceFirst("^[vV]", "");
                JSONArray assets = rel.getJSONArray("assets");
                String url = null;
                long size = 0;
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.getJSONObject(i);
                    if (a.getString("name").endsWith(".apk")) {
                        url = a.getString("browser_download_url");
                        size = a.optLong("size");
                        break;
                    }
                }
                sp.edit().putLong("update_checked_at", System.currentTimeMillis()).putString("update_latest", tag).apply();
                latest = tag;
                if (url == null || !isNewer(tag, installed())) { set(State.UP_TO_DATE, null); return; }
                apkUrl = url;
                apkSize = size;
                File f = apk(tag);
                if (f.exists() && (size == 0 || f.length() == size)) { set(State.READY, null); return; }
                if (force || autoDownload()) downloadNow(); else set(State.IDLE, null);
            } catch (Exception e) {
                set(State.FAILED, "Couldn't reach GitHub");
            }
        });
    }

    /** Downloads the release found by the last check. */
    void download() {
        if (apkUrl == null) { check(true); return; }
        if (state == State.DOWNLOADING) return;
        io.execute(this::downloadNow);
    }

    private void downloadNow() {
        File f = apk(latest), part = new File(f.getPath() + ".part");
        set(State.DOWNLOADING, null);
        try {
            File dir = f.getParentFile();
            if (dir != null && !dir.exists()) dir.mkdirs();
            // Only the newest download is kept.
            File[] old = dir == null ? null : dir.listFiles();
            if (old != null) for (File o : old) if (!o.equals(f)) o.delete();
            HttpURLConnection c = open(apkUrl);
            long total = c.getContentLengthLong() > 0 ? c.getContentLengthLong() : apkSize, done = 0;
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(part)) {
                byte[] buf = new byte[64 * 1024];
                for (int n; (n = in.read(buf)) > 0; ) {
                    out.write(buf, 0, n);
                    done += n;
                    int pct = total > 0 ? (int) (done * 100 / total) : 0;
                    if (pct != progress) { progress = pct; main.post(this::changed); }
                }
            } finally {
                c.disconnect();
            }
            if (!part.renameTo(f)) throw new IllegalStateException("rename");
            set(State.READY, null);
        } catch (Exception e) {
            part.delete();
            set(State.FAILED, "Download didn't finish");
        }
    }

    /** Whether Android lets this app install updates; if not, Settings has a switch for it. */
    boolean canInstall() { return app.getPackageManager().canRequestPackageInstalls(); }

    Intent installPermissionScreen() {
        return new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + app.getPackageName()))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    /** Hands the downloaded APK to the system installer, which asks the viewer to confirm. */
    void install() {
        if (state != State.READY) return;
        File f = apk(latest);
        io.execute(() -> {
            try {
                PackageInstaller pi = app.getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                        PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                params.setAppPackageName(app.getPackageName());
                params.setSize(f.length());
                int id = pi.createSession(params);
                try (PackageInstaller.Session s = pi.openSession(id)) {
                    try (InputStream in = new FileInputStream(f); OutputStream out = s.openWrite("base.apk", 0, f.length())) {
                        byte[] buf = new byte[64 * 1024];
                        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                        s.fsync(out);
                    }
                    Intent status = new Intent(ACTION_STATUS).setClass(app, InstallStatus.class);
                    PendingIntent cb = PendingIntent.getBroadcast(app, id, status,
                            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
                    s.commit(cb.getIntentSender());
                }
            } catch (Exception e) {
                set(State.FAILED, "Couldn't start the install");
            }
        });
    }

    /** "1.10" is newer than "1.9"; missing parts count as 0. */
    static boolean isNewer(String a, String b) {
        String[] x = a.split("\\D+"), y = b.split("\\D+");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length && !x[i].isEmpty() ? Integer.parseInt(x[i]) : 0;
            int q = i < y.length && !y[i].isEmpty() ? Integer.parseInt(y[i]) : 0;
            if (p != q) return p > q;
        }
        return false;
    }

    private File apk(String version) {
        return new File(new File(app.getCacheDir(), "updates"), "BeeshTV-Browser-" + version + ".apk");
    }

    private void set(State s, String err) {
        state = s;
        error = err;
        if (s != State.DOWNLOADING) progress = 0;
        main.post(this::changed);
    }

    private void changed() {
        Runnable r = onChange;
        if (r != null) r.run();
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "BeeshTV-Browser");
        c.setRequestProperty("Accept", "application/vnd.github+json");
        if (c.getResponseCode() / 100 != 2) throw new IllegalStateException("HTTP " + c.getResponseCode());
        return c;
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = open(url);
        try (InputStream in = c.getInputStream()) {
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
            return new String(b.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            c.disconnect();
        }
    }

    /** The installer's answer: show its confirmation screen when it asks for one. */
    public static final class InstallStatus extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent i) {
            int status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (status != PackageInstaller.STATUS_PENDING_USER_ACTION) {
                // Success restarts the browser as the new version, so only failures need saying.
                if (status != PackageInstaller.STATUS_SUCCESS && status != PackageInstaller.STATUS_FAILURE_ABORTED) {
                    String why = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                    android.widget.Toast.makeText(c, "Update didn't install" + (why == null ? "" : ": " + why),
                            android.widget.Toast.LENGTH_LONG).show();
                }
                return;
            }
            Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm == null) return;
            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { c.startActivity(confirm); } catch (RuntimeException ignored) { }
        }
    }
}
