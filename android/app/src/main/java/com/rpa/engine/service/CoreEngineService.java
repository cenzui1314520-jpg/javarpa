package com.rpa.engine.service;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

import com.rpa.engine.App;
import com.rpa.engine.MainActivity;
import com.rpa.engine.R;
import com.rpa.engine.script.ScriptRepository;
import com.rpa.engine.task.TaskExecutor;
import com.rpa.engine.util.Prefs;
import com.rpa.engine.ws.WsClient;

/** Foreground service keeping the WS connection and script executor alive. */
public class CoreEngineService extends Service {
    public static final String ACTION_STATUS = "com.rpa.engine.STATUS";

    private WsClient wsClient;
    private TaskExecutor taskExecutor;

    @Override
    public void onCreate() {
        super.onCreate();
        startForeground(1, buildNotification("引擎运行中"));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!Prefs.configured(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (wsClient == null) {
            ScriptRepository repository = new ScriptRepository(this);
            taskExecutor = new TaskExecutor(this, repository);
            wsClient = new WsClient(this, taskExecutor, repository, this::notifyStatus);
            wsClient.start();
            taskExecutor.setReporter(wsClient);
        }
        return START_STICKY;
    }

    private void notifyStatus(String status) {
        android.content.Intent i = new android.content.Intent(ACTION_STATUS);
        i.setPackage(getPackageName());
        i.putExtra("status", status);
        sendBroadcast(i);
        // 状态刷新用 notify；反复 startForeground 部分机型有额外开销
        android.app.NotificationManager nm = getSystemService(android.app.NotificationManager.class);
        if (nm != null) nm.notify(1, buildNotification(status));
    }

    private Notification buildNotification(String text) {
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, App.CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    @Override
    public void onDestroy() {
        if (wsClient != null) wsClient.close();
        // 只发停止信号不在主线程 join（旧实现最长卡 2s 可致 ANR），线程靠观察器自行退出
        if (taskExecutor != null) taskExecutor.shutdown();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public static void start(Context context) {
        Intent intent = new Intent(context, CoreEngineService.class);
        context.startForegroundService(intent);
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, CoreEngineService.class));
    }
}
