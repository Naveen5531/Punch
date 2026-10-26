package com.punch.attendance;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.*;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.ViewGroup;
import android.webkit.*;
import android.widget.Toast;
import androidx.webkit.WebViewAssetLoader;
import java.io.*;

/** One WebView for the whole process. It lives as long as the process (kept alive by
 *  TrackerService), so the attendance logic in index.html keeps running with the screen
 *  off, the phone locked, or the activity closed. */
public class WebHolder {
    static final String HOME = "https://appassets.androidplatform.net/assets/index.html";
    static final String HOST = "appassets.androidplatform.net";
    private static WebView web;
    private static MutableContextWrapper ctx;
    static Activity current;
    static final int FILE_REQ = 77, AUDIO_REQ = 78, DOC_REQ = 79;
    static String docKind = "hols";
    static ValueCallback<Uri[]> fileCb;

    /** Alarm tone picked natively: read the file and hand it to the page as base64. */
    static void audioResult(final Context app, int code, Intent data) {
        deliver(app, code, data, 12_000_000, "onNativeAudio", "onNativeAudioErr", null, "That file is too large. Choose a tone under 12 MB.");
    }

    /** Holiday list (xlsx / csv) picked natively. */
    static void docResult(final Context app, int code, Intent data) {
        deliver(app, code, data, 5_000_000, "onNativeDoc", "onNativeDocErr", docKind, "That file is too large.");
    }

    private static void deliver(final Context app, int code, Intent data, final int max, final String fn, final String errFn, final String kind, final String tooBig) {
        if (code != Activity.RESULT_OK || data == null || data.getData() == null || web == null) return;
        final Uri u = data.getData();
        new Thread(() -> {
            try {
                ContentResolver cr = app.getContentResolver();
                String name = "file";
                try (android.database.Cursor c = cr.query(u, null, null, null, null)) {
                    if (c != null && c.moveToFirst()) { int i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME); if (i >= 0) name = c.getString(i); }
                }
                String mime = cr.getType(u);
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                try (InputStream in = cr.openInputStream(u)) {
                    byte[] buf = new byte[16384]; int n, tot = 0;
                    while ((n = in.read(buf)) > 0) {
                        tot += n;
                        if (tot > max) { js("window." + errFn + "&&window." + errFn + "(" + quote(tooBig) + ")"); return; }
                        bo.write(buf, 0, n);
                    }
                }
                final String b64 = Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP);
                String pre = kind == null ? "" : quote(kind) + ",";
                js("window." + fn + "&&window." + fn + "(" + pre + quote(name) + "," + quote(mime == null ? "application/octet-stream" : mime) + ",'" + b64 + "')");
            } catch (Exception e) { js("window." + errFn + "&&window." + errFn + "('Could not read that file.')"); }
        }).start();
    }

    static void js(final String code) { if (web != null) web.post(() -> web.evaluateJavascript(code, null)); }

    /** Called from MainActivity.onActivityResult. */
    static void fileResult(int code, Intent data) {
        if (fileCb == null) return;
        fileCb.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(code, data));
        fileCb = null;
    }

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    static synchronized WebView get(Context c) {
        if (web != null) return web;
        final Context app = c.getApplicationContext();
        ctx = new MutableContextWrapper(app);
        web = new WebView(ctx);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setGeolocationEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(app)).build();

        web.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                return loader.shouldInterceptRequest(r.getUrl());
            }
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if (HOST.equals(u.getHost())) return false;
                try { app.startActivity(new Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception ignored) {}
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onJsAlert(WebView v, String url, String msg, JsResult r) {
                Activity a = current;
                if (a == null || a.isFinishing()) { r.confirm(); return true; }
                new android.app.AlertDialog.Builder(a).setMessage(msg).setCancelable(false)
                        .setPositiveButton("OK", (d, w) -> r.confirm()).show();
                return true;
            }
            @Override public boolean onJsConfirm(WebView v, String url, String msg, JsResult r) {
                Activity a = current;
                if (a == null || a.isFinishing()) { r.cancel(); return true; }
                new android.app.AlertDialog.Builder(a).setMessage(msg)
                        .setPositiveButton("OK", (d, w) -> r.confirm())
                        .setNegativeButton("Cancel", (d, w) -> r.cancel())
                        .setOnCancelListener(d -> r.cancel()).show();
                return true;
            }
            @Override public boolean onJsPrompt(WebView v, String url, String msg, String def, JsPromptResult r) {
                Activity a = current;
                if (a == null || a.isFinishing()) { r.cancel(); return true; }
                final android.widget.EditText in = new android.widget.EditText(a);
                if (def != null) in.setText(def);
                new android.app.AlertDialog.Builder(a).setMessage(msg).setView(in)
                        .setPositiveButton("OK", (d, w) -> r.confirm(in.getText().toString()))
                        .setNegativeButton("Cancel", (d, w) -> r.cancel())
                        .setOnCancelListener(d -> r.cancel()).show();
                return true;
            }

            @Override public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback cb) {
                cb.invoke(origin, true, false);
            }

            /** Makes <input type="file"> work (e.g. Shift alarm > Choose audio file, payslip logo). */
            @Override public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = cb;
                Activity a = current;
                if (a == null) { fileCb = null; return false; }
                try {
                    Intent i = params.createIntent();
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    a.startActivityForResult(Intent.createChooser(i, "Choose file"), FILE_REQ);
                } catch (Exception e) {
                    fileCb = null;
                    Toast.makeText(a, "No file picker found on this phone", Toast.LENGTH_LONG).show();
                    return false;
                }
                return true;
            }
        });
        web.setDownloadListener((url, ua, cd, mime, len) -> {
            if (url.startsWith("blob:")) {
                String name = URLUtil.guessFileName(url, cd, mime);
                if (name == null || name.matches("(?i)^[0-9a-f\\-]{20,}.*")) name = "Punch_export.csv";
                web.evaluateJavascript("(function(){var u='" + url + "',n=(window.__dl||" + quote(name) + ");"
                        + "fetch(u).then(function(r){return r.blob()}).then(function(b){var f=new FileReader();"
                        + "f.onloadend=function(){PunchNative.saveFile(n,b.type||'text/csv',String(f.result).split(',')[1])};"
                        + "f.readAsDataURL(b)})})()", null);
            }
        });
        web.addJavascriptInterface(new Bridge(app), "PunchNative");
        web.loadUrl(HOME);
        web.resumeTimers();
        return web;
    }

    static String quote(String s) { return "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'"; }

    static void attach(Activity a, ViewGroup parent) {
        current = a;
        WebView w = get(a);
        ctx.setBaseContext(a);
        if (w.getParent() instanceof ViewGroup) ((ViewGroup) w.getParent()).removeView(w);
        parent.addView(w, new ViewGroup.LayoutParams(-1, -1));
    }

    static void detach(Activity a) {
        if (current == a) current = null;
        if (web != null) {
            if (web.getParent() instanceof ViewGroup) ((ViewGroup) web.getParent()).removeView(web);
            ctx.setBaseContext(a.getApplicationContext());
            web.resumeTimers(); // never pause: attendance logic must keep running in background
        }
    }

    /** A location from the background service; provider "gps" or "network" (Wi-Fi / mobile network, less exact). */
    static void push(double la, double lo, double ac, String provider, long t) {
        if (web == null) return;
        final String js = "window.onNativePos&&window.onNativePos(" + la + "," + lo + "," + ac + "," + quote(provider == null ? "" : provider) + "," + t + ")";
        web.post(() -> { web.resumeTimers(); web.evaluateJavascript(js, null); });
    }

    /** Tells the page whether Android location permission is granted (shows / hides the banner). */
    static void permChanged(Context c) {
        js("window.onNativePerm&&window.onNativePerm(" + TrackerService.hasFine(c) + "," + TrackerService.hasLocation(c) + ")");
    }

    static void heartbeat() {
        if (web == null) return;
        web.post(() -> { web.resumeTimers(); web.evaluateJavascript("window.graceCheck&&graceCheck();window.__alarmChk&&__alarmChk()", null); });
    }

    /** Methods callable from index.html as window.PunchNative.* */
    static class Bridge {
        private final Context app;
        Bridge(Context app) { this.app = app; }

        @JavascriptInterface public void start() {
            app.getSharedPreferences("punch", 0).edit().putBoolean("auto", true).apply();
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                Activity a = current;
                if (a instanceof MainActivity) ((MainActivity) a).ensure();
                else TrackerService.startSafely(app);
            });
        }

        @JavascriptInterface public void stop() {
            app.getSharedPreferences("punch", 0).edit().putBoolean("auto", false).putLong("hb_last", 0).apply();
            TrackerService.cancelRestart(app);
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> app.stopService(new Intent(app, TrackerService.class)));
        }

        /** Shift alarm: exact native alarm at epoch ms (0 = cancel). Rings even if the page is paused. */
        @JavascriptInterface public void setShiftAlarm(double at, String t, String b) {
            AlarmReceiver.schedule(app, (long) at, t, b);
        }

        /** Missed check-out reminder (epoch ms; 0 cancels). */
        @JavascriptInterface public void setReminder(double at, String t, String b) {
            AlarmReceiver.scheduleReminder(app, (long) at, t, b);
        }

        /** Notification from the page. loud = alarm sound + vibration until opened. */
        @JavascriptInterface public void notify(String t, String b, boolean loud) { AlarmReceiver.notify(app, t, b, loud); }

        @JavascriptInterface public void cancelNotify() { AlarmReceiver.cancel(app); }

        @JavascriptInterface public boolean canNotify() {
            return Build.VERSION.SDK_INT < 33 || app.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED;
        }

        @JavascriptInterface public void askNotify() {
            if (Build.VERSION.SDK_INT < 33 || canNotify()) return;
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                Activity a = current;
                if (a != null) a.requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 5);
            });
        }

        /** Android share sheet (WhatsApp, mail, ...). */
        @JavascriptInterface public void share(String title, String text) {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                try {
                    Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_SUBJECT, title).putExtra(Intent.EXTRA_TEXT, text);
                    Intent ch = Intent.createChooser(i, title == null ? "Share" : title);
                    Activity a = current;
                    if (a != null) a.startActivity(ch); else app.startActivity(ch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (Exception e) { toast("No app found to share with"); }
            });
        }

        @JavascriptInterface public void copy(String text) {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                try {
                    ClipboardManager cm = (ClipboardManager) app.getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("Punch", text));
                } catch (Exception ignored) {}
            });
        }

        @JavascriptInterface public void vibrate(int ms) {
            try {
                android.os.Vibrator v = (android.os.Vibrator) app.getSystemService(Context.VIBRATOR_SERVICE);
                if (v != null) v.vibrate(android.os.VibrationEffect.createOneShot(Math.max(50, Math.min(ms, 3000)), android.os.VibrationEffect.DEFAULT_AMPLITUDE));
            } catch (Exception ignored) {}
        }

        /** Readings saved by the service after time t (ms), as JSON, for the page to replay. */
        @JavascriptInterface public String fixesSince(double t) { return FixLog.since(app, (long) t); }

        /** Times when the phone stopped tracking, as JSON [[from,to],...]. */
        @JavascriptInterface public String gaps() { return FixLog.gaps(app); }
        @JavascriptInterface public void clearGaps() { FixLog.clearGaps(app); }

        /** Tracking check: everything that decides whether tracking works with the app closed. */
        @JavascriptInterface public String health() {
            android.content.SharedPreferences p = app.getSharedPreferences("punch", 0);
            boolean bat = false, gps = true;
            try { bat = ((android.os.PowerManager) app.getSystemService(Context.POWER_SERVICE)).isIgnoringBatteryOptimizations(app.getPackageName()); } catch (Exception ignored) {}
            try { if (Build.VERSION.SDK_INT >= 28) gps = ((android.location.LocationManager) app.getSystemService(Context.LOCATION_SERVICE)).isLocationEnabled(); } catch (Exception ignored) {}
            String man = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.toLowerCase();
            return "{\"fine\":" + TrackerService.hasFine(app) + ",\"bg\":" + TrackerService.hasBackground(app)
                    + ",\"battery\":" + bat + ",\"gps\":" + gps + ",\"running\":" + TrackerService.running
                    + ",\"auto\":" + p.getBoolean("auto", false) + ",\"lastFix\":" + p.getLong("last_fix", 0)
                    + ",\"beat\":" + p.getLong("hb_last", 0) + ",\"notif\":" + canNotify()
                    + ",\"autostart\":" + autoStart() + ",\"maker\":\"" + man.replaceAll("[^a-z]", "") + "\"}";
        }

        /** Xiaomi / Poco / Redmi (MIUI, HyperOS) keep Autostart as app-op 10008. 1 = on, 0 = off, -1 = cannot tell. */
        private int autoStart() {
            try {
                android.app.AppOpsManager ao = (android.app.AppOpsManager) app.getSystemService(Context.APP_OPS_SERVICE);
                java.lang.reflect.Method m = android.app.AppOpsManager.class.getMethod("checkOpNoThrow", int.class, int.class, String.class);
                int r = (Integer) m.invoke(ao, 10008, android.os.Process.myUid(), app.getPackageName());
                return r == android.app.AppOpsManager.MODE_ALLOWED ? 1 : r == android.app.AppOpsManager.MODE_IGNORED || r == android.app.AppOpsManager.MODE_ERRORED ? 0 : -1;
            } catch (Throwable t) { return -1; }
        }

        /** Opens the screen that fixes one tracking setting: battery, autostart (Xiaomi / Poco / Oppo / Vivo ...) or app settings. */
        @JavascriptInterface public void fixSetting(final String what) {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                Activity a = current;
                Context cx = a != null ? a : app;
                java.util.List<Intent> tries = new java.util.ArrayList<>();
                String pkg = app.getPackageName();
                if ("battery".equals(what)) {
                    tries.add(new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + pkg)));
                    tries.add(new Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                } else if ("autostart".equals(what)) {
                    tries.add(new Intent().setComponent(new ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")));
                    tries.add(new Intent("miui.intent.action.OP_AUTO_START").addCategory(Intent.CATEGORY_DEFAULT));
                    tries.add(new Intent().setComponent(new ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")));
                    tries.add(new Intent().setComponent(new ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")));
                    tries.add(new Intent().setComponent(new ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")));
                } else if ("gps".equals(what)) {
                    tries.add(new Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS));
                }
                tries.add(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg)));
                for (Intent i : tries) {
                    try { if (a == null) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); cx.startActivity(i); return; } catch (Exception ignored) {}
                }
                toast("Open Settings > Apps > Punch");
            });
        }

        /** Location: 1 = precise, 0 = approximate only, -1 = none. */
        @JavascriptInterface public int locationState() {
            return TrackerService.hasFine(app) ? 1 : TrackerService.hasLocation(app) ? 0 : -1;
        }

        /** One GPS fix for the page; answers via window.onNativeFix / window.onNativeFixErr. */
        @JavascriptInterface public void locate() {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> Locator.locate(app));
        }

        /** Shows Android's location permission prompt (or the settings hint if refused before). */
        @JavascriptInterface public void askLocation() {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                Activity a = current;
                if (a instanceof MainActivity) ((MainActivity) a).askLocation(false);
            });
        }

        @JavascriptInterface public void openAppSettings() {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                Activity a = current;
                if (a instanceof MainActivity) ((MainActivity) a).openAppSettings();
            });
        }

        /** Small native key/value store (survives WebView storage being cleared): used for "Keep me logged in". */
        // ---------- Google sync (CloudSync) ----------
        @JavascriptInterface public void cloudSignIn() {
            final Activity a = current;
            if (a == null) return;
            a.runOnUiThread(() -> CloudSync.signIn(a));
        }
        @JavascriptInterface public void cloudSignOut() { CloudSync.signOut(app); }
        @JavascriptInterface public void cloudPush(String json) { CloudSync.push(app, json); }
        @JavascriptInterface public void cloudPull() { CloudSync.pull(app); }
        @JavascriptInterface public String cloudState() { return CloudSync.state(app); }

        @JavascriptInterface public String getPref(String k) {
            return app.getSharedPreferences("punch_web", 0).getString(k, null);
        }

        @JavascriptInterface public void setPref(String k, String v) {
            SharedPreferences.Editor e = app.getSharedPreferences("punch_web", 0).edit();
            if (v == null) e.remove(k); else e.putString(k, v);
            e.commit();
        }

        /** Shift alarm > Choose audio file: opens the phone's own picker (does not rely on the WebView chooser). */
        @JavascriptInterface public void pickAudio() {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                Activity a = current;
                if (a == null) { toast("Open the app screen first"); return; }
                try {
                    Intent i = new Intent(Intent.ACTION_GET_CONTENT).setType("audio/*").addCategory(Intent.CATEGORY_OPENABLE);
                    a.startActivityForResult(Intent.createChooser(i, "Choose alarm tone"), AUDIO_REQ);
                } catch (Exception e) { toast("No file picker found on this phone"); }
            });
        }

        /** Holidays: pick an Excel (.xlsx) or .csv file with the phone's own picker. */
        @JavascriptInterface public void pickDoc(final String kind) {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                Activity a = current;
                if (a == null) { toast("Open the app screen first"); return; }
                docKind = kind;
                try {
                    Intent i = new Intent(Intent.ACTION_GET_CONTENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
                    i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/vnd.ms-excel",
                            "text/csv", "text/comma-separated-values", "text/plain", "application/octet-stream"});
                    a.startActivityForResult(Intent.createChooser(i, "Choose holiday list (Excel or CSV)"), DOC_REQ);
                } catch (Exception e) { toast("No file picker found on this phone"); }
            });
        }

        /** Payslip: opens Android's print dialog (Save as PDF) with a proper file name. */
        @JavascriptInterface public void printPage(final String name) {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                Activity a = current;
                if (a == null || web == null) { toast("Open the app screen to print"); return; }
                android.print.PrintManager pm = (android.print.PrintManager) a.getSystemService(Context.PRINT_SERVICE);
                android.print.PrintAttributes at = new android.print.PrintAttributes.Builder()
                        .setMediaSize(android.print.PrintAttributes.MediaSize.ISO_A4)
                        .setColorMode(android.print.PrintAttributes.COLOR_MODE_COLOR)
                        .setMinMargins(android.print.PrintAttributes.Margins.NO_MARGINS).build();
                pm.print(name, web.createPrintDocumentAdapter(name), at);
            });
        }

        @JavascriptInterface public void saveFile(String name, String mime, String b64) {
            try {
                byte[] data = Base64.decode(b64, Base64.DEFAULT);
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.Downloads.DISPLAY_NAME, name);
                    v.put(MediaStore.Downloads.MIME_TYPE, mime);
                    v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                    Uri u = app.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                    try (OutputStream o = app.getContentResolver().openOutputStream(u)) { o.write(data); }
                } else {
                    File d = app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                    try (OutputStream o = new FileOutputStream(new File(d, name))) { o.write(data); }
                }
                toast("Saved to Downloads: " + name);
            } catch (Exception e) { toast("Could not save file"); }
        }

        private void toast(String m) {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> Toast.makeText(app, m, Toast.LENGTH_LONG).show());
        }
    }
}
