package io.freemytv;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

import java.io.IOException;

/**
 * Keeps LampaBridge running (a foreground service, in the :home process next to the home screen that shows the data)
 * while Free My TV mode is on and Lampa is installed. Original mode stops it: nothing stays running.
 */
public class LampaService extends Service {
    private static final String CHANNEL = "lampa";
    private static final int NOTIFICATION = 38777;

    private LampaBridge bridge;

    /** Free My TV mode is on and Lampa is on the TV. */
    static boolean wanted(Context c) {
        return HomeMode.FREE.equals(HomeMode.state(c).optString("mode")) && LampaLists.available(c);
    }

    static void startIfWanted(Context c) {
        if (wanted(c)) start(c);
    }

    static void start(Context c) {
        try {
            c.startForegroundService(new Intent(c, LampaService.class));
        } catch (RuntimeException e) {
            AppLog.w("Lampa: could not start the service: " + e.getMessage());
        }
    }

    static void stop(Context c) {
        try {
            c.stopService(new Intent(c, LampaService.class));
        } catch (RuntimeException e) {
            AppLog.w("Lampa: could not stop the service: " + e.getMessage());
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            startForeground(NOTIFICATION, notification());
        } catch (RuntimeException e) {
            AppLog.w("Lampa: could not go foreground: " + e.getMessage());
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!wanted(this)) {   // restarted by the system after Original mode, or Lampa was removed
            stopSelf();
            return START_NOT_STICKY;
        }
        LampaLists.load(this);
        if (bridge == null) {
            LampaBridge b = new LampaBridge(this);
            try {
                b.start();
                bridge = b;
            } catch (IOException e) {
                AppLog.w("Lampa: could not listen on port " + LampaBridge.PORT + ": " + e.getMessage());
                stopSelf();
                return START_NOT_STICKY;
            }
        }
        return START_STICKY;
    }

    @Override public void onDestroy() {
        if (bridge != null) bridge.stop();
        bridge = null;
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification notification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL,
                    Ui.tr("Listas do Lampa", "Lampa lists", "Listas de Lampa"), NotificationManager.IMPORTANCE_LOW));
        }
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.icon)
                .setContentTitle(Ui.tr("Free My TV: listas do Lampa", "Free My TV: Lampa lists", "Free My TV: listas de Lampa"))
                .setOngoing(true)
                .build();
    }
}
