package com.doubao.autocheckin;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 极简本地日志：按天写文件，保留 30 天。 */
public final class Logger {

    private static final String TAG = "DidiCheckin";
    private static final SimpleDateFormat TS = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA);
    private static final SimpleDateFormat DAY = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA);
    private static final int KEEP_DAYS = 30;

    private Logger() {
    }

    public static synchronized void log(Context c, String msg) {
        String line = TS.format(new Date()) + "  " + msg + "\n";
        try {
            File dir = new File(c.getFilesDir(), "logs");
            if (!dir.exists() && !dir.mkdirs()) {
                return;
            }
            File f = new File(dir, "checkin-" + DAY.format(new Date()) + ".log");
            FileOutputStream fos = new FileOutputStream(f, true);
            try {
                fos.write(line.getBytes("UTF-8"));
            } finally {
                fos.close();
            }
            prune(dir);
        } catch (Exception e) {
            Log.w(TAG, "write log failed", e);
        }
        Log.i(TAG, msg);
    }

    private static void prune(File dir) {
        File[] files = dir.listFiles();
        if (files == null || files.length <= KEEP_DAYS) {
            return;
        }
        Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (int i = 0; i < files.length - KEEP_DAYS; i++) {
            //noinspection ResultOfMethodCallIgnored
            files[i].delete();
        }
    }

    public static String tail(Context c, int maxLines) {
        List<String> lines = new ArrayList<>();
        try {
            File dir = new File(c.getFilesDir(), "logs");
            File[] files = dir.listFiles();
            if (files == null) {
                return "（暂无日志）";
            }
            Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
            for (int i = files.length - 1; i >= 0 && lines.size() < maxLines; i--) {
                List<String> one = readLines(files[i]);
                for (int j = one.size() - 1; j >= 0 && lines.size() < maxLines; j--) {
                    lines.add(0, one.get(j));
                }
            }
        } catch (Exception e) {
            return "读取日志失败：" + e;
        }
        if (lines.isEmpty()) {
            return "（暂无日志）";
        }
        StringBuilder sb = new StringBuilder();
        for (String l : lines) {
            sb.append(l).append('\n');
        }
        return sb.toString();
    }

    private static List<String> readLines(File f) throws Exception {
        List<String> out = new ArrayList<>();
        java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"));
        try {
            String l;
            while ((l = r.readLine()) != null) {
                out.add(l);
            }
        } finally {
            r.close();
        }
        return out;
    }

    public static void clear(Context c) {
        File dir = new File(c.getFilesDir(), "logs");
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }
}
