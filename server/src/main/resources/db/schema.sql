-- JavaRPA cloud schema (idempotent)

CREATE TABLE IF NOT EXISTS admin_user (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(64) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    nickname VARCHAR(64),
    role VARCHAR(20) NOT NULL DEFAULT 'ADMIN',
    status TINYINT NOT NULL DEFAULT 1,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 改密时间持久化：改密后旧 JWT 失效的判定不随服务重启丢失
CREATE TABLE IF NOT EXISTS admin_token_state (
    admin_id BIGINT PRIMARY KEY,
    password_changed_at BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS api_token (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(64) NOT NULL,
    prefix VARCHAR(16) NOT NULL,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    status TINYINT NOT NULL DEFAULT 1,
    last_used_at DATETIME,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS device_group (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(64) NOT NULL,
    remark VARCHAR(255),
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS device (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    device_sn VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(64),
    group_id BIGINT,
    model VARCHAR(64),
    brand VARCHAR(64),
    android_version VARCHAR(32),
    sdk_int INT,
    app_version VARCHAR(32),
    engine_version VARCHAR(32),
    secret VARCHAR(64) NOT NULL,
    status TINYINT NOT NULL DEFAULT 1,
    online TINYINT NOT NULL DEFAULT 0,
    last_active_at DATETIME,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    KEY idx_group (group_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS device_group_member (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    group_id BIGINT NOT NULL,
    device_id BIGINT NOT NULL,
    UNIQUE KEY uk_group_device (group_id, device_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS script (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(64) NOT NULL,
    pkg_name VARCHAR(64) NOT NULL UNIQUE,
    description VARCHAR(255),
    stable_version_code INT NOT NULL DEFAULT 0,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS script_version (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    script_id BIGINT NOT NULL,
    version_code INT NOT NULL,
    version_name VARCHAR(32),
    file_path VARCHAR(255) NOT NULL,
    file_md5 VARCHAR(64) NOT NULL,
    file_size BIGINT,
    status TINYINT NOT NULL DEFAULT 1,
    changelog VARCHAR(500),
    created_by VARCHAR(64),
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_script_version (script_id, version_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS publish_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    script_id BIGINT NOT NULL,
    version_code INT NOT NULL,
    target_type VARCHAR(16) NOT NULL,
    target_value VARCHAR(255),
    operator VARCHAR(64),
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    KEY idx_script (script_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS task (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(64) NOT NULL,
    script_id BIGINT NOT NULL,
    version_code INT,
    params_json TEXT,
    schedule_type VARCHAR(16) NOT NULL DEFAULT 'IMMEDIATE',
    cron_expr VARCHAR(64),
    max_retries INT NOT NULL DEFAULT 0,
    status TINYINT NOT NULL DEFAULT 1,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS task_device (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id BIGINT NOT NULL,
    device_id BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    retry_count INT NOT NULL DEFAULT 0,
    success_count INT NOT NULL DEFAULT 0,
    fail_count INT NOT NULL DEFAULT 0,
    last_run_at DATETIME,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_task_device (task_id, device_id),
    KEY idx_device (device_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS task_execution (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id BIGINT NOT NULL,
    device_id BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    success_count INT NOT NULL DEFAULT 0,
    fail_count INT NOT NULL DEFAULT 0,
    error_msg VARCHAR(1000),
    duration_ms BIGINT,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    KEY idx_task (task_id),
    KEY idx_created (created_at),
    KEY idx_device (device_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- device_log 按 taskId 过滤（/logs 与开放接口）依赖该索引；存量库需手动执行:
-- ALTER TABLE device_log ADD INDEX idx_task (task_id);
CREATE TABLE IF NOT EXISTS device_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    device_id BIGINT NOT NULL,
    task_id BIGINT,
    level VARCHAR(8) NOT NULL,
    tag VARCHAR(64),
    content VARCHAR(2000),
    log_time DATETIME,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    KEY idx_device_time (device_id, created_at),
    KEY idx_task (task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS stats_daily (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    stat_date DATE NOT NULL,
    task_id BIGINT NOT NULL,
    script_id BIGINT NOT NULL,
    total_exec INT NOT NULL DEFAULT 0,
    success_exec INT NOT NULL DEFAULT 0,
    fail_exec INT NOT NULL DEFAULT 0,
    success_cnt INT NOT NULL DEFAULT 0,
    fail_cnt INT NOT NULL DEFAULT 0,
    UNIQUE KEY uk_date_task (stat_date, task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
