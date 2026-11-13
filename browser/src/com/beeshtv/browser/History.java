package com.beeshtv.browser;

import android.content.Context;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Pages you've visited, newest first, kept on the TV (the last 500). Used on the main thread. */
final class History {
    static final int KEEP = 500;

    static final class Entry {
        final String url, title;
        final long at;
        Entry(String url, String title, long at) { this.url = url; this.title = title; this.at = at; }
    }

    private final File file;
    private final List<Entry> items = new ArrayList<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    History(Context c) {
        file = new File(c.getFilesDir(), "history.json");
        try {
            JSONArray a = new JSONArray(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                items.add(new Entry(o.getString("u"), o.optString("t"), o.getLong("a")));
            }
        } catch (Exception ignored) { }
    }

    List<Entry> all() { return items; }

    /** A page finished loading. Reloading the same page only refreshes its entry. */
    void add(String url, String title) {
        if (url == null || !url.startsWith("http")) return;
        String t = title == null || title.isEmpty() || title.startsWith("http") ? hostOf(url) : title;
        if (!items.isEmpty() && items.get(0).url.equals(url)) items.remove(0);
        items.add(0, new Entry(url, t, System.currentTimeMillis()));
        while (items.size() > KEEP) items.remove(items.size() - 1);
        save();
    }

    void clear() {
        items.clear();
        save();
    }

    /** Sites, newest first, one each (en.wikipedia.org and wikipedia.org are one site). */
    List<String[]> recentSites(int n) {
        Set<String> seen = new LinkedHashSet<>();
        List<String[]> out = new ArrayList<>();
        for (Entry e : items) {
            String h = hostOf(e.url);
            if (h == null || !seen.add(domainOf(h))) continue;
            out.add(new String[]{h, "https://" + h});
            if (out.size() == n) break;
        }
        return out;
    }

    /**
     * Pages for what's being typed, as {label, address}: sites whose name starts with it first, then
     * addresses and titles that contain it, newest first, one per page.
     */
    List<String[]> match(String typed, int n) {
        List<String[]> out = new ArrayList<>();
        String q = typed == null ? "" : typed.trim().toLowerCase(java.util.Locale.ROOT);
        if (q.isEmpty() || q.startsWith("http") && q.length() < 9) return out;
        q = q.replaceFirst("^https?://", "").replaceFirst("^www\\.", "");
        Set<String> seen = new LinkedHashSet<>();
        for (int pass = 0; pass < 3 && out.size() < n; pass++) {
            for (Entry e : items) {
                String h = hostOf(e.url), u = e.url.toLowerCase(java.util.Locale.ROOT);
                if (h == null) continue;
                boolean hit = pass == 0 ? h.startsWith(q)
                        : pass == 1 ? u.replaceFirst("^https?://(www\\.)?", "").contains(q)
                        : e.title != null && e.title.toLowerCase(java.util.Locale.ROOT).contains(q);
                String key = u.replaceFirst("/$", "");
                if (!hit || !seen.add(key)) continue;
                String title = e.title == null || e.title.isEmpty() ? h : e.title;
                out.add(new String[]{title.equals(h) ? h : title + "  ·  " + h, e.url});
                if (out.size() == n) break;
            }
        }
        return out;
    }

    /** Second-level labels under which country domains put their sites (bbc.co.uk, abc.net.au). */
    private static final java.util.Set<String> SECOND = new java.util.HashSet<>(java.util.Arrays.asList(
            "co", "com", "net", "org", "gov", "edu", "ac", "or", "ne", "go"));

    /** The domain a site is registered under: en.wikipedia.org gives wikipedia.org, bbc.co.uk stays. */
    static String domainOf(String host) {
        String[] p = host.split("\\.");
        if (p.length <= 2) return host;
        int keep = p.length >= 3 && SECOND.contains(p[p.length - 2]) && p[p.length - 1].length() == 2 ? 3 : 2;
        StringBuilder d = new StringBuilder();
        for (int i = p.length - keep; i < p.length; i++) d.append(i == p.length - keep ? "" : ".").append(p[i]);
        return d.toString();
    }

    /** A short name for a site: Wikipedia for en.wikipedia.org, BBC-style names stay as written. */
    static String nameOf(String host) {
        String d = domainOf(host);
        String n = d.contains(".") ? d.substring(0, d.indexOf('.')) : d;
        return n.isEmpty() ? host : Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }

    /** The site's name without "www." or the "m." of its phone version, so both count as one site. */
    static String hostOf(String url) {
        String h = url == null ? null : Uri.parse(url).getHost();
        if (h == null) return null;
        if (h.startsWith("www.")) return h.substring(4);
        if (h.startsWith("m.") && h.indexOf('.', 2) > 0) return h.substring(2);
        return h;
    }

    private void save() {
        JSONArray a = new JSONArray();
        try {
            for (Entry e : items) a.put(new JSONObject().put("u", e.url).put("t", e.title).put("a", e.at));
        } catch (Exception ignored) { }
        byte[] data = a.toString().getBytes(StandardCharsets.UTF_8);
        io.execute(() -> {
            File tmp = new File(file.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(data);
            } catch (Exception e) {
                return;
            }
            tmp.renameTo(file);
        });
    }
}
