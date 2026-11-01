package com.beeshtv.browser;

import android.app.DownloadManager;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.webkit.CookieManager;
import android.webkit.URLUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * Files saved from websites, through the TV's own download service: it carries on when the browser is
 * closed, and the files land in the TV's Downloads folder. The list is the service's, so nothing is
 * stored here; it only ever shows this browser's downloads.
 */
final class Downloads {
    static final class Item {
        long id;
        String name, url, mime;
        int status;
        long done, total, at;

        boolean running() {
            return status == DownloadManager.STATUS_RUNNING || status == DownloadManager.STATUS_PENDING
                    || status == DownloadManager.STATUS_PAUSED;
        }
        boolean finished() { return status == DownloadManager.STATUS_SUCCESSFUL; }
        boolean failed() { return status == DownloadManager.STATUS_FAILED; }
    }

    private final Context c;
    private final DownloadManager dm;

    Downloads(Context c) {
        this.c = c;
        dm = c.getSystemService(DownloadManager.class);
    }

    /** Starts saving a file, with the site's cookies so signed-in downloads work. Its name, or null if it can't be. */
    String start(String url, String userAgent, String disposition, String mime) {
        if (dm == null || url == null || !(url.startsWith("http://") || url.startsWith("https://"))) return null;
        String name = URLUtil.guessFileName(url, disposition, mime);
        DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
        String cookies = CookieManager.getInstance().getCookie(url);
        if (cookies != null) r.addRequestHeader("Cookie", cookies);
        if (userAgent != null) r.addRequestHeader("User-Agent", userAgent);
        if (mime != null && !mime.isEmpty()) r.setMimeType(mime);
        r.setTitle(name);
        r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        try {
            r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
        } catch (IllegalStateException noFolder) {
            // A TV without a shared Downloads folder: the browser's own, removed with it.
            r.setDestinationInExternalFilesDir(c, Environment.DIRECTORY_DOWNLOADS, name);
        }
        try {
            dm.enqueue(r);
            return name;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Every download, newest first. */
    List<Item> all() {
        List<Item> out = new ArrayList<>();
        if (dm == null) return out;
        try (Cursor q = dm.query(new DownloadManager.Query())) {
            if (q == null) return out;
            while (q.moveToNext()) {
                Item i = new Item();
                i.id = q.getLong(q.getColumnIndexOrThrow(DownloadManager.COLUMN_ID));
                i.name = q.getString(q.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE));
                i.url = q.getString(q.getColumnIndexOrThrow(DownloadManager.COLUMN_URI));
                i.mime = q.getString(q.getColumnIndexOrThrow(DownloadManager.COLUMN_MEDIA_TYPE));
                i.status = q.getInt(q.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                i.done = q.getLong(q.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                i.total = q.getLong(q.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                i.at = q.getLong(q.getColumnIndexOrThrow(DownloadManager.COLUMN_LAST_MODIFIED_TIMESTAMP));
                out.add(i);
            }
        } catch (RuntimeException ignored) { }
        out.sort((a, b) -> Long.compare(b.id, a.id));
        return out;
    }

    Item get(long id) {
        for (Item i : all()) if (i.id == id) return i;
        return null;
    }

    int running() {
        int n = 0;
        for (Item i : all()) if (i.running()) n++;
        return n;
    }

    /** Stops it if it's still going, and deletes the file. */
    void remove(long id) { if (dm != null) dm.remove(id); }

    /** The finished file, for another app (or a tab) to open; null if it's gone. */
    Uri file(long id) { return dm == null ? null : dm.getUriForDownloadedFile(id); }

    String mime(long id) { return dm == null ? null : dm.getMimeTypeForDownloadedFile(id); }
}
