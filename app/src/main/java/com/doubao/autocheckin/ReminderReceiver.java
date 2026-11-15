package com.doubao.autocheckin;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 未打卡提醒：到点检查今天有没有打卡，没有就发通知催人，
 * 同时把明天的提醒排好。用户点通知会打开 App，App 里再弹窗。
 */
public class ReminderReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        Logger.log(context, "未打卡提醒闹钟触发");
        // 先把明天的排上，避免中途异常丢失
        CheckinScheduler.scheduleReminderDaily(context);

        if (!Config.isEnabled(context)) {
            Logger.log(context, "自动签到总开关已关闭，跳过提醒");
            return;
        }
        if (Records.todayDone(context)) {
            Logger.log(context, "今天已完成打卡，不发提醒");
            return;
        }
        Notifier.notifyReminder(context, Records.todayFailedOnly(context));
    }
}
