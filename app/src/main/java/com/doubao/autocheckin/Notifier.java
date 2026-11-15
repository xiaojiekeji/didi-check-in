package com.doubao.autocheckin;

import android.annotation.TargetApi;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** 结果通知。 */
public final class Notifier {

    public static final String CHANNEL = "checkin_result";
    public static final int ID_SUCCESS = 2;
    public static final int ID_SKIP = 3;
    public static final int ID_FAIL = 4;

    private Notifier() {
    }

    @TargetApi(26)
    public static void init(Context c) {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null || nm.getNotificationChannel(CHANNEL) != null) {
            return;
        }
        NotificationChannel ch = new NotificationChannel(CHANNEL, "签到结果", NotificationManager.IMPORTANCE_DEFAULT);
        ch.setDescription("每日自动签到执行结果");
        nm.createNotificationChannel(ch);
    }

    public static void notify(Context c, int id, String title, String text) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) {
                return;
            }
            init(c);

            Intent i = new Intent(c, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(c, 0, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= 26) {
                b = new Notification.Builder(c, CHANNEL);
            } else {
                b = new Notification.Builder(c);
            }
            b.setSmallIcon(android.R.drawable.ic_popup_reminder)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setAutoCancel(true)
                    .setContentIntent(pi)
                    .setWhen(System.currentTimeMillis());
            nm.notify(id, b.build());
        } catch (Exception ignored) {
        }
    }
}
