package com.punch.attendance;

import android.content.*;

public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        AlarmReceiver.restore(c);
        if (c.getSharedPreferences("punch", 0).getBoolean("auto", false)) TrackerService.startFromBackground(c);
    }
}
