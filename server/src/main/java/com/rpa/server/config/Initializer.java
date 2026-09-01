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
            AdminUser admin = new AdminUser();
            admin.username = "admin";
            admin.passwordHash = encoder.encode("admin123");
            admin.nickname = "管理员";
            admin.role = "ADMIN";
            admin.status = 1;
            adminUserMapper.insert(admin);
            log.info("default admin created: admin / admin123 (please change the password)");
        }
    }
}
