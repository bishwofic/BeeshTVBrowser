package com.beeshtv.browser;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import java.io.InputStream;

/**
 * The home page's photo and weather, borrowed from BEESH Launcher (its ShareProvider), so the
 * browser opens on the same picture as the TV's Home. The two blurs are the launcher's own: the light
 * one it lays over the photo on Home, and the heavy one behind its frosted panels.
 */
final class Backdrop {
    static final Uri WALLPAPER = Uri.parse("content://com.beeshtv.home.share/wallpaper");
    static final Uri WEATHER = Uri.parse("content://com.beeshtv.home.share/weather");
    static final Uri SCREEN = Uri.parse("content://com.beeshtv.home.share/screen");

    /**
     * The TV's Home screen as it last looked (apps, clock and photo), frosted: what you'd see through
     * the browser if it were glass. Falls back to Home's photo when the launcher hasn't saved one.
     */
    static Bitmap homeScreen(Context c) {
        try (InputStream in = c.getContentResolver().openInputStream(SCREEN)) {
            Bitmap small = in == null ? null : BitmapFactory.decodeStream(in);
            if (small != null) {
                Bitmap out = blur(small, 0.5f);
                small.recycle();
                return out;
            }
        } catch (Exception | OutOfMemoryError ignored) { }
        return wallpaper(c, true);
    }

    static final class Weather {
        int temp, code;
        boolean day;
        String label;
    }

    /** The photo on Home, light (as Home shows it) or heavy (frosted); null if the launcher isn't there. */
    static Bitmap wallpaper(Context c, boolean heavy) {
        try (InputStream in = c.getContentResolver().openInputStream(WALLPAPER)) {
            if (in == null) return null;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap full = BitmapFactory.decodeStream(in, null, o);
            if (full == null) return null;
            // Home decodes at screen size, so scale to it first for the same look.
            int sw = c.getResources().getDisplayMetrics().widthPixels, sh = c.getResources().getDisplayMetrics().heightPixels;
            float s = Math.max(sw / (float) full.getWidth(), sh / (float) full.getHeight());
            if (s < 1f) {
                Bitmap scaled = Bitmap.createScaledBitmap(full, Math.round(full.getWidth() * s), Math.round(full.getHeight() * s), true);
                full.recycle();
                full = scaled;
            }
            Bitmap soft = soften(full);
            full.recycle();
            if (!heavy) return soft;
            // Frosted deep: the photo reads as colour and light, not detail.
            Bitmap small = Bitmap.createScaledBitmap(soft, soft.getWidth() / 4, soft.getHeight() / 4, true);
            soft.recycle();
            Bitmap out = blur(small, 0.5f);
            small.recycle();
            return out;
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    /** Home's current weather; null if the launcher isn't there or hasn't fetched it yet. */
    static Weather weather(Context c) {
        try (Cursor cur = c.getContentResolver().query(WEATHER, null, null, null, null)) {
            if (cur == null || !cur.moveToFirst()) return null;
            Weather w = new Weather();
            w.temp = cur.getInt(0);
            w.label = cur.getString(1);
            w.code = cur.getInt(2);
            w.day = cur.getInt(3) == 1;
            return w;
        } catch (Exception e) {
            return null;
        }
    }

    /** Open-Meteo weather code to an icon, as on Home. */
    static int icon(int code, boolean day) {
        if (code == 0 || code == 1) return day ? R.drawable.ic_w_sun : R.drawable.ic_w_moon;
        if (code == 2) return day ? R.drawable.ic_w_cloud_sun : R.drawable.ic_w_cloud_moon;
        if (code == 3) return R.drawable.ic_w_cloud;
        if (code == 45 || code == 48) return R.drawable.ic_w_fog;
        if (code >= 71 && code <= 77 || code == 85 || code == 86) return R.drawable.ic_w_snow;
        if (code >= 95) return R.drawable.ic_w_storm;
        return R.drawable.ic_w_rain;
    }

    // ---- The launcher's blurs (BEESH Launcher, Ui.java), kept identical so both apps look the same.

    /** A light blur over the whole photo, at half size. */
    static Bitmap soften(Bitmap full) {
        int w = Math.max(1, full.getWidth() / 2), h = Math.max(1, full.getHeight() / 2);
        Bitmap small = Bitmap.createScaledBitmap(full, w, h, true);
        int[] px = new int[w * h];
        small.getPixels(px, 0, w, 0, 0, w, h);
        small.recycle();
        boxBlur(px, w, h, 1, true);
        boxBlur(px, w, h, 1, false);
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
    }

    /** The frosted-glass blur: three box passes at a sixteenth of the size, a little more colour. */
    static Bitmap blur(Bitmap small, float amount) {
        int w = small.getWidth(), h = small.getHeight();
        int sw = Math.max(1, w / 4), sh = Math.max(1, h / 4);
        Bitmap tiny = Bitmap.createScaledBitmap(small, sw, sh, true);
        int[] px = new int[sw * sh];
        tiny.getPixels(px, 0, sw, 0, 0, sw, sh);
        tiny.recycle();
        int r = 1 + Math.round(amount * 4);
        for (int pass = 0; pass < 3; pass++) {
            boxBlur(px, sw, sh, r, true);
            boxBlur(px, sw, sh, r, false);
        }
        saturate(px, 1.35f);
        Bitmap blurred = Bitmap.createBitmap(px, sw, sh, Bitmap.Config.ARGB_8888);
        Bitmap out = Bitmap.createScaledBitmap(blurred, w, h, true);
        blurred.recycle();
        return out;
    }

    private static void boxBlur(int[] px, int w, int h, int r, boolean horizontal) {
        int len = horizontal ? w : h, lines = horizontal ? h : w;
        int[] line = new int[len];
        int span = 2 * r + 1;
        for (int l = 0; l < lines; l++) {
            for (int i = 0; i < len; i++) line[i] = px[horizontal ? l * w + i : i * w + l];
            int sr = 0, sg = 0, sb = 0;
            for (int i = -r; i <= r; i++) {
                int c = line[Math.max(0, Math.min(len - 1, i))];
                sr += (c >> 16) & 255; sg += (c >> 8) & 255; sb += c & 255;
            }
            for (int i = 0; i < len; i++) {
                px[horizontal ? l * w + i : i * w + l] = 0xFF000000 | (sr / span) << 16 | (sg / span) << 8 | (sb / span);
                int out = line[Math.max(0, i - r)], in = line[Math.min(len - 1, i + r + 1)];
                sr += ((in >> 16) & 255) - ((out >> 16) & 255);
                sg += ((in >> 8) & 255) - ((out >> 8) & 255);
                sb += (in & 255) - (out & 255);
            }
        }
    }

    private static void saturate(int[] px, float s) {
        for (int i = 0; i < px.length; i++) {
            int c = px[i], r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
            float y = 0.299f * r + 0.587f * g + 0.114f * b;
            r = clamp(y + (r - y) * s); g = clamp(y + (g - y) * s); b = clamp(y + (b - y) * s);
            px[i] = 0xFF000000 | r << 16 | g << 8 | b;
        }
    }

    private static int clamp(float v) { return Math.max(0, Math.min(255, Math.round(v))); }
}
