package com.punch.attendance;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.FrameLayout;

public class MainActivity extends Activity {
    static final int LOC_REQ = 1, BG_REQ = 2;
    private FrameLayout root;
    /** Each prompt is shown at most once per screen, so a refused permission can never
     *  trap the app in an endless request loop (that froze v1.7 on start). */
    private boolean locAsked, bgPrompted, batPrompted, pendingLocate;
    private AlertDialog dlg;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        root = new FrameLayout(this);
        setContentView(root);
        WebHolder.attach(this, root);
    }

    @Override protected void onResume() {
        super.onResume();
        WebHolder.current = this;
        WebHolder.permChanged(this);
        WebHolder.js("window.__beat&&__beat()");   // clocks and timers show the right time at once on opening
        if (auto()) ensure();
    }

    private boolean auto() { return getSharedPreferences("punch", 0).getBoolean("auto", false); }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == WebHolder.FILE_REQ) WebHolder.fileResult(res, data);
        else if (req == WebHolder.AUDIO_REQ) WebHolder.audioResult(getApplicationContext(), res, data);
        else if (req == WebHolder.DOC_REQ) WebHolder.docResult(getApplicationContext(), res, data);
        else if (req == CloudSync.SIGNIN_REQ) CloudSync.result(getApplicationContext(), res, data);
    }

    @Override protected void onDestroy() {
        if (dlg != null && dlg.isShowing()) dlg.dismiss();
        WebHolder.detach(this);
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        android.webkit.WebView w = WebHolder.get(this);
        if (w.canGoBack()) w.goBack(); else moveTaskToBack(true);   // keep running in background
    }

    private boolean has(String p) { return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED; }

    private String[] locPerms() {
        return Build.VERSION.SDK_INT >= 33
                ? new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.POST_NOTIFICATIONS}
                : new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION};
    }

    /** Asked by the page (Check in, Maps, "check again" button). Always shows the system prompt
     *  because the user tapped something; Android itself stops prompting after two refusals. */
    void askLocation(boolean forLocate) {
        if (forLocate) pendingLocate = true;
        if (TrackerService.hasFine(this)) { afterLocationResult(); return; }
        requestPermissions(locPerms(), LOC_REQ);
    }

    /** Walks through: location -> "allow all the time" -> battery exemption, then starts the service. */
    void ensure() {
        if (!TrackerService.hasLocation(this)) {
            if (locAsked) return;               // already asked on this screen: don't loop
            locAsked = true;
            requestPermissions(locPerms(), LOC_REQ);
            return;
        }
        TrackerService.startSafely(this);
        if (Build.VERSION.SDK_INT >= 29 && !has(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
            if (bgPrompted) { batteryStep(); return; }
            bgPrompted = true;
            if (Build.VERSION.SDK_INT >= 30) {
                showDialog("Allow location all the time",
                        "For auto check-in with the screen off or the app closed, open Permissions > Location and choose \"Allow all the time\". Also keep \"Use precise location\" on.",
                        this::batteryStep);
            } else requestPermissions(new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION}, BG_REQ);
            return;
        }
        batteryStep();
    }

    private void batteryStep() {
        PowerManager pm = getSystemService(PowerManager.class);
        if (!batPrompted && pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
            batPrompted = true;
            try { startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName()))); } catch (Exception ignored) {}
        }
    }

    private void showDialog(String title, String msg, Runnable later) {
        if (isFinishing() || (dlg != null && dlg.isShowing())) return;
        dlg = new AlertDialog.Builder(this).setTitle(title).setMessage(msg)
                .setPositiveButton("Open settings", (d, w) -> openAppSettings())
                .setNegativeButton("Later", (d, w) -> { if (later != null) later.run(); })
                .show();
    }

    void openAppSettings() {
        try { startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))); } catch (Exception ignored) {}
    }

    private void afterLocationResult() {
        boolean fine = TrackerService.hasFine(this), any = TrackerService.hasLocation(this);
        WebHolder.permChanged(this);
        if (pendingLocate) {
            pendingLocate = false;
            if (fine) Locator.locate(getApplicationContext());
            else Locator.fail(any ? Locator.PRECISE : Locator.DENIED);
        }
        if (!fine) {
            showDialog(any ? "Turn on precise location" : "Location permission needed",
                    any ? "Punch has only approximate location, so check-in cannot tell if you are at the workplace. Open Permissions > Location, turn on \"Use precise location\" and choose \"Allow all the time\"."
                        : "Punch cannot read your location, so check-in and auto check-in will not work. Open Permissions > Location and choose \"Allow all the time\".",
                    null);
        }
        if (any && auto()) ensure();            // safe: ensure() never re-requests once location is granted
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code == LOC_REQ) afterLocationResult();
        else if (code == BG_REQ) batteryStep();
    }
}
