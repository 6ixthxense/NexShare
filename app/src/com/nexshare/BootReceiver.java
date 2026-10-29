package com.nexshare;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * Starts the file sharing service automatically after the phone reboots or the app updates,
 * when the user enabled "เปิดแชร์อัตโนมัติเมื่อเปิดเครื่อง" (startOnBoot).
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }
        if (!Settings.startOnBoot(context)) return;
        Intent svc = new Intent(context, ServerService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(svc);
            } else {
                context.startService(svc);
            }
        } catch (Exception ignored) { }
    }
}

