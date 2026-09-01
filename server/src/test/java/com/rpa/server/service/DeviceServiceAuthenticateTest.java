package com.rpa.server.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rpa.server.common.DigestUtil;
import com.rpa.server.entity.Device;
import com.rpa.server.mapper.DeviceGroupMapper;
import com.rpa.server.mapper.DeviceGroupMemberMapper;
import com.rpa.server.mapper.DeviceMapper;
import com.rpa.server.ws.AdminStompService;
import com.rpa.server.ws.DeviceSessionManager;
import com.rpa.server.ws.RedisQueueService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DeviceServiceAuthenticateTest {
    private DeviceMapper deviceMapper;
    private DeviceService deviceService;

    private Device deviceWithSecret(String secret) {
        Device d = new Device();
        d.id = 1L;
        d.deviceSn = "SN-001";
        d.secret = secret;
        d.status = 1;
        return d;
    }

    @BeforeEach
    void setup() {
        deviceMapper = mock(DeviceMapper.class);
        deviceService = new DeviceService(deviceMapper, mock(DeviceGroupMapper.class),
                mock(DeviceGroupMemberMapper.class), mock(DeviceSessionManager.class),
                mock(RedisQueueService.class), mock(AdminStompService.class),
                mock(DeviceLogService.class), mock(PublishService.class),
                mock(TaskControlService.class));
    }

    @Test
    void hashedSecretVerifiedByPlaintext() {
        when(deviceMapper.selectOne(any(QueryWrapper.class)))
                .thenReturn(deviceWithSecret(DigestUtil.sha256Hex("raw-secret")));
        assertNotNull(deviceService.authenticate("SN-001", "raw-secret"));
    }

    @Test
    void hashedSecretNotVerifiedByHashValueItself() {
        String hash = DigestUtil.sha256Hex("raw-secret");
        when(deviceMapper.selectOne(any(QueryWrapper.class))).thenReturn(deviceWithSecret(hash));
        // 库中哈希值本身不能当凭据使用
        assertNull(deviceService.authenticate("SN-001", hash));
    }

    @Test
    void legacyPlaintextSecretStillWorks() {
        when(deviceMapper.selectOne(any(QueryWrapper.class)))
                .thenReturn(deviceWithSecret("legacy-plain-secret"));
        assertNotNull(deviceService.authenticate("SN-001", "legacy-plain-secret"));
    }

    @Test
    void wrongSecretRejected() {
        when(deviceMapper.selectOne(any(QueryWrapper.class)))
                .thenReturn(deviceWithSecret(DigestUtil.sha256Hex("raw-secret")));
        assertNull(deviceService.authenticate("SN-001", "other"));
        assertNull(deviceService.authenticate("SN-001", null));
    }

    @Test
    void disabledDeviceRejected() {
        Device d = deviceWithSecret(DigestUtil.sha256Hex("raw-secret"));
        d.status = 0;
        when(deviceMapper.selectOne(any(QueryWrapper.class))).thenReturn(d);
        assertNull(deviceService.authenticate("SN-001", "raw-secret"));
    }

    @Test
    void createStoresHashAndReturnsPlaintext() {
        when(deviceMapper.selectCount(any(QueryWrapper.class))).thenReturn(0L);
        // MP 通过实体回填 id，这里手动模拟
        when(deviceMapper.insert(any(Device.class))).thenAnswer(inv -> {
            Device arg = inv.getArgument(0);
            arg.id = 100L;
            return 1;
        });
        Device created = deviceService.create("SN-002", "test", null);
        assertEquals(100L, created.id);
        assertNotNull(created.secret);
        assertEquals(64, DigestUtil.sha256Hex(created.secret).length());
    }
}
