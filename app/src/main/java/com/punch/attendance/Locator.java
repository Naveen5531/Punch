package com.punch.attendance;

import android.app.Activity;
import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

/** One-shot native GPS fix for the page (Check in / Check out, Maps "my location").
 *  Replaces the WebView's navigator.geolocation, which fails silently inside the app
 *  when Android permission is missing, approximate-only, or GPS is slow. */
final class Locator {
    static final String DENIED = "Location permission is off for Punch. Tap Allow, or open App info > Permissions > Location and choose \"Allow all the time\".";
    static final String PRECISE = "Punch has only approximate location. Open App info > Permissions > Location and turn on \"Use precise location\".";
    static final String GPS_OFF = "Location is turned off on the phone. Turn on Location (GPS) from quick settings and try again.";
    static final String NO_FIX = "Could not get your location. Move to an open area and try again.";

    private static final long TIMEOUT_MS = 25_000;
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static boolean busy;
    private static Location best;
    private static LocationListener listener;
    private static Runnable timeout;

    private Locator() {}

    /** Must run on the main thread. */
    static void locate(Context app) {
        if (busy) return;                       // the running request answers every caller
        if (!TrackerService.hasFine(app)) {
            Activity a = WebHolder.current;
            if (a instanceof MainActivity) { ((MainActivity) a).askLocation(true); return; }
            fail(TrackerService.hasLocation(app) ? PRECISE : DENIED);
            return;
        }
        final LocationManager lm = (LocationManager) app.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) { fail(NO_FIX); return; }
        if (Build.VERSION.SDK_INT >= 28 && !lm.isLocationEnabled()) { fail(GPS_OFF); return; }

        // A very recent, accurate fix (e.g. from the tracking service) is good enough.
        best = null;
        for (String p : lm.getProviders(true)) {
            try {
                Location l = lm.getLastKnownLocation(p);
                if (l != null && ageMs(l) < 30_000 && better(l)) best = l;
            } catch (SecurityException ignored) {}
        }
        if (best != null && best.hasAccuracy() && best.getAccuracy() <= 50) { done(); return; }

        busy = true;
        listener = new LocationListener() {
            @Override public void onLocationChanged(Location l) {
                if (better(l)) best = l;
                if (best != null && best.hasAccuracy() && best.getAccuracy() <= 30) finish(lm);
            }
            @Override public void onProviderEnabled(String p) {}
            @Override public void onProviderDisabled(String p) {}
            @Override public void onStatusChanged(String p, int s, Bundle b) {}
        };
        boolean any = false;
        for (String p : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
            try {
                if (lm.isProviderEnabled(p)) { lm.requestLocationUpdates(p, 1000, 0, listener, Looper.getMainLooper()); any = true; }
            } catch (Exception ignored) {}
        }
        if (!any) { cleanup(lm); fail(GPS_OFF); return; }
        timeout = () -> finish(lm);
        main.postDelayed(timeout, TIMEOUT_MS);
    }

    private static void finish(LocationManager lm) {
        cleanup(lm);
        if (best == null) {
            // Last resort: any fix from the last 5 minutes.
            for (String p : lm.getProviders(true)) {
                try {
                    Location l = lm.getLastKnownLocation(p);
                    if (l != null && ageMs(l) < 300_000 && better(l)) best = l;
                } catch (SecurityException ignored) {}
            }
        }
        if (best == null) fail(NO_FIX); else done();
    }

    private static void cleanup(LocationManager lm) {
        busy = false;
        if (timeout != null) { main.removeCallbacks(timeout); timeout = null; }
        if (listener != null) { try { lm.removeUpdates(listener); } catch (Exception ignored) {} listener = null; }
    }

    private static boolean better(Location l) {
        if (best == null) return true;
        float a = l.hasAccuracy() ? l.getAccuracy() : 9999, b = best.hasAccuracy() ? best.getAccuracy() : 9999;
        return a <= b || ageMs(best) - ageMs(l) > 60_000;
    }

    private static long ageMs(Location l) {
        return (SystemClock.elapsedRealtimeNanos() - l.getElapsedRealtimeNanos()) / 1_000_000L;
    }

    private static void done() {
        Location l = best; best = null;
        WebHolder.js("window.onNativeFix&&window.onNativeFix(" + l.getLatitude() + "," + l.getLongitude() + ","
                + (l.hasAccuracy() ? l.getAccuracy() : 50) + "," + WebHolder.quote(l.getProvider() == null ? "" : l.getProvider()) + ")");
    }

    static void fail(String msg) {
        WebHolder.js("window.onNativeFixErr&&window.onNativeFixErr(" + WebHolder.quote(msg) + ")");
    }
}
