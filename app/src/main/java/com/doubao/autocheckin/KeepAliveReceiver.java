package com.doubao.autocheckin;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 保活重启广播：最近任务被划掉、或心跳任务发现服务没在跑时，把前台服务重新拉起来。 */
public class KeepAliveReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        Logger.log(context, "保活广播：" + (intent == null ? "-" : intent.getAction()) + "，重新拉起服务");
        KeepAliveService.start(context);
        CheckinScheduler.scheduleDaily(context);
        CheckinScheduler.scheduleReminderDaily(context);
    }
}
