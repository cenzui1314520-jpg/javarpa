package com.rpa.engine;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.rpa.engine.accessibility.AutoAccessibilityService;
import com.rpa.engine.service.CoreEngineService;
import com.rpa.engine.util.Prefs;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

import org.json.JSONObject;

public class MainActivity extends Activity {

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            TextView tv = findViewById(R.id.tvStatus);
            tv.setText(intent.getStringExtra("status"));
            refreshButtons();
            // 鉴权失败时服务在广播后才 stopSelf，延迟复核一次按钮状态
            new android.os.Handler(Looper.getMainLooper())
                    .postDelayed(() -> refreshButtons(), 600);
        }
    };

    /** 引擎运行中：禁用启动、可用停止；反之亦然。 */
    private void refreshButtons() {
        boolean running = CoreEngineService.isRunning();
        findViewById(R.id.btnStart).setEnabled(!running);
        findViewById(R.id.btnStop).setEnabled(running);
    }

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
            // 先停再起：凭据可能已变（如重新扫码），老连接不重启会一直用旧凭据
            CoreEngineService.stop(this);
            CoreEngineService.start(this);
            refreshButtons();
            Toast.makeText(this, "引擎已启动", Toast.LENGTH_SHORT).show();
        });

        findViewById(R.id.btnStop).setOnClickListener(v -> {
            CoreEngineService.stop(MainActivity.this);
            // 服务销毁是异步的，延迟刷新按钮状态
            new android.os.Handler(Looper.getMainLooper())
                    .postDelayed(this::refreshButtons, 300);
        });

        findViewById(R.id.btnScan).setOnClickListener(v ->
                new IntentIntegrator(this)
                        .setPrompt("对准管理后台「设备配置二维码」")
                        // 锁定方向，走 manifest 里覆盖声明的竖屏
                        .setOrientationLocked(true)
                        .initiateScan());

        findViewById(R.id.btnAccessibility).setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));

        Button btnAcc = findViewById(R.id.btnAccessibility);
        if (AutoAccessibilityService.isRunning()) {
            btnAcc.setText("无障碍服务已开启");
            btnAcc.setEnabled(false);
        }
        refreshButtons();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        IntentResult result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
        if (result == null || result.getContents() == null) return; // 用户取消扫码
        applyQrConfig(result.getContents());
    }

    /** 解析管理后台生成的配置二维码：{"v":1,"type":"javarpa-device","server","deviceSn","secret"}。 */
    private void applyQrConfig(String contents) {
        try {
            JSONObject cfg = new JSONObject(contents);
            if (!"javarpa-device".equals(cfg.optString("type"))) {
                Toast.makeText(this, "不是 JavaRPA 设备配置二维码", Toast.LENGTH_SHORT).show();
                return;
            }
            String server = cfg.optString("server", "").trim();
            String sn = cfg.optString("deviceSn", "").trim();
            String secret = cfg.optString("secret", "").trim();
            if (!server.startsWith("http://") && !server.startsWith("https://")) {
                Toast.makeText(this, "二维码中的服务器地址非法", Toast.LENGTH_SHORT).show();
                return;
            }
            if (TextUtils.isEmpty(sn) || TextUtils.isEmpty(secret)) {
                Toast.makeText(this, "二维码缺少设备编号或密钥", Toast.LENGTH_SHORT).show();
                return;
            }
            ((EditText) findViewById(R.id.etServer)).setText(server);
            ((EditText) findViewById(R.id.etDeviceSn)).setText(sn);
            ((EditText) findViewById(R.id.etSecret)).setText(secret);
            // 引擎在跑时旧凭据已失效，直接停掉等用户确认新配置后重新启动
            if (CoreEngineService.isRunning()) {
                CoreEngineService.stop(this);
                new android.os.Handler(Looper.getMainLooper())
                        .postDelayed(this::refreshButtons, 300);
                Toast.makeText(this, "已填入 " + sn + " 的新配置（旧引擎已停止），请点击「保存并启动引擎」", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "已填入 " + sn + " 的配置，请点击「保存并启动引擎」", Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "二维码内容无法解析", Toast.LENGTH_SHORT).show();
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
        refreshButtons();
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(statusReceiver);
    }
}
