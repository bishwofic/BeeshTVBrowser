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

    /** Sites, newest first, one each: the address of the last page you saw on it. */
    List<String[]> recentSites(int n) {
        Set<String> seen = new LinkedHashSet<>();
        List<String[]> out = new ArrayList<>();
        for (Entry e : items) {
            String h = hostOf(e.url);
            if (h == null || !seen.add(h)) continue;
            out.add(new String[]{h, "https://" + Uri.parse(e.url).getHost()});
            if (out.size() == n) break;
        }
        return out;
    }

    static String hostOf(String url) {
        String h = url == null ? null : Uri.parse(url).getHost();
        return h == null ? null : h.startsWith("www.") ? h.substring(4) : h;
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
