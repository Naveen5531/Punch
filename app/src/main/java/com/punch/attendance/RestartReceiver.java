package com.punch.attendance;

import android.content.*;

/** Fired by the alarm that TrackerService schedules when the app is swiped away or killed. */
public class RestartReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        if (c.getSharedPreferences("punch", 0).getBoolean("auto", false) && TrackerService.hasLocation(c)) {
            TrackerService.startFromBackground(c);
            TrackerService.scheduleRestart(c, TrackerService.WATCHDOG_MS);
        }
    }
}
