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
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Blocks requests to known ad and tracker hosts. It starts from a short list bundled with the app and,
 * once a week, swaps in two public lists merged: Steven Black's unified hosts (ads and malware) and
 * AdGuard's DNS filter (ads, trackers, analytics), together well over 100,000 hosts. A host is
 * blocked when it or any parent domain is listed.
 *
 * The hosts are kept as a sorted array of 64-bit hashes, not as strings: 260,000 hosts take 2 MB this
 * way instead of about 30 MB of strings in a set, on a TV with under 2 GB for everything.
 */
final class AdBlock {
    static final String[] LISTS = {
            "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts",
            "https://adguardteam.github.io/AdGuardSDNSFilter/Filters/filter.txt",
    };
    /** Raised when the lists change, so TVs fetch the new set at once instead of within a week. */
    static final int LISTS_VERSION = 2;
    static final long REFRESH_MS = 7L * 24 * 60 * 60 * 1000;

    private final Context app;
    private final SharedPreferences sp;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    /** Sorted, no repeats. */
    private volatile long[] hosts = new long[0];
    private volatile boolean fetching;

    AdBlock(Context c, SharedPreferences sp) {
        app = c.getApplicationContext();
        this.sp = sp;
        io.execute(this::loadCached);
    }

    boolean enabled() { return sp.getBoolean("adblock", true); }
    void setEnabled(boolean v) { sp.edit().putBoolean("adblock", v).apply(); }

    int size() { return hosts.length; }
    long updatedAt() { return sp.getLong("adblock_at", 0); }

    /** Whether a request to this host should be dropped. */
    boolean blocks(String host) {
        if (host == null || !enabled()) return false;
        long[] h = hosts;
        if (h.length == 0) return false;
        String name = host.toLowerCase(java.util.Locale.ROOT);
        int from = 0;
        while (true) {
            if (Arrays.binarySearch(h, hash(name, from)) >= 0) return true;
            int dot = name.indexOf('.', from);
            // Stop before the bare top-level domain ("com").
            if (dot < 0 || name.indexOf('.', dot + 1) < 0) return false;
            from = dot + 1;
        }
    }

    /** 64-bit FNV-1a of name from index from on: one in billions of hosts could share another's. */
    private static long hash(String name, int from) {
        long x = 0xcbf29ce484222325L;
        for (int i = from; i < name.length(); i++) {
            x ^= name.charAt(i);
            x *= 0x100000001b3L;
        }
        return x;
    }

    /** Fetches a fresh list once a week (or now, when forced), then switches to it. */
    void refreshIfDue(boolean force, Runnable done) {
        boolean stale = sp.getInt("adblock_lists", 1) != LISTS_VERSION;
        if (fetching || !force && !stale && System.currentTimeMillis() - updatedAt() < REFRESH_MS) return;
        fetching = true;
        io.execute(() -> {
            File part = new File(app.getFilesDir(), "hosts.part");
            try {
                try (OutputStream out = new FileOutputStream(part)) {
                    for (String list : LISTS) {
                        HttpURLConnection c = (HttpURLConnection) new URL(list).openConnection();
                        c.setConnectTimeout(15000);
                        c.setReadTimeout(30000);
                        c.setRequestProperty("User-Agent", "BeeshTV-Browser");
                        try (InputStream in = c.getInputStream()) {
                            byte[] buf = new byte[64 * 1024];
                            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                            out.write('\n');
                        } finally {
                            c.disconnect();
                        }
                    }
                }
                long[] fresh = parse(new java.io.FileInputStream(part));
                // A list this short is an error page, not a hosts file.
                if (fresh.length < 1000) throw new IllegalStateException("short list");
                if (!part.renameTo(cache())) throw new IllegalStateException("rename");
                hosts = fresh;
                writeBin(fresh);
                sp.edit().putLong("adblock_at", System.currentTimeMillis()).putInt("adblock_lists", LISTS_VERSION).apply();
            } catch (Exception e) {
                part.delete();
            } finally {
                fetching = false;
                if (done != null) new android.os.Handler(android.os.Looper.getMainLooper()).post(done);
            }
        });
    }

    private File cache() { return new File(app.getFilesDir(), "hosts.txt"); }

    /** The list as it's kept in memory, so it loads at once: reading the text takes seconds on a TV. */
    private File bin() { return new File(app.getFilesDir(), "hosts.bin"); }

    private void loadCached() {
        try {
            File f = cache(), b = bin();
            if (b.exists() && (!f.exists() || b.lastModified() >= f.lastModified())) {
                hosts = readBin(b);
                return;
            }
            long[] h = parse(f.exists() ? new java.io.FileInputStream(f) : app.getAssets().open("blocklist.txt"));
            hosts = h;
            if (f.exists()) writeBin(h);
        } catch (Exception ignored) { }
    }

    private static long[] readBin(File f) throws java.io.IOException {
        try (java.io.RandomAccessFile r = new java.io.RandomAccessFile(f, "r");
             java.nio.channels.FileChannel ch = r.getChannel()) {
            java.nio.LongBuffer lb = ch.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, ch.size()).asLongBuffer();
            long[] out = new long[lb.remaining()];
            lb.get(out);
            return out;
        }
    }

    private void writeBin(long[] h) {
        File tmp = new File(app.getFilesDir(), "hosts.bin.part");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(h.length * 8);
            b.asLongBuffer().put(h);
            out.write(b.array());
        } catch (java.io.IOException e) {
            tmp.delete();
            return;
        }
        if (!tmp.renameTo(bin())) tmp.delete();
    }

    /**
     * Hosts-file lines ("0.0.0.0 ads.example.com"), plain domains, and the domain rules of AdGuard's
     * filter ("||ads.example.com^"); # and ! start comments, exceptions (@@) and anything with
     * wildcards or options are skipped.
     */
    private static long[] parse(InputStream in) throws java.io.IOException {
        long[] out = new long[1 << 17];
        int n = 0;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            for (String line; (line = r.readLine()) != null; ) {
                int hash = line.indexOf('#');
                if (hash >= 0) line = line.substring(0, hash);
                line = line.trim();
                if (line.isEmpty() || line.startsWith("!") || line.startsWith("@@")) continue;
                if (line.startsWith("||")) {
                    if (!line.endsWith("^") || line.indexOf('*') >= 0 || line.indexOf('$') >= 0) continue;
                    line = line.substring(2, line.length() - 1);
                }
                String[] parts = line.split("\\s+");
                String host = parts.length > 1 ? parts[1] : parts[0];
                if (host.equals("localhost") || host.endsWith(".localdomain") || host.equals("0.0.0.0")
                        || host.indexOf('.') < 0) continue;
                if (n == out.length) out = Arrays.copyOf(out, n * 2);
                out[n++] = hash(host.toLowerCase(java.util.Locale.ROOT), 0);
            }
        }
        Arrays.sort(out, 0, n);
        int k = 0;
        for (int i = 0; i < n; i++) if (k == 0 || out[i] != out[k - 1]) out[k++] = out[i];
        return Arrays.copyOf(out, k);
    }
}
