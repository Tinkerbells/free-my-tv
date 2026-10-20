package io.freemytv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

import java.io.IOException;

/** Sleep timer alarms, in the main process (where the ADB client runs): the one-minute warning, then the sleep. */
public class SleepReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        final Context app = context.getApplicationContext();
        String stage = intent.getStringExtra(SleepTimer.STAGE);
        long late = System.currentTimeMillis() - intent.getLongExtra(SleepTimer.AT, 0);
        if (late > SleepTimer.LATE_MS) return;   // delivered long after the deadline: the TV isn't where it was
        if (SleepTimer.WARN.equals(stage)) {
            Toast.makeText(app, Ui.tr("A TV desliga em 1 minuto", "TV turns off in 1 minute",
                    "El TV se apaga en 1 minuto"), Toast.LENGTH_LONG).show();
        } else if (SleepTimer.SLEEP.equals(stage)) {
            final PendingResult result = goAsync();
            new Thread(() -> {
                try (Adb adb = Adb.connect(app, 10_000, null)) {
                    adb.shell("input keyevent 223");   // KEYCODE_SLEEP
                    AppLog.w("sleep timer: TV put to sleep");
                } catch (IOException e) {
                    AppLog.w("sleep timer: could not put the TV to sleep: " + e.getMessage());
                } finally {
                    result.finish();
                }
            }, "sleep-timer").start();
        }
    }
}
