package com.punch.attendance;

import android.app.*;
import android.content.*;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;

/** Shift alarm, done natively so it rings on time even when the screen is off, the page is
 *  paused by Android, or the app was closed. The page sets the time with PunchNative.setShiftAlarm. */
public class AlarmReceiver extends BroadcastReceiver {
    static final String CH_ALARM = "punch_alarm2", CH_INFO = "punch_info";
    static final int NOTIF_ID = 7;

    @Override public void onReceive(Context c, Intent i) {
        c.getSharedPreferences("punch", 0).edit().putLong("alarm_at", 0).apply();
        notify(c, i.getStringExtra("t"), i.getStringExtra("b"), true);
        // Make sure the page catches up (shows the alarm screen when opened).
        WebHolder.js("window.__alarmChk&&window.__alarmChk()");
    }

    private static PendingIntent pi(Context c, String t, String b) {
        Intent i = new Intent(c, AlarmReceiver.class).putExtra("t", t).putExtra("b", b);
        return PendingIntent.getBroadcast(c, 43, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** at = epoch ms; 0 cancels. */
    static void schedule(Context c, long at, String t, String b) {
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            PendingIntent p = pi(c, t == null ? "Shift complete" : t, b == null ? "Time to check out." : b);
            am.cancel(p);
            c.getSharedPreferences("punch", 0).edit().putLong("alarm_at", at).apply();
            if (at <= System.currentTimeMillis()) return;
            PendingIntent show = PendingIntent.getActivity(c, 44, new Intent(c, MainActivity.class),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            try { am.setAlarmClock(new AlarmManager.AlarmClockInfo(at, show), p); }
            catch (SecurityException e) { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p); }
        } catch (Exception ignored) {}
    }

    private static void channels(NotificationManager nm) {
        NotificationChannel a = new NotificationChannel(CH_ALARM, "Shift alarm", NotificationManager.IMPORTANCE_HIGH);
        Uri snd = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        if (snd == null) snd = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        a.setSound(snd, new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
        a.enableVibration(true);
        a.setVibrationPattern(new long[]{0, 300, 150, 300, 600});
        a.setBypassDnd(true);
        a.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(a);
        NotificationChannel n = new NotificationChannel(CH_INFO, "Punch messages", NotificationManager.IMPORTANCE_DEFAULT);
        nm.createNotificationChannel(n);
    }

    /** loud = ring and vibrate until opened (used when the app is not on screen). */
    static void notify(Context c, String t, String b, boolean loud) {
        try {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            channels(nm);
            PendingIntent open = PendingIntent.getActivity(c, 45, new Intent(c, MainActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification.Builder nb = new Notification.Builder(c, loud ? CH_ALARM : CH_INFO)
                    .setSmallIcon(R.drawable.ic_notif)
                    .setContentTitle(t == null ? "Punch" : t)
                    .setContentText(b == null ? "" : b)
                    .setStyle(new Notification.BigTextStyle().bigText(b == null ? "" : b))
                    .setContentIntent(open).setAutoCancel(true)
                    .setCategory(loud ? Notification.CATEGORY_ALARM : Notification.CATEGORY_REMINDER);
            if (loud) nb.setFullScreenIntent(open, true);
            Notification n = nb.build();
            if (loud) n.flags |= Notification.FLAG_INSISTENT;
            nm.notify(NOTIF_ID, n);
        } catch (Exception ignored) {}
    }

    static void cancel(Context c) {
        try { c.getSystemService(NotificationManager.class).cancel(NOTIF_ID); } catch (Exception ignored) {}
    }

    /** Restores a pending alarm after a phone restart. */
    static void restore(Context c) {
        long at = c.getSharedPreferences("punch", 0).getLong("alarm_at", 0);
        if (at > System.currentTimeMillis()) schedule(c, at, null, null);
    }
}
