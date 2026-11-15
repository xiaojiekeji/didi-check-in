package com.doubao.autocheckin;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 打卡记录：按天存一条汇总记录，落盘为 records.tsv。
 * 每行格式：yyyy-MM-dd \t 时间戳 \t 状态码 \t 触发来源 \t 说明
 */
public final class Records {

    public static final int SUCCESS = 1;
    public static final int SKIP = 2;
    public static final int FAIL = 3;

    private static final String FILE_NAME = "records.tsv";
    private static final String TAB = "\t";
    private static final int KEEP_DAYS = 120;

    private static final SimpleDateFormat DAY = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA);
    private static final SimpleDateFormat HM = new SimpleDateFormat("HH:mm", Locale.CHINA);

    private Records() {
    }

    public static final class Item {
        public String day;
        public long at;
        public int status;
        public String source;
        public String msg;

        public String statusText() {
            switch (status) {
                case SUCCESS:
                    return "成功";
                case SKIP:
                    return "已签到";
                default:
                    return "失败";
            }
        }

        public String timeText() {
            return HM.format(new Date(at));
        }
    }

    // ---------------------------------------------------------------- 写入

    public static synchronized void add(Context c, int status, String msg, String source) {
        long now = System.currentTimeMillis();
        Item it = new Item();
        it.day = DAY.format(new Date(now));
        it.at = now;
        it.status = status;
        it.source = source == null ? "" : source;
        it.msg = msg == null ? "" : msg;
        try {
            File f = new File(c.getFilesDir(), FILE_NAME);
            FileOutputStream fos = new FileOutputStream(f, true);
            try {
                fos.write((line(it) + "\n").getBytes("UTF-8"));
            } finally {
                fos.close();
            }
            Config.setLastResult(c, it.statusText() + " · " + it.msg);
            prune(c);
        } catch (Exception e) {
            Logger.log(c, "写打卡记录失败：" + e);
        }
    }

    private static String line(Item it) {
        return it.day + TAB + it.at + TAB + it.status + TAB
                + clean(it.source) + TAB + clean(it.msg);
    }

    private static String clean(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\t", " ").replace("\n", " ").replace("\r", " ");
    }

    private static void prune(Context c) {
        // 只按天数做上限，简单粗暴地整文件重写一次
        List<Item> all = readAll(c);
        if (all.size() <= KEEP_DAYS * 4) {
            return;
        }
        List<Item> keep = new ArrayList<>();
        for (int i = all.size() - 1; i >= 0 && keep.size() < KEEP_DAYS * 4; i--) {
            keep.add(all.get(i));
        }
        Collections.reverse(keep);
        rewrite(c, keep);
    }

    private static void rewrite(Context c, List<Item> items) {
        try {
            File f = new File(c.getFilesDir(), FILE_NAME);
            FileOutputStream fos = new FileOutputStream(f, false);
            try {
                for (Item it : items) {
                    fos.write((line(it) + "\n").getBytes("UTF-8"));
                }
            } finally {
                fos.close();
            }
        } catch (Exception e) {
            Logger.log(c, "重写打卡记录失败：" + e);
        }
    }

    // ---------------------------------------------------------------- 读取

    public static synchronized List<Item> readAll(Context c) {
        List<Item> out = new ArrayList<>();
        File f = new File(c.getFilesDir(), FILE_NAME);
        if (!f.exists()) {
            return out;
        }
        try {
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(new FileInputStream(f), "UTF-8"));
            try {
                String l;
                while ((l = r.readLine()) != null) {
                    Item it = parse(l);
                    if (it != null) {
                        out.add(it);
                    }
                }
            } finally {
                r.close();
            }
        } catch (Exception e) {
            Logger.log(c, "读打卡记录失败：" + e);
        }
        return out;
    }

    private static Item parse(String l) {
        if (l == null || l.trim().isEmpty()) {
            return null;
        }
        String[] p = l.split(TAB, -1);
        if (p.length < 5) {
            return null;
        }
        try {
            Item it = new Item();
            it.day = p[0];
            it.at = Long.parseLong(p[1]);
            it.status = Integer.parseInt(p[2]);
            it.source = p[3];
            it.msg = p[4];
            return it;
        } catch (Exception e) {
            return null;
        }
    }

    /** 最近 N 天的记录，按时间倒序（新的在前）。 */
    public static List<Item> recent(Context c, int limit) {
        List<Item> all = readAll(c);
        List<Item> out = new ArrayList<>();
        int dayCount = 0;
        String lastDay = null;
        for (int i = all.size() - 1; i >= 0; i--) {
            Item it = all.get(i);
            if (lastDay == null || !lastDay.equals(it.day)) {
                dayCount++;
                lastDay = it.day;
                if (dayCount > limit) {
                    break;
                }
            }
            out.add(it);
        }
        return out;
    }

    /** 今天的最终状态：只要有成功就算成功，其次已签到，最后失败；没有任何记录返回 null。 */
    public static Item today(Context c) {
        String today = DAY.format(new Date());
        Item best = null;
        for (Item it : readAll(c)) {
            if (!today.equals(it.day)) {
                continue;
            }
            if (best == null) {
                best = it;
            } else if (rank(it.status) > rank(best.status)) {
                best = it;
            }
        }
        return best;
    }

    private static int rank(int status) {
        if (status == SUCCESS) {
            return 3;
        }
        if (status == SKIP) {
            return 2;
        }
        return 1;
    }

    public static boolean todayDone(Context c) {
        Item it = today(c);
        return it != null && rank(it.status) >= 2;
    }

    public static boolean todayFailedOnly(Context c) {
        Item it = today(c);
        return it != null && it.status == FAIL;
    }

    /** 连续打卡天数（成功或已签到），从今天/昨天往前数。 */
    public static int streak(Context c) {
        List<Item> all = readAll(c);
        int streak = 0;
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        // 今天还没打卡时，从昨天开始算，避免刚起床就把纪录打断
        if (!todayDone(c)) {
            cal.add(Calendar.DAY_OF_YEAR, -1);
        }
        for (int guard = 0; guard < 365; guard++) {
            String day = DAY.format(cal.getTime());
            if (dayBestStatus(all, day) >= 2) {
                streak++;
                cal.add(Calendar.DAY_OF_YEAR, -1);
            } else {
                break;
            }
        }
        return streak;
    }

    private static int dayBestStatus(List<Item> all, String day) {
        int best = 0;
        for (Item it : all) {
            if (day.equals(it.day)) {
                best = Math.max(best, rank(it.status));
            }
        }
        return best;
    }

    public static synchronized void clear(Context c) {
        File f = new File(c.getFilesDir(), FILE_NAME);
        if (f.exists()) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }
}
