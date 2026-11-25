package com.pocketvpnbridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import java.io.IOException;
import java.util.Locale;

public class ProxyService extends Service {
    static final String ACTION_START = "com.pocketvpnbridge.action.START";
    static final String ACTION_STOP = "com.pocketvpnbridge.action.STOP";
    static final String ACTION_STATE = "com.pocketvpnbridge.action.STATE";
    static final String EXTRA_PORT = "port";
    static final String EXTRA_USERNAME = "username";
    static final String EXTRA_PASSWORD = "password";
    static final String EXTRA_KEEP_AWAKE = "keep_awake";
    static final String EXTRA_RUNNING = "running";
    static final String EXTRA_MESSAGE = "message";
    static final String EXTRA_CONNECTIONS = "connections";

    private static final String CHANNEL_ID = "pocket_proxy_status";
    private static final int NOTIFICATION_ID = 701;
    private static final String PREFS = "bridge_settings";
    private static final String PREF_RUNNING = "service_running";

    private Socks5Server proxy;
    private PowerManager.WakeLock wakeLock;
    private int port = 1080;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(intent.getAction())) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (proxy != null && proxy.isRunning()) return START_NOT_STICKY;

        port = intent.getIntExtra(EXTRA_PORT, 1080);
        String username = intent.getStringExtra(EXTRA_USERNAME);
        String password = intent.getStringExtra(EXTRA_PASSWORD);
        boolean keepAwake = intent.getBooleanExtra(EXTRA_KEEP_AWAKE, false);

        try {
            startAsForeground();
            proxy = new Socks5Server();
            proxy.start(port, username == null ? "android" : username,
                    password == null ? "" : password,
                    stats -> onProxyStats(stats));
            port = proxy.getPort();
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(PREF_RUNNING, true).apply();
            if (keepAwake) acquireWakeLock();
            sendState(true, "Listening on port " + port, 0);
            updateNotification(0);
        } catch (IOException | RuntimeException error) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(PREF_RUNNING, false).apply();
            sendState(false, "Could not start proxy: " + safeMessage(error), 0);
            stopSelf(startId);
        }
        return START_NOT_STICKY;
    }

    private void startAsForeground() {
        Notification notification = buildNotification(0);
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void onProxyStats(Socks5Server.Stats stats) {
        updateNotification(stats.activeConnections);
        sendState(true, "Listening on port " + port, stats.activeConnections);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    "VPN bridge status", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Shows when the local SOCKS5 proxy is running");
            channel.setShowBadge(false);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(int connections) {
        Intent openApp = new Intent(this, MainActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 2, openApp,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stopIntent = new Intent(this, ProxyService.class).setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(this, 3, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder.setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setContentTitle("Pocket VPN Bridge is active")
                .setContentText("SOCKS5 on port " + port + " · " + connections + " active")
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(android.R.drawable.ic_media_pause, "Stop", stopPending)
                .build();
    }

    private void updateNotification(int connections) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null && proxy != null && proxy.isRunning()) {
            manager.notify(NOTIFICATION_ID, buildNotification(connections));
        }
    }

    private void acquireWakeLock() {
        try {
            PowerManager power = getSystemService(PowerManager.class);
            if (power == null) return;
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                    "PocketVpnBridge:ProxyRunning");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
        } catch (RuntimeException ignored) {
            wakeLock = null;
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null) {
            try {
                if (wakeLock.isHeld()) wakeLock.release();
            } catch (RuntimeException ignored) {}
            wakeLock = null;
        }
    }

    private void sendState(boolean running, String message, int active) {
        Intent state = new Intent(ACTION_STATE).setPackage(getPackageName());
        state.putExtra(EXTRA_RUNNING, running);
        state.putExtra(EXTRA_MESSAGE, message);
        state.putExtra(EXTRA_CONNECTIONS, active);
        sendBroadcast(state);
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null ? error.getClass().getSimpleName()
                : message.toLowerCase(Locale.ROOT).contains("address already in use")
                ? "Port is already in use" : message;
    }

    @Override
    public void onDestroy() {
        Socks5Server oldProxy = proxy;
        proxy = null;
        if (oldProxy != null) oldProxy.stop();
        releaseWakeLock();
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(PREF_RUNNING, false).apply();
        sendState(false, "Proxy stopped", 0);
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
        else stopForeground(true);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
