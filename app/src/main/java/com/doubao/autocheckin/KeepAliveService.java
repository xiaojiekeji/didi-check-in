package com.doubao.autocheckin;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/**
 * 保活用的常驻前台服务。
 *
 * 作用有三个：
 * 1. 前台服务 + 常驻通知，进程优先级最高档，最不容易被系统在后台回收；
 * 2. 在「最近任务」里被划掉（onTaskRemoved）时自动重启自己，并重新排明天的闹钟；
 * 3. 每次起来都顺手把每日签到的闹钟补齐，避免闹钟被系统清掉后一整天不执行。
 */
public class KeepAliveService extends Service {

    public static final String CHANNEL = "checkin_keepalive";
    public static final int ID = 11;
    public static final String ACTION_RESTART = "com.doubao.autocheckin.ACTION_KEEPALIVE_RESTART";

    public static void start(Context c) {
        try {
            Intent i = new Intent(c, KeepAliveService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                c.startForegroundService(i);
            } else {
                c.startService(i);
            }
        } catch (Throwable t) {
            Logger.log(c, "启动保活服务失败：" + t);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(ID, buildNotification());
        Logger.log(this, "保活服务已启动（前台常驻）");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 每次被拉起都补一次排程，防止闹钟被系统清掉
        CheckinScheduler.scheduleDaily(this);
        return START_STICKY;
    }

    /** 用户在最近任务里把卡片划掉时，安排 5 秒后把自己拉回来。 */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Logger.log(this, "最近任务被划掉，准备自动重启");
        scheduleRestart(this);
        CheckinScheduler.scheduleDaily(this);
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    static void scheduleRestart(Context c) {
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(ALARM_SERVICE);
            if (am == null) {
                return;
            }
            Intent i = new Intent(c, KeepAliveReceiver.class);
            i.setAction(ACTION_RESTART);
            PendingIntent pi = PendingIntent.getBroadcast(c, 1101, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + 5000L, pi);
        } catch (Throwable t) {
            Logger.log(c, "安排保活重启失败：" + t);
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null || nm.getNotificationChannel(CHANNEL) != null) {
            return;
        }
        NotificationChannel ch = new NotificationChannel(CHANNEL, "自动签到保活",
                NotificationManager.IMPORTANCE_MIN);
        ch.setDescription("保持每日自动签到在后台可用，可长期驻留");
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    private Notification buildNotification() {
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
        }
        return b.setContentTitle("自动签到在后台待命")
                .setContentText("每天 7:00~8:00 自动执行，点开可查看记录")
                .setSmallIcon(getApplicationInfo().icon)
                .setOngoing(true)
                .setContentIntent(pi)
                .setPriority(Notification.PRIORITY_MIN)
                .build();
    }
}
