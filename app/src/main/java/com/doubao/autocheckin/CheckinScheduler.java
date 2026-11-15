package com.doubao.autocheckin;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.util.Calendar;
import java.util.Random;

/** 定时排程：每天一次，带上随机偏移；失败时排一次重试。 */
public final class CheckinScheduler {

    public static final String ACTION_ALARM = "com.doubao.autocheckin.ACTION_ALARM";
    private static final Random RND = new Random();

    private CheckinScheduler() {
    }

    private static PendingIntent pending(Context c) {
        Intent i = new Intent(c, AlarmReceiver.class);
        i.setAction(ACTION_ALARM);
        return PendingIntent.getBroadcast(c, 1001, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** 下一次触发时间：今天的目标时刻 + 0~30 分钟随机偏移，已过则顺延到明天。 */
    public static long nextDailyMillis(Context c) {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, Config.hour(c));
        cal.set(Calendar.MINUTE, Config.minute(c));
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        cal.add(Calendar.MINUTE, RND.nextInt(31));
        if (cal.getTimeInMillis() < System.currentTimeMillis() + 60_000L) {
            cal.add(Calendar.DAY_OF_YEAR, 1);
            cal.add(Calendar.MINUTE, RND.nextInt(31));
        }
        return cal.getTimeInMillis();
    }

    public static void scheduleDaily(Context c) {
        if (!Config.isEnabled(c)) {
            cancel(c);
            Config.setNextTrigger(c, 0L);
            return;
        }
        scheduleAt(c, nextDailyMillis(c));
    }

    public static void scheduleRetry(Context c, long minutesLater) {
        scheduleAt(c, System.currentTimeMillis() + minutesLater * 60_000L);
    }

    public static void scheduleAt(Context c, long triggerAtMillis) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return;
        }
        PendingIntent pi = pending(c);
        am.cancel(pi);
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                if (am.canScheduleExactAlarms()) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi);
                } else {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi);
                    Logger.log(c, "无精确闹钟权限，已降级为普通闹钟（可能延迟几分钟）");
                }
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi);
            }
            Config.setNextTrigger(c, triggerAtMillis);
        } catch (Exception e) {
            Logger.log(c, "排程失败：" + e);
        }
    }

    public static void cancel(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am != null) {
            am.cancel(pending(c));
        }
    }
}
