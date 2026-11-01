package com.beeshtv.browser;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Blocks requests to known ad and tracker hosts. It starts from a short list bundled with the app and,
 * once a week, swaps in Steven Black's unified hosts list (ads and malware, about 80,000 hosts). A
 * host is blocked when it or any parent domain is listed, so "ads.example.com" is caught by
 * "example.com" only if that is listed itself.
 */
final class AdBlock {
    static final String LIST_URL = "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts";
    static final long REFRESH_MS = 7L * 24 * 60 * 60 * 1000;

    private final Context app;
    private final SharedPreferences sp;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private volatile Set<String> hosts = new HashSet<>();
    private volatile boolean fetching;

    AdBlock(Context c, SharedPreferences sp) {
        app = c.getApplicationContext();
        this.sp = sp;
        io.execute(this::loadCached);
    }

    boolean enabled() { return sp.getBoolean("adblock", true); }
    void setEnabled(boolean v) { sp.edit().putBoolean("adblock", v).apply(); }

    int size() { return hosts.size(); }
    long updatedAt() { return sp.getLong("adblock_at", 0); }

    /** Whether a request to this host should be dropped. */
    boolean blocks(String host) {
        if (host == null || !enabled()) return false;
        Set<String> h = hosts;
        String name = host.toLowerCase(java.util.Locale.ROOT);
        while (true) {
            if (h.contains(name)) return true;
            int dot = name.indexOf('.');
            // Stop before the bare top-level domain ("com").
            if (dot < 0 || name.indexOf('.', dot + 1) < 0) return false;
            name = name.substring(dot + 1);
        }
    }

    /** Fetches a fresh list once a week (or now, when forced), then switches to it. */
    void refreshIfDue(boolean force, Runnable done) {
        if (fetching || !force && System.currentTimeMillis() - updatedAt() < REFRESH_MS) return;
        fetching = true;
        io.execute(() -> {
            File part = new File(app.getFilesDir(), "hosts.part");
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(LIST_URL).openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(30000);
                c.setRequestProperty("User-Agent", "BeeshTV-Browser");
                try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(part)) {
                    byte[] buf = new byte[64 * 1024];
                    for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                } finally {
                    c.disconnect();
                }
                Set<String> fresh = parse(new java.io.FileInputStream(part));
                // A list this short is an error page, not a hosts file.
                if (fresh.size() < 1000) throw new IllegalStateException("short list");
                if (!part.renameTo(cache())) throw new IllegalStateException("rename");
                hosts = fresh;
                sp.edit().putLong("adblock_at", System.currentTimeMillis()).apply();
            } catch (Exception e) {
                part.delete();
            } finally {
                fetching = false;
                if (done != null) new android.os.Handler(android.os.Looper.getMainLooper()).post(done);
            }
        });
    }

    private File cache() { return new File(app.getFilesDir(), "hosts.txt"); }

    private void loadCached() {
        try {
            File f = cache();
            hosts = parse(f.exists() ? new java.io.FileInputStream(f) : app.getAssets().open("blocklist.txt"));
        } catch (Exception ignored) { }
    }

    /** Hosts-file lines ("0.0.0.0 ads.example.com") or plain domains; # starts a comment. */
    private static Set<String> parse(InputStream in) throws java.io.IOException {
        Set<String> out = new HashSet<>(100_000);
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            for (String line; (line = r.readLine()) != null; ) {
                int hash = line.indexOf('#');
                if (hash >= 0) line = line.substring(0, hash);
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] parts = line.split("\\s+");
                String host = parts.length > 1 ? parts[1] : parts[0];
                if (host.equals("localhost") || host.endsWith(".localdomain") || host.equals("0.0.0.0")
                        || host.indexOf('.') < 0) continue;
                out.add(host.toLowerCase(java.util.Locale.ROOT));
            }
        }
        return out;
    }
}
