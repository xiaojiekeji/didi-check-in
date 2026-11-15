package com.doubao.autocheckin;

import android.annotation.TargetApi;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** 结果通知与未打卡提醒。 */
public final class Notifier {

    public static final String CHANNEL = "checkin_result";
    public static final String CHANNEL_REMIND = "checkin_remind";
    public static final int ID_SUCCESS = 2;
    public static final int ID_SKIP = 3;
    public static final int ID_FAIL = 4;
    public static final int ID_REMIND = 5;

    private Notifier() {
    }

    @TargetApi(26)
    public static void init(Context c) {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        if (nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "签到结果",
                    NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription("每日自动签到执行结果");
            nm.createNotificationChannel(ch);
        }
        if (nm.getNotificationChannel(CHANNEL_REMIND) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_REMIND, "未打卡提醒",
                    NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("今天还没打卡时的提醒");
            ch.enableVibration(true);
            nm.createNotificationChannel(ch);
        }
    }

    public static void notify(Context c, int id, String title, String text) {
        post(c, id, CHANNEL, title, text, true);
    }

    /** 未打卡提醒：高优先级 + 常驻，点开直接进 App 的打卡页。 */
    public static void notifyReminder(Context c, boolean lastWasFail) {
        String text = lastWasFail
                ? "今天自动签到执行失败，还没成功打卡。点开可以手动补一次。"
                : "今天还没打卡。点开可以手动补一次，或者等下一次自动重试。";
        post(c, ID_REMIND, CHANNEL_REMIND, "滴滴充电还没打卡", text, false);
    }

    private static void post(Context c, int id, String channel, String title, String text,
                             boolean autoCancel) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) {
                return;
            }
            init(c);

            Intent i = new Intent(c, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            if (id == ID_REMIND) {
                i.putExtra(MainActivity.EXTRA_FROM_REMIND, true);
            }
            PendingIntent pi = PendingIntent.getActivity(c, id, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= 26) {
                b = new Notification.Builder(c, channel);
            } else {
                b = new Notification.Builder(c);
            }
            b.setSmallIcon(android.R.drawable.ic_popup_reminder)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setAutoCancel(autoCancel)
                    .setContentIntent(pi)
                    .setWhen(System.currentTimeMillis());
            if (!autoCancel) {
                b.setOngoing(false);
                if (Build.VERSION.SDK_INT >= 26) {
                    b.setPriority(Notification.PRIORITY_HIGH);
                } else {
                    b.setPriority(Notification.PRIORITY_HIGH);
                }
            }
            nm.notify(id, b.build());
        } catch (Exception ignored) {
        }
    }
}
