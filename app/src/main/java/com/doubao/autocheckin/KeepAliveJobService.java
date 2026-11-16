package com.doubao.autocheckin;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

/**
 * 15 分钟一次的心跳任务：负责把前台保活服务和每日闹钟都补回来。
 * 系统会周期性调度这个任务，即使进程被清掉也会被重新拉起。
 */
public class KeepAliveJobService extends JobService {

    private static final int JOB_ID = 1201;

    public static void schedule(Context c) {
        try {
            JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) {
                return;
            }
            if (js.getPendingJob(JOB_ID) != null) {
                return;
            }
            JobInfo job = new JobInfo.Builder(JOB_ID,
                    new ComponentName(c, KeepAliveJobService.class))
                    .setPeriodic(15 * 60 * 1000L)
                    .setPersisted(true)
                    .build();
            js.schedule(job);
        } catch (Throwable t) {
            Logger.log(c, "排保活心跳任务失败：" + t);
        }
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        Logger.log(this, "保活心跳：补齐服务与闹钟");
        KeepAliveService.start(this);
        CheckinScheduler.scheduleDaily(this);
        CheckinScheduler.scheduleReminderDaily(this);
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }
}
