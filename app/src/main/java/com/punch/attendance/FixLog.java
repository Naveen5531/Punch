package com.punch.attendance;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;
import java.io.*;
import java.util.ArrayList;
import java.util.List;

/** Every location reading is written here with its real time, by the tracking service, whether or not the
 *  page is running. When the page wakes up (app opened, or the phone let it run again) it replays the readings
 *  it missed, so trips and check-in / check-out get the true times instead of the time the app was opened.
 *  It also records when tracking was stopped by the phone (gaps between service heartbeats). */
final class FixLog {
    private static final String FILE = "fixes.csv";
    private static final int KEEP = 4000;
    private static final long GAP_MS = 20 * 60_000L;
    private static long lastT;
    private static int adds;

    private FixLog() {}

    static synchronized void add(Context c, Location l) {
        long t = l.getTime();
        if (t <= 0) t = System.currentTimeMillis();
        if (t > System.currentTimeMillis() + 60_000) t = System.currentTimeMillis();
        if (t <= lastT) return;                      // passive provider repeats our own fixes
        lastT = t;
        String line = t + "," + l.getLatitude() + "," + l.getLongitude() + ","
                + (l.hasAccuracy() ? l.getAccuracy() : 50) + "," + (l.getProvider() == null ? "" : l.getProvider()) + "\n";
        File f = new File(c.getFilesDir(), FILE);
        try (FileWriter w = new FileWriter(f, true)) { w.write(line); } catch (IOException ignored) {}
        c.getSharedPreferences("punch", 0).edit().putLong("last_fix", t).apply();
        if (++adds % 200 == 0) trim(f);
    }

    private static void trim(File f) {
        List<String> all = read(f);
        if (all.size() <= KEEP) return;
        List<String> keep = all.subList(all.size() - KEEP / 2, all.size());
        try (FileWriter w = new FileWriter(f, false)) { for (String s : keep) w.write(s + "\n"); } catch (IOException ignored) {}
    }

    private static List<String> read(File f) {
        List<String> out = new ArrayList<>();
        if (!f.exists()) return out;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String s; while ((s = r.readLine()) != null) if (!s.isEmpty()) out.add(s);
        } catch (IOException ignored) {}
        return out;
    }

    /** JSON array [[t,lat,lng,acc,"provider"],...] of readings newer than t (at most the last 3000). */
    static synchronized String since(Context c, long t) {
        StringBuilder b = new StringBuilder("[");
        List<String> all = read(new File(c.getFilesDir(), FILE));
        int from = Math.max(0, all.size() - 3000), n = 0;
        for (int i = from; i < all.size(); i++) {
            String[] p = all.get(i).split(",", -1);
            if (p.length < 5) continue;
            try {
                if (Long.parseLong(p[0]) <= t) continue;
                if (n++ > 0) b.append(',');
                b.append('[').append(p[0]).append(',').append(p[1]).append(',').append(p[2]).append(',').append(p[3])
                        .append(",\"").append(p[4].replaceAll("[^a-z]", "")).append("\"]");
            } catch (NumberFormatException ignored) {}
        }
        return b.append(']').toString();
    }

    /** Called every minute by the service and when it starts: a long silence means the phone stopped Punch. */
    static void beat(Context c) {
        SharedPreferences p = c.getSharedPreferences("punch", 0);
        long now = System.currentTimeMillis(), last = p.getLong("hb_last", 0);
        SharedPreferences.Editor e = p.edit().putLong("hb_last", now);
        if (last > 0 && now - last > GAP_MS && p.getBoolean("auto", false)) {
            String g = p.getString("gaps", "");
            g = (g.isEmpty() ? "" : g + ";") + last + "-" + now;
            String[] parts = g.split(";");
            if (parts.length > 20) g = g.substring(g.indexOf(';') + 1);
            e.putString("gaps", g);
        }
        e.apply();
    }

    /** JSON [[from,to],...] of times when tracking was not running. */
    static String gaps(Context c) {
        String g = c.getSharedPreferences("punch", 0).getString("gaps", "");
        StringBuilder b = new StringBuilder("[");
        int n = 0;
        for (String s : g.split(";")) {
            String[] p = s.split("-");
            if (p.length != 2) continue;
            if (n++ > 0) b.append(',');
            b.append('[').append(p[0]).append(',').append(p[1]).append(']');
        }
        return b.append(']').toString();
    }

    static void clearGaps(Context c) { c.getSharedPreferences("punch", 0).edit().remove("gaps").apply(); }
}
