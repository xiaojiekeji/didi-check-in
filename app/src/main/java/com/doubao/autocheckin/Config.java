package com.doubao.autocheckin;

import android.content.Context;
import android.content.SharedPreferences;

/** 全部配置存放在 SharedPreferences，卸载即清空。 */
public final class Config {

    private static final String SP = "checkin_cfg";
    /** 每日执行时刻：7:30 + 0~30 分钟随机偏移，实际落在 7:00~8:00 之间。 */
    public static final int DEF_HOUR = 7;
    public static final int DEF_MINUTE = 0;
    public static final String DEF_PACKAGE = "com.sdu.didi.psnger";
    /** 锁屏解锁密码（原样输入，仅存本机）。 */
    public static final String DEF_PIN = "688888";

    private Config() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(SP, Context.MODE_PRIVATE);
    }

    public static int hour(Context c) {
        return sp(c).getInt("hour", DEF_HOUR);
    }

    public static int minute(Context c) {
        return sp(c).getInt("minute", DEF_MINUTE);
    }

    public static void setTime(Context c, int hour, int minute) {
        sp(c).edit().putInt("hour", hour).putInt("minute", minute).apply();
    }

    public static String targetPackage(Context c) {
        String v = sp(c).getString("pkg", DEF_PACKAGE);
        return (v == null || v.trim().isEmpty()) ? DEF_PACKAGE : v.trim();
    }

    /** 锁屏密码：用于锁屏时自动输入（只在有密码锁的机器上生效）。 */
    public static String pin(Context c) {
        String v = sp(c).getString("pin", DEF_PIN);
        return v == null ? DEF_PIN : v;
    }

    public static void setPin(Context c, String pin) {
        sp(c).edit().putString("pin", pin == null ? "" : pin).apply();
    }

    public static void setTargetPackage(Context c, String pkg) {
        sp(c).edit().putString("pkg", pkg == null ? DEF_PACKAGE : pkg.trim()).apply();
    }

    public static boolean isEnabled(Context c) {
        return sp(c).getBoolean("enabled", true);
    }

    /** 是否在「最近任务」里隐藏本应用（默认隐藏，避免一键清理把卡片划掉）。 */
    public static boolean hideFromRecents(Context c) {
        return sp(c).getBoolean("hideRecents", true);
    }

    public static void setHideFromRecents(Context c, boolean hide) {
        sp(c).edit().putBoolean("hideRecents", hide).apply();
    }

    public static void setEnabled(Context c, boolean enabled) {
        sp(c).edit().putBoolean("enabled", enabled).apply();
    }

    public static int failCount(Context c) {
        return sp(c).getInt("fail", 0);
    }

    public static int incFail(Context c) {
        int f = failCount(c) + 1;
        sp(c).edit().putInt("fail", f).apply();
        return f;
    }

    public static void resetFail(Context c) {
        sp(c).edit().putInt("fail", 0).apply();
    }

    public static String lastResult(Context c) {
        return sp(c).getString("lastResult", "暂无记录");
    }

    public static void setLastResult(Context c, String msg) {
        sp(c).edit().putString("lastResult", msg)
                .putLong("lastRunAt", System.currentTimeMillis()).apply();
    }

    public static long lastRunAt(Context c) {
        return sp(c).getLong("lastRunAt", 0L);
    }

    public static long nextTrigger(Context c) {
        return sp(c).getLong("nextTrigger", 0L);
    }

    public static void setNextTrigger(Context c, long at) {
        sp(c).edit().putLong("nextTrigger", at).apply();
    }

    public static String lastShot(Context c) {
        return sp(c).getString("lastShot", "");
    }

    public static void setLastShot(Context c, String path) {
        sp(c).edit().putString("lastShot", path).apply();
    }

    // ---------------------------------------------------------------- 未打卡提醒

    public static final int DEF_REMIND_HOUR = 21;
    public static final int DEF_REMIND_MINUTE = 0;

    public static int remindHour(Context c) {
        return sp(c).getInt("remindHour", DEF_REMIND_HOUR);
    }

    public static int remindMinute(Context c) {
        return sp(c).getInt("remindMinute", DEF_REMIND_MINUTE);
    }

    public static void setRemindTime(Context c, int hour, int minute) {
        sp(c).edit().putInt("remindHour", hour).putInt("remindMinute", minute).apply();
    }

    public static boolean isRemindEnabled(Context c) {
        return sp(c).getBoolean("remindEnabled", true);
    }

    public static void setRemindEnabled(Context c, boolean enabled) {
        sp(c).edit().putBoolean("remindEnabled", enabled).apply();
    }

    /** 今天是否已经弹过「未打卡」提醒，避免反复打扰。 */
    public static String lastPromptDay(Context c) {
        return sp(c).getString("lastPromptDay", "");
    }

    public static void setLastPromptDay(Context c, String day) {
        sp(c).edit().putString("lastPromptDay", day).apply();
    }

    public static long nextRemind(Context c) {
        return sp(c).getLong("nextRemind", 0L);
    }

    public static void setNextRemind(Context c, long at) {
        sp(c).edit().putLong("nextRemind", at).apply();
    }
}
