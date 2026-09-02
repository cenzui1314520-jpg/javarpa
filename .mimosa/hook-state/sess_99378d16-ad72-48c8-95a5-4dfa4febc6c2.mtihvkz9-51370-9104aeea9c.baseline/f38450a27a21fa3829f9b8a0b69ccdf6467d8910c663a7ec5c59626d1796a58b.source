package com.rpa.engine;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.rpa.engine.service.CoreEngineService;
import com.rpa.engine.util.Prefs;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())
                && Prefs.configured(context)) {
            CoreEngineService.start(context);
        }
    }
}
