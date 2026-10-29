package com.nexshare;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * UI helper module for MainActivity: card containers, copyable URL links, buttons,
 * confirmation dialogs, and status summaries.
 */
final class MainUi {

    static final int BLUE = 0xFF1E6FD9;

    private MainUi() { }

    static int dp(Context ctx, int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }

    static TextView text(Context ctx, String s, int sp, int color, boolean bold) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setGravity(Gravity.START);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    static LinearLayout card(Context ctx, LinearLayout parent) {
        LinearLayout c = new LinearLayout(ctx);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(ctx, 18), dp(ctx, 16), dp(ctx, 18), dp(ctx, 16));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(ctx, 14));
        bg.setColor(Color.WHITE);
        c.setBackground(bg);
        c.setElevation(dp(ctx, 1));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(ctx, 14);
        parent.addView(c, lp);
        return c;
    }

    static TextView link(final Context ctx, LinearLayout parent) {
        final TextView t = text(ctx, "", 17, BLUE, true);
        t.setPadding(0, dp(ctx, 10), 0, 0);
        t.setOnClickListener(v -> {
            Object url = v.getTag();
            if (url == null) return;
            ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("NexShare", url.toString()));
                Toast.makeText(ctx, "คัดลอกลิงก์แล้ว: " + url, Toast.LENGTH_SHORT).show();
            }
        });
        parent.addView(t);
        return t;
    }

    static Button button(Context ctx, LinearLayout parent, String label, final Runnable run) {
        Button b = new Button(ctx);
        b.setAllCaps(false);
        b.setText(label);
        b.setOnClickListener(v -> run.run());
        parent.addView(b);
        return b;
    }

    static void confirm(final Activity act, String title, String message, final Runnable action, final Runnable after) {
        new AlertDialog.Builder(act).setTitle(title).setMessage(message)
            .setPositiveButton("ยืนยัน", (d, w) -> {
                action.run();
                if (after != null) after.run();
                Toast.makeText(act, "เรียบร้อย", Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("ยกเลิก", null).show();
    }

    static String buildSecuritySummary(Context ctx) {
        boolean hasPw = Settings.hasAdminPassword(ctx);
        String totp = Settings.totpMode(ctx);
        StringBuilder sec = new StringBuilder();
        sec.append(hasPw ? "✓ ตั้งรหัสผ่านผู้ดูแลแล้ว" : "• ผู้ดูแลใช้ PIN 6 หลัก (ตั้งรหัสผ่านได้ในแผงควบคุมบนเว็บ)");
        sec.append('\n').append(Settings.TOTP_OFF.equals(totp) ? "• ยังไม่เปิด 2FA"
            : "✓ เปิด 2FA " + (Settings.TOTP_ALWAYS.equals(totp) ? "ทุกการเข้าสู่ระบบ" : "เมื่อเข้าผ่านอินเทอร์เน็ต"));
        sec.append('\n').append(ServerService.httpsRunning ? "✓ HTTPS พอร์ต " + ServerService.HTTPS_PORT
            : Settings.httpsEnabled(ctx) ? "• HTTPS กำลังเริ่ม" + (ServerService.httpsError != null ? " (ผิดพลาด: " + ServerService.httpsError + ")" : "") : "• ปิด HTTPS");
        sec.append('\n').append("เข้าสู่ระบบค้างไว้ ").append(SessionStore.get(ctx).count()).append(" เครื่อง");
        return sec.toString();
    }
}

