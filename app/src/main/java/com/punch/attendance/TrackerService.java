package com.punch.attendance;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.location.*;
import android.os.*;

/** Foreground service: keeps the process alive and delivers GPS fixes to the page
 *  even when the screen is off or the phone is locked. */
public class TrackerService extends Service implements LocationListener {
    private static final String CH = "punch_tracking";
    private PowerManager.WakeLock wl;
    private LocationManager lm;
    /** Set when startForeground was refused, so onDestroy does not schedule a restart loop. */
    private boolean failed;
    /** True while the tracking service is alive (shown in the page's tracking check). */
    static volatile boolean running;
    private final Handler beat = new Handler(Looper.getMainLooper());
    private final Runnable beatTask = new Runnable() {
        @Override public void run() { FixLog.beat(TrackerService.this); WebHolder.heartbeat(); beat.postDelayed(this, 60_000); }
    };

    static final long WATCHDOG_MS = 15 * 60_000L;

    static boolean granted(Context c, String p) { return c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED; }
    static boolean hasFine(Context c) { return granted(c, Manifest.permission.ACCESS_FINE_LOCATION); }
    static boolean hasLocation(Context c) { return hasFine(c) || granted(c, Manifest.permission.ACCESS_COARSE_LOCATION); }
    static boolean hasBackground(Context c) { return Build.VERSION.SDK_INT < 29 || granted(c, Manifest.permission.ACCESS_BACKGROUND_LOCATION); }

    /** Schedules a (re)start of this service; used after swipe-away, kill and as a 15-minute watchdog. */
    static void scheduleRestart(Context c, long delayMs) {
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            PendingIntent pi = PendingIntent.getBroadcast(c, 42, new Intent(c, RestartReceiver.class),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            long at = SystemClock.elapsedRealtime() + delayMs;
            try { am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi); }
            catch (SecurityException e) { am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi); }
        } catch (Exception ignored) {}
    }

    static void cancelRestart(Context c) {
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            am.cancel(PendingIntent.getBroadcast(c, 42, new Intent(c, RestartReceiver.class),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        } catch (Exception ignored) {}
    }

    /** Start from the app screen. Never starts without location permission: a location
     *  foreground service without it makes Android kill the whole app ("keeps stopping"). */
    static void startSafely(Context c) {
        if (!hasLocation(c)) return;
        try { c.startForegroundService(new Intent(c, TrackerService.class)); } catch (Exception ignored) {}
    }

    /** Start from boot / restart alarm (app not on screen). Android 14+ only allows a background
     *  start of a location service when "Allow all the time" is granted. */
    static void startFromBackground(Context c) {
        if (Build.VERSION.SDK_INT >= 34 && !hasBackground(c)) return;
        startSafely(c);
    }

    @Override public int onStartCommand(Intent i, int flags, int id) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CH, "Attendance tracking", NotificationManager.IMPORTANCE_LOW));
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, CH)
                .setSmallIcon(R.drawable.ic_notif).setContentTitle("Punch is running")
                .setContentText("Tracking location for auto check-in / check-out")
                .setContentIntent(pi).setOngoing(true).build();
        try {
            if (Build.VERSION.SDK_INT >= 29)
                startForeground(1, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            else startForeground(1, n);
        } catch (Exception e) { failed = true; stopSelf(); return START_NOT_STICKY; }
        failed = false;
        running = true;
        FixLog.beat(this);                    // records a gap if the phone had stopped Punch

        if (wl == null) {
            wl = ((PowerManager) getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "punch:track");
            wl.setReferenceCounted(false);
            wl.acquire();
        }
        try { WebHolder.get(this); } catch (Exception ignored) {}   // page logic running (also after reboot)
        beat.removeCallbacks(beatTask); beat.postDelayed(beatTask, 60_000);
        register();
        scheduleRestart(this, WATCHDOG_MS);   // watchdog: brings the service back if the system kills it
        return START_STICKY;
    }

    private void register() {
        if (!hasLocation(this)) return;
        lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        lm.removeUpdates(this);
        try {
            if (hasFine(this) && lm.isProviderEnabled(LocationManager.GPS_PROVIDER))
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 20_000, 5, this, Looper.getMainLooper());
        } catch (Exception ignored) {}
        try {
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 30_000, 0, this, Looper.getMainLooper());
        } catch (Exception ignored) {}
        // Free extra readings whenever any other app (Maps, weather, ...) gets a location.
        try { lm.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, 10_000, 0, this, Looper.getMainLooper()); }
        catch (Exception ignored) {}
    }

    @Override public void onLocationChanged(Location l) {
        FixLog.add(this, l);
        WebHolder.push(l.getLatitude(), l.getLongitude(), l.hasAccuracy() ? l.getAccuracy() : 50, l.getProvider(), l.getTime());
    }
    @Override public void onProviderEnabled(String p) { register(); }
    @Override public void onProviderDisabled(String p) {}
    @Override public void onStatusChanged(String p, int s, Bundle b) {}

    private boolean shouldRestart() {
        return !failed && hasLocation(this) && getSharedPreferences("punch", 0).getBoolean("auto", false);
    }

    /** User swiped Punch away from recent apps: come back automatically. */
    @Override public void onTaskRemoved(Intent root) {
        if (shouldRestart()) scheduleRestart(this, 2_000);
        super.onTaskRemoved(root);
    }

    @Override public void onDestroy() {
        running = false;
        if (shouldRestart()) scheduleRestart(this, 3_000);
        beat.removeCallbacks(beatTask);
        if (lm != null) lm.removeUpdates(this);
        if (wl != null && wl.isHeld()) wl.release();
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent i) { return null; }
}
