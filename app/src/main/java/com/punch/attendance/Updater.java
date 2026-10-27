package com.punch.attendance;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.*;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.webkit.WebView;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;

/** In-app update: downloads the new Punch.apk inside the app and hands it to Android's installer.
 *  Progress and every failure go back to the page as window.__updState(state, text) so the user sees why. */
public class Updater extends BroadcastReceiver {
    static final String ACTION = "com.punch.attendance.UPDATE_STATUS";
    private static volatile boolean busy = false;
    private static String pendingUrl = null;

    static void say(String state, String text) {
        new Handler(Looper.getMainLooper()).post(() -> {
            WebView w = WebHolder.webView();
            if (w != null) w.evaluateJavascript("window.__updState&&__updState(" + WebHolder.quote(state) + "," + WebHolder.quote(text) + ")", null);
        });
    }

    static void start(final Context app, final String url) {
        if (busy) { say("busy", "The update is already downloading…"); return; }
        if (Build.VERSION.SDK_INT >= 26 && !app.getPackageManager().canRequestPackageInstalls()) {
            pendingUrl = url;
            say("perm", "Allow Punch to install updates: turn on \"Allow from this source\", then come back to Punch.");
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + app.getPackageName()));
                Activity a = WebHolder.current;
                if (a != null) a.startActivity(i); else app.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception e) { say("fail", "Could not open the install permission screen. Open Settings › Apps › Punch › Install unknown apps and allow it."); }
            return;
        }
        busy = true;
        new Thread(() -> {
            File f = new File(app.getCacheDir(), "Punch-update.apk");
            try {
                download(url, f);
                install(app, f);
            } catch (Exception e) {
                say("fail", "Update failed: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()) + ". Check the internet and try again.");
            } finally { busy = false; }
        }).start();
    }

    /** Called when Punch comes back to the front: if the user just allowed installs, carry on. */
    static void resume(Context app) {
        if (pendingUrl != null && (Build.VERSION.SDK_INT < 26 || app.getPackageManager().canRequestPackageInstalls())) {
            String u = pendingUrl; pendingUrl = null; start(app, u);
        }
    }

    private static void download(String url, File out) throws IOException {
        for (int hop = 0; hop < 5; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url + (url.contains("?") ? "&" : "?") + "t=" + System.currentTimeMillis()).openConnection();
            c.setConnectTimeout(20000); c.setReadTimeout(30000); c.setUseCaches(false); c.setInstanceFollowRedirects(true);
            int code = c.getResponseCode();
            if (code >= 300 && code < 400 && c.getHeaderField("Location") != null) { url = c.getHeaderField("Location"); c.disconnect(); continue; }
            if (code != 200) { c.disconnect(); throw new IOException("the website answered " + code); }
            long total = c.getContentLengthLong(), got = 0; int last = -1;
            try (InputStream in = c.getInputStream(); OutputStream o = new FileOutputStream(out)) {
                byte[] b = new byte[64 * 1024]; int n;
                while ((n = in.read(b)) > 0) {
                    o.write(b, 0, n); got += n;
                    int p = total > 0 ? (int) (got * 100 / total) : -1;
                    if (p != last && (p < 0 || p % 5 == 0)) { last = p; say("dl", p >= 0 ? "Downloading the update… " + p + "%" : "Downloading the update… " + (got / 1024) + " KB"); }
                }
            } finally { c.disconnect(); }
            if (total > 0 && got != total) throw new IOException("the download was cut off");
            if (got < 100_000) throw new IOException("the downloaded file is not a valid app");
            return;
        }
        throw new IOException("too many redirects");
    }

    private static void install(Context app, File f) throws IOException {
        say("inst", "Download complete. Opening the installer…");
        PackageInstaller pi = app.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams p = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        p.setAppPackageName(app.getPackageName());
        if (Build.VERSION.SDK_INT >= 31) p.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        int id = pi.createSession(p);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            try (OutputStream o = s.openWrite("punch", 0, f.length()); InputStream in = new FileInputStream(f)) {
                byte[] b = new byte[64 * 1024]; int n;
                while ((n = in.read(b)) > 0) o.write(b, 0, n);
                s.fsync(o);
            }
            Intent i = new Intent(app, Updater.class).setAction(ACTION);
            int fl = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            s.commit(PendingIntent.getBroadcast(app, id, i, fl).getIntentSender());
        }
    }

    @Override public void onReceive(Context ctx, Intent i) {
        int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent c = i.getParcelableExtra(Intent.EXTRA_INTENT);
            if (c != null) {
                say("inst", "Tap Update / Install on Android's screen to finish.");
                try {
                    Activity a = WebHolder.current;
                    if (a != null) a.startActivity(c); else ctx.startActivity(c.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (Exception e) { say("fail", "Could not open Android's installer."); }
            }
        } else if (st == PackageInstaller.STATUS_SUCCESS) {
            say("ok", "Punch is updated.");
        } else {
            String m = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            String why = st == PackageInstaller.STATUS_FAILURE_ABORTED ? "the install was cancelled"
                    : st == PackageInstaller.STATUS_FAILURE_CONFLICT ? "the new app is signed differently from the one on this phone (uninstall once and install the new APK)"
                    : st == PackageInstaller.STATUS_FAILURE_STORAGE ? "not enough free space on the phone"
                    : st == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ? "this phone cannot run that version"
                    : (m == null ? "Android refused the install" : m);
            say("fail", "Update failed: " + why + ".");
        }
    }
}
