package com.rpa.engine;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.rpa.engine.accessibility.AutoAccessibilityService;
import com.rpa.engine.service.CoreEngineService;
import com.rpa.engine.util.Prefs;

public class MainActivity extends Activity {

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            TextView tv = findViewById(R.id.tvStatus);
            tv.setText(intent.getStringExtra("status"));
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        EditText etServer = findViewById(R.id.etServer);
        EditText etSn = findViewById(R.id.etDeviceSn);
        EditText etSecret = findViewById(R.id.etSecret);
        etServer.setText(Prefs.serverUrl(this));
        etSn.setText(Prefs.deviceSn(this));
        etSecret.setText(Prefs.secret(this));

        findViewById(R.id.btnStart).setOnClickListener(v -> {
            String server = etServer.getText().toString().trim();
            String sn = etSn.getText().toString().trim();
            String secret = etSecret.getText().toString().trim();
            if (TextUtils.isEmpty(server) || TextUtils.isEmpty(sn) || TextUtils.isEmpty(secret)) {
                Toast.makeText(this, "请填写完整的服务器地址、设备编号与密钥", Toast.LENGTH_SHORT).show();
                return;
            }
            // 提前拦截非法地址，避免重连循环中反复崩溃
            if (!server.startsWith("http://") && !server.startsWith("https://")) {
                Toast.makeText(this, "服务器地址需以 http:// 或 https:// 开头", Toast.LENGTH_SHORT).show();
                return;
            }
            Prefs.save(this, server, sn, secret);
            if (Build.VERSION.SDK_INT >= 33) {
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
            }
            CoreEngineService.start(this);
            Toast.makeText(this, "引擎已启动", Toast.LENGTH_SHORT).show();
        });

        findViewById(R.id.btnStop).setOnClickListener(v ->
                CoreEngineService.stop(MainActivity.this));

        findViewById(R.id.btnAccessibility).setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));

        Button btnAcc = findViewById(R.id.btnAccessibility);
        if (AutoAccessibilityService.isRunning()) {
            btnAcc.setText("无障碍服务已开启");
            btnAcc.setEnabled(false);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1 && (grantResults.length == 0 || grantResults[0] != 0)) {
            Toast.makeText(this, "未授予通知权限，引擎将在后台静默运行", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter(CoreEngineService.ACTION_STATUS);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(statusReceiver, filter);
        }
        Button btnAcc = findViewById(R.id.btnAccessibility);
        if (AutoAccessibilityService.isRunning()) {
            btnAcc.setText("无障碍服务已开启");
            btnAcc.setEnabled(false);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(statusReceiver);
    }
}
