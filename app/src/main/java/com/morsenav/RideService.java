package com.morsenav;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

/**
 * Foreground service started by "Start ride". While it runs, turn cues are vibrated and the
 * process is kept at foreground priority so it survives with the phone locked in a pocket.
 */
public class RideService extends Service {

    private static final String ACTION_STOP = "com.morsenav.action.STOP_RIDE";
    private static final String CHANNEL_ID = "ride";
    private static final int NOTIFICATION_ID = 1;

    private static volatile boolean running;

    static boolean isRunning() {
        return running;
    }

    static void start(Context context) {
        ContextCompat.startForegroundService(context, new Intent(context, RideService.class));
    }

    static void stop(Context context) {
        context.stopService(new Intent(context, RideService.class));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }

        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } catch (RuntimeException e) {
            // A sticky restart from the background may be refused; the rider must press Start again.
            DebugLog.add("Could not enter foreground: " + e);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!running) {
            running = true;
            NavEngine.get(this).startRide();
            DebugLog.add("Ride started");
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        NavEngine.get(this).stopRide();
        DebugLog.add("Ride stopped");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification buildNotification() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(
                CHANNEL_ID, getString(R.string.ride_channel), NotificationManager.IMPORTANCE_LOW));

        PendingIntent openApp = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stopRide = PendingIntent.getService(this, 1,
                new Intent(this, RideService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.ride_active))
                .setContentText(getString(R.string.ride_listening))
                .setSmallIcon(R.drawable.ic_stat_ride)
                .setOngoing(true)
                .setContentIntent(openApp)
                .addAction(0, getString(R.string.stop_ride), stopRide)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build();
    }
}
