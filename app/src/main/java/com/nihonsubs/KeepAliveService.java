package com.nihonsubs;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/**
 * 前台服务：只做一件事 —— 把进程提升为前台优先级，让 16 分钟音频那 6~7 分钟的处理
 * 在用户切到别的 App 时不被系统回收，同时在通知栏显示当前阶段和进度。
 *
 * 真正的流水线仍在 MainActivity 的后台线程里跑，这里只负责保活 + 展示，
 * 所以不需要 Binder，也不需要把 run.sh 搬进来。
 */
public class KeepAliveService extends Service {

    public static final String ACTION_START = "com.nihonsubs.KEEPALIVE_START";
    public static final String ACTION_STOP  = "com.nihonsubs.KEEPALIVE_STOP";
    private static final String CHANNEL_ID = "asmr_pipeline";
    private static final int NOTIF_ID = 1001;

    private NotificationManager nm;
    private int pct = 0;
    private String stage = "准备中";

    @Override
    public void onCreate() {
        super.onCreate();
        nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel c = new NotificationChannel(
                    CHANNEL_ID, "处理进度", NotificationManager.IMPORTANCE_LOW);
            c.setDescription("显示音频处理的当前阶段与进度");
            c.setShowBadge(false);
            c.setSound(null, null);
            nm.createNotificationChannel(c);
        }
    }

    @Override
    public int onStartCommand(Intent it, int flags, int startId) {
        if (it != null && ACTION_STOP.equals(it.getAction())) {
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (it != null) {
            pct = it.getIntExtra("pct", pct);
            String s = it.getStringExtra("stage");
            if (s != null && s.length() > 0) stage = s;
        }
        startForeground(NOTIF_ID, build());
        return START_NOT_STICKY;
    }

    private Notification build() {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            piFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, piFlags);

        Notification.Builder b = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        b.setContentTitle("正在翻译：" + stage)
                .setContentText(pct + "%　（切到后台也会继续）")
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(100, Math.max(0, Math.min(100, pct)), false)
                .setContentIntent(pi);
        return b.build();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}