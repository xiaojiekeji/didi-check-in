package com.doubao.autocheckin;

import android.content.Context;
import android.content.SharedPreferences;

/** 全部配置存放在 SharedPreferences，卸载即清空。 */
public final class Config {

    private static final String SP = "checkin_cfg";
    public static final int DEF_HOUR = 8;
    public static final int DEF_MINUTE = 30;
    public static final String DEF_PACKAGE = "com.sdu.didi.psnger";

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

    public static void setTargetPackage(Context c, String pkg) {
        sp(c).edit().putString("pkg", pkg == null ? DEF_PACKAGE : pkg.trim()).apply();
    }

    public static boolean isEnabled(Context c) {
        return sp(c).getBoolean("enabled", true);
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
}
