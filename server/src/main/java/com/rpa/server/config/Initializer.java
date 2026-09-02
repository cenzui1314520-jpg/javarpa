package com.rpa.server.config;

import com.rpa.server.common.DigestUtil;
import com.rpa.server.entity.AdminUser;
import com.rpa.server.mapper.AdminUserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class Initializer implements CommandLineRunner {
    private static final Logger log = LoggerFactory.getLogger(Initializer.class);

    private final AdminUserMapper adminUserMapper;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public Initializer(AdminUserMapper adminUserMapper) {
        this.adminUserMapper = adminUserMapper;
    }

    @Override
    public void run(String... args) {
        if (adminUserMapper.selectCount(null) == 0) {
            String envPassword = System.getenv("RPA_ADMIN_PASSWORD");
            boolean fromEnv = envPassword != null && envPassword.length() >= 6;
            String password = fromEnv ? envPassword : DigestUtil.randomToken(16);
            AdminUser admin = new AdminUser();
            admin.username = "admin";
            admin.passwordHash = encoder.encode(password);
            admin.nickname = "管理员";
            admin.role = "ADMIN";
            admin.status = 1;
            adminUserMapper.insert(admin);
            if (fromEnv) {
                log.warn("default admin created from RPA_ADMIN_PASSWORD, change it after first login");
            } else {
                // 随机口令不整串进日志（日志聚合/截图留痕即凭据泄露），首登前 4 位仅用于人工比对
                log.warn("default admin created: admin / {}**** (random, shown partially — "
                        + "use RPA_ADMIN_PASSWORD to preset, or reset via DB if lost)", password.substring(0, 4));
            }
        }
    }
}
