package com.rpa.engine;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;

public class App extends Application {
    public static final String CHANNEL_ID = "rpa_core";
    private static App instance;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "RPA 核心引擎", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("保持引擎与云端的连接");
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.createNotificationChannel(channel);
        }
    }

    public static App get() {
        return instance;
    }
}
