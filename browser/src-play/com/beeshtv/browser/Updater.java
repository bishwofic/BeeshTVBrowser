package com.beeshtv.browser;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.Settings;

/**
 * The Google Play build's Updater (built by `build.sh --play` in place of src/.../Updater.java).
 * Play updates the app itself and doesn't allow apps to update any other way, so this never looks
 * for, downloads or installs anything; it only says which version is installed.
 */
final class Updater {
    /** This build came from Google Play: no updates from GitHub. */
    static final boolean PLAY = true;
    static final String REPO = "bishwofic/BeeshTVBrowser";

    enum State { IDLE, CHECKING, UP_TO_DATE, DOWNLOADING, READY, FAILED }

    private final Context app;
    /** Never called: nothing here changes. */
    Runnable onChange;

    Updater(Context c, SharedPreferences sp) { app = c.getApplicationContext(); }

    State state() { return State.IDLE; }
    String latest() { return null; }
    String error() { return null; }
    int progress() { return 0; }

    boolean autoDownload() { return false; }
    void setAutoDownload(boolean v) { }

    String installed() {
        try { return app.getPackageManager().getPackageInfo(app.getPackageName(), 0).versionName; }
        catch (PackageManager.NameNotFoundException e) { return "0"; }
    }

    void check(boolean force) { }
    void download() { }
    void install() { }

    /** Nothing to allow: Google Play installs the updates. */
    boolean canInstall() { return true; }

    Intent installPermissionScreen() {
        return new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + app.getPackageName()))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    static boolean isNewer(String a, String b) { return false; }
}
