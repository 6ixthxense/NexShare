package com.nexshare;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import java.net.Inet4Address;

/** Foreground service that keeps the web server and mDNS responder alive while sharing. */
public class ServerService extends Service {

    public static final int PORT = 8080;
    public static final String ACTION_STOP = "com.nexshare.STOP";
    private static final String CHANNEL = "nexshare";

    public static volatile boolean running;
    public static volatile String lastError;

    private final HttpServer[] server = new HttpServer[1];
    private MdnsResponder mdns;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private NsdManager nsd;
    private NsdManager.RegistrationListener nsdListener;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        goForeground();
        if (server[0] == null) startServer();
        return START_STICKY;
    }

    private void goForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "NexShare server", NotificationManager.IMPORTANCE_LOW));
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
        }
        int piFlags = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), piFlags);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, ServerService.class).setAction(ACTION_STOP), piFlags);
        Inet4Address ip = MdnsResponder.lanAddress();
        b.setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("NexShare กำลังแชร์ไฟล์")
            .setContentText("http://" + MdnsResponder.HOST + ":" + PORT + (ip != null ? "  •  " + ip.getHostAddress() + ":" + PORT : ""))
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(new Notification.Action.Builder(null, "หยุด", stop).build());
        Notification n = b.build();
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(1, n);
    }

    private void startServer() {
        try {
            HttpServer s = new HttpServer(new WebApi(this, server));
            server[0] = s;
            s.start(PORT);
            mdns = new MdnsResponder(this);
            mdns.start();
            registerNsd();
            DownloadManager.get(this).startup();
            applyStayOn();
            TunnelManager.get(this).restoreIfEnabled();
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nexshare:server");
            wakeLock.acquire();
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "nexshare:wifi");
                wifiLock.acquire();
            }
            running = true;
            lastError = null;
        } catch (Exception e) {
            lastError = e.getMessage();
            running = false;
            server[0] = null;
            stopSelf();
        }
    }

    /** Root edition: keep the physical screen on across the whole device while sharing. */
    private void applyStayOn() {
        if (!Flavor.ROOT) return;
        final boolean on = Settings.keepScreenOn(this);
        new Thread(new Runnable() {
            public void run() {
                try {
                    if (RootShell.available()) RootShell.main().exec("svc power stayon " + (on ? "true" : "false"), 8000);
                } catch (Exception ignored) { }
            }
        }).start();
    }

    /** Also advertise as a DNS-SD web service so discovery apps list it. */
    private void registerNsd() {
        try {
            nsd = (NsdManager) getSystemService(Context.NSD_SERVICE);
            NsdServiceInfo info = new NsdServiceInfo();
            info.setServiceName("NexShare");
            info.setServiceType("_http._tcp");
            info.setPort(PORT);
            nsdListener = new NsdManager.RegistrationListener() {
                public void onRegistrationFailed(NsdServiceInfo s, int e) { }
                public void onUnregistrationFailed(NsdServiceInfo s, int e) { }
                public void onServiceRegistered(NsdServiceInfo s) { }
                public void onServiceUnregistered(NsdServiceInfo s) { }
            };
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, nsdListener);
        } catch (Exception ignored) {
            nsdListener = null;
        }
    }

    @Override
    public void onDestroy() {
        running = false;
        if (server[0] != null) server[0].stop();
        server[0] = null;
        if (mdns != null) mdns.stop();
        TunnelManager.get(this).stop();
        DownloadManager.get(this).shutdown();
        if (Flavor.ROOT) new Thread(new Runnable() {
            public void run() {
                try {
                    if (RootShell.available()) RootShell.main().exec("svc power stayon false", 6000);
                } catch (Exception ignored) { }
            }
        }).start();
        try {
            if (nsd != null && nsdListener != null) nsd.unregisterService(nsdListener);
        } catch (Exception ignored) { }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        super.onDestroy();
    }
}
