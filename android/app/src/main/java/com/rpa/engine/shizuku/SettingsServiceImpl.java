package com.rpa.engine.shizuku;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.regex.Pattern;

/**
 * 被载入 Shizuku 服务进程执行（shell uid），因此拥有写 secure settings 的能力。
 * 安全约束：仅以参数列表方式调用 /system/bin/settings（ProcessBuilder argv，
 * 不经过 sh、不存在命令串拼接）；key/value 再加白名单校验，双重防线。
 */
public class SettingsServiceImpl extends ISettingsSvc.Stub {

    // secure settings 的 key 形如 enabled_accessibility_services
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9_]+");
    // 值仅允许系统组件路径、冒号分隔列表、开关整数等形态，杜绝任何 shell 元字符
    private static final Pattern VALUE = Pattern.compile("[A-Za-z0-9_./:$-]*");

    private static void check(String key, String value) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("非法 settings key");
        }
        if (value == null || !VALUE.matcher(value).matches()) {
            throw new IllegalArgumentException("非法 settings value");
        }
    }

    private static String run(String key, String action, String value) throws Exception {
        // argv 形式：每个参数独立成项，无 shell 参与，value 不会被解释
        ProcessBuilder pb = new ProcessBuilder("/system/bin/settings", action, "secure", key, value);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
        }
        p.waitFor();
        return sb.toString();
    }

    @Override
    public String getSecure(String key) {
        try {
            check(key, "");
            return run(key, "get", "null").trim();
        } catch (Exception e) {
            throw new IllegalArgumentException("读取失败: " + e.getMessage());
        }
    }

    @Override
    public void putSecure(String key, String value) {
        try {
            check(key, value);
            run(key, "put", value);
        } catch (Exception e) {
            throw new IllegalArgumentException("写入失败: " + e.getMessage());
        }
    }
}
