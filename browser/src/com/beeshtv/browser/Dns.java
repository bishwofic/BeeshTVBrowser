package com.beeshtv.browser;

import android.content.SharedPreferences;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DNS protection: the chosen service (AdGuard by default) is asked about each host over encrypted
 * DNS (DNS-over-HTTPS), and a host it blocks isn't loaded. Android has no way to point the WebView's
 * own lookups at another DNS server, so this applies the service's blocking to the browser while the
 * TV's DNS still does the actual lookups.
 *
 * Nothing on a page waits for it: a site you open is checked for at most a moment, and the pieces of
 * a page (images, scripts, ads) are checked in the background, so a blocked host is caught from its
 * next request on. Answers are remembered for their TTL.
 */
final class Dns {
    /** Name, DNS-over-HTTPS endpoint, and whether NXDOMAIN means "blocked" (else only sinkhole addresses). */
    static final String[][] SERVICES = {
            {"Off", "", ""},
            {"AdGuard", "https://dns.adguard-dns.com/dns-query", ""},
            {"AdGuard Family", "https://family.adguard-dns.com/dns-query", ""},
            {"Cloudflare (malware)", "https://security.cloudflare-dns.com/dns-query", ""},
            {"Cloudflare Family", "https://family.cloudflare-dns.com/dns-query", ""},
            {"OpenDNS", "https://doh.opendns.com/dns-query", ""},
            {"OpenDNS FamilyShield", "https://doh.familyshield.opendns.com/dns-query", ""},
            {"Quad9", "https://dns.quad9.net/dns-query", "nx"},
    };
    static final int DEFAULT = 1;
    private static final int TIMEOUT_MS = 1500, PAGE_WAIT_MS = 800;

    private final SharedPreferences sp;
    private final ConcurrentHashMap<String, long[]> cache = new ConcurrentHashMap<>();  // host → {blocked, expiresAt}
    private final ConcurrentHashMap<String, java.util.concurrent.Future<Boolean>> asking = new ConcurrentHashMap<>();
    private final java.util.concurrent.ExecutorService io = java.util.concurrent.Executors.newFixedThreadPool(4);

    Dns(SharedPreferences sp) { this.sp = sp; }

    int service() { return Math.floorMod(sp.getInt("dns", DEFAULT), SERVICES.length); }
    String name() { return SERVICES[service()][0]; }

    void setService(int i) {
        sp.edit().putInt("dns", Math.floorMod(i, SERVICES.length)).apply();
        cache.clear();
    }

    /** Opens the connection to the service early, so the first real check is quick. */
    void warmUp() {
        if (service() != 0) io.execute(() -> blocks("example.com", true));
    }

    /**
     * Whether the chosen service blocks this host. With wait, asks now (for at most a moment); without,
     * answers from what's known and asks in the background. Called on the WebView's network threads.
     */
    boolean blocks(String host, boolean wait) {
        int s = service();
        if (s == 0 || host == null || host.isEmpty() || host.equals("localhost")) return false;
        String h = host.toLowerCase(Locale.ROOT);
        String key = s + ":" + h;
        long[] hit = cache.get(key);
        if (hit != null && hit[1] > System.currentTimeMillis()) return hit[0] == 1;
        java.util.concurrent.Future<Boolean> f = asking.computeIfAbsent(key, k -> io.submit(() -> {
            try { return lookUp(s, h, key); } finally { asking.remove(key); }
        }));
        if (!wait) return false;
        try {
            return f.get(PAGE_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean lookUp(int s, String host, String key) {
        long now = System.currentTimeMillis();
        boolean blocked;
        long ttl;
        try {
            int[] answer = ask(SERVICES[s][1], host);    // {blocked, ttl seconds}
            blocked = answer[0] == 1 || answer[0] == 2 && "nx".equals(SERVICES[s][2]);
            ttl = Math.max(60, Math.min(3600, answer[1])) * 1000L;
        } catch (Exception e) {
            // Unreachable or slow: let the site load, and ask again in a minute.
            blocked = false;
            ttl = 60_000L;
        }
        cache.put(key, new long[]{blocked ? 1 : 0, now + ttl});
        return blocked;
    }

    /** One A query in DNS wire format (RFC 8484 GET). Returns {1 sinkholed / 2 NXDOMAIN / 0 fine, ttl}. */
    private static int[] ask(String endpoint, String host) throws Exception {
        ByteArrayOutputStream q = new ByteArrayOutputStream();
        q.write(new byte[]{0, 0, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0});    // id 0, recursion desired, 1 question
        for (String label : host.split("\\.")) {
            byte[] b = label.getBytes("US-ASCII");
            if (b.length == 0 || b.length > 63) continue;
            q.write(b.length);
            q.write(b);
        }
        q.write(new byte[]{0, 0, 1, 0, 1});                          // end of name, type A, class IN
        String dns = Base64.encodeToString(q.toByteArray(), Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);

        HttpURLConnection c = (HttpURLConnection) new URL(endpoint + "?dns=" + dns).openConnection();
        c.setConnectTimeout(TIMEOUT_MS);
        c.setReadTimeout(TIMEOUT_MS);
        c.setRequestProperty("Accept", "application/dns-message");
        byte[] r;
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[2048];
            for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
            r = b.toByteArray();
        } finally {
            c.disconnect();
        }
        int rcode = r[3] & 0x0F;
        int qd = u16(r, 4), an = u16(r, 6);
        if (rcode == 3) return new int[]{2, 300};
        int p = 12;
        for (int i = 0; i < qd; i++) p = skipName(r, p) + 4;
        int ttl = 300;
        for (int i = 0; i < an && p < r.length; i++) {
            p = skipName(r, p);
            int type = u16(r, p), len = u16(r, p + 8);
            ttl = (int) Math.min(Integer.MAX_VALUE, ((long) u16(r, p + 4) << 16) | u16(r, p + 6));
            int data = p + 10;
            if (type == 1 && len == 4 && sinkhole(r[data] & 255, r[data + 1] & 255, r[data + 2] & 255, r[data + 3] & 255)) {
                return new int[]{1, ttl};
            }
            p = data + len;
        }
        return new int[]{0, ttl};
    }

    /** The addresses these services answer with for a blocked host. */
    private static boolean sinkhole(int a, int b, int c, int d) {
        if (a == 0 && b == 0 && c == 0 && d == 0) return true;                              // AdGuard, Cloudflare
        if (a == 146 && b == 112 && c == 61 && d >= 104 && d <= 110) return true;          // OpenDNS block pages
        return a == 94 && b == 140 && c == 14 && d == 35;                                  // AdGuard block page
    }

    private static int u16(byte[] r, int i) { return ((r[i] & 255) << 8) | (r[i + 1] & 255); }

    private static int skipName(byte[] r, int p) {
        while (p < r.length) {
            int len = r[p] & 255;
            if (len == 0) return p + 1;
            if ((len & 0xC0) == 0xC0) return p + 2;   // a pointer ends the name
            p += len + 1;
        }
        return p;
    }
}
