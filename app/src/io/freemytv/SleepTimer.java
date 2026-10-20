package io.freemytv;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

/**
 * Sleep timer: the home screen's "Sleep timer" shortcut sets it, and two alarms (one minute before, and at the end)
 * wake SleepReceiver in the main process, which warns and then puts the TV to sleep. The deadline (wall clock) is in
 * Prefs, so the :home process can show the time left; the alarms keep it running when the home screen isn't on top.
 * It doesn't survive a reboot.
 */
final class SleepTimer {
    private SleepTimer() {}

    static final String STAGE = "stage", AT = "at", WARN = "warn", SLEEP = "sleep";
    static final long WARN_MS = 60_000L, LATE_MS = 2 * 60_000L;
    /** Minutes the shortcut cycles through; 0 = off. */
    private static final int[] PRESETS = {0, 15, 30, 45, 60, 90, 120};
    private static final int CODE_WARN = 1, CODE_SLEEP = 2;

    static boolean active() {
        return remaining() > 0;
    }

    /** Milliseconds left; 0 without a timer (or after it ended). */
    static long remaining() {
        long at = Prefs.sleepAt();
        return at == 0 ? 0 : Math.max(0, at - System.currentTimeMillis());
    }

    /** Home screen started or came back: a deadline already in the past is dropped, never acted on. */
    static void dropStale(Context c) {
        long at = Prefs.sleepAt();
        if (at != 0 && at <= System.currentTimeMillis()) cancel(c);
    }

    /** Next preset after the current one (Off after the last); returns the minutes now set, 0 = off. */
    static int next(Context c) {
        int current = active() ? Prefs.sleepMinutes() : 0, index = 0;
        for (int i = 0; i < PRESETS.length; i++) if (PRESETS[i] == current) index = i;
        int minutes = PRESETS[(index + 1) % PRESETS.length];
        if (minutes == 0) cancel(c);
        else set(c, minutes);
        return minutes;
    }

    /** Starts the timer for that many minutes (the prompt); 0 or less cancels it. */
    static void setMinutes(Context c, int minutes) {
        if (minutes <= 0) cancel(c);
        else set(c, minutes);
    }

    /** Milliseconds until the label changes (it shows whole seconds, rounded up), so the countdown never skips one. */
    static long untilTick() {
        long left = remaining();
        return left - Math.max(0, (left + 999) / 1000 - 1) * 1000 + 5;
    }

    private static void set(Context c, int minutes) {
        long at = System.currentTimeMillis() + minutes * 60_000L;
        Prefs.setSleep(at, minutes);
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        arm(c, am, CODE_WARN, WARN, at - WARN_MS, at);
        arm(c, am, CODE_SLEEP, SLEEP, at, at);
    }

    static void cancel(Context c) {
        Prefs.setSleep(0, 0);
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        am.cancel(pending(c, CODE_WARN, WARN, 0));
        am.cancel(pending(c, CODE_SLEEP, SLEEP, 0));
    }

    private static void arm(Context c, AlarmManager am, int code, String stage, long when, long deadline) {
        PendingIntent pi = pending(c, code, stage, deadline);
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pi);
        } catch (SecurityException e) {
            // Android 12+ without the exact alarms permission: a few minutes late is better than never.
            AppLog.w("sleep timer: exact alarm not allowed, using an inexact one");
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pi);
        }
    }

    private static PendingIntent pending(Context c, int code, String stage, long deadline) {
        Intent i = new Intent(c, SleepReceiver.class).putExtra(STAGE, stage).putExtra(AT, deadline);
        return PendingIntent.getBroadcast(c, code, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** The drawer shortcut's name: idle, or just the time left (mm:ss under an hour, h:mm from there). Built on a tick. */
    static String label() {
        long left = remaining();
        if (left <= 0) return Ui.tr("Timer de sono", "Sleep timer", "Temporizador de sueño");
        long s = (left + 999) / 1000;
        StringBuilder t = new StringBuilder(8);
        if (s >= 3600) {
            long m = s % 3600 / 60;
            t.append(s / 3600).append(':').append(m < 10 ? "0" : "").append(m);
        } else {
            long m = s / 60, sec = s % 60;
            t.append(m < 10 ? "0" : "").append(m).append(':').append(sec < 10 ? "0" : "").append(sec);
        }
        return t.toString();
    }
}
