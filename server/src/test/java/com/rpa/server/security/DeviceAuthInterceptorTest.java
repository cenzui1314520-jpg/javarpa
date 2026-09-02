package com.rpa.server.security;

import com.rpa.server.entity.Device;
import com.rpa.server.service.DeviceService;
import com.rpa.server.service.ScriptService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeviceAuthInterceptorTest {
    private DeviceService deviceService;
    private ScriptService scriptService;
    private DeviceAuthInterceptor interceptor;
    private HttpServletRequest request;
    private HttpServletResponse response;

    private Device device() {
        Device d = new Device();
        d.id = 1L;
        d.deviceSn = "SN-001";
        d.status = 1;
        return d;
    }

    @BeforeEach
    void setup() throws Exception {
        deviceService = mock(DeviceService.class);
        scriptService = mock(ScriptService.class);
        interceptor = new DeviceAuthInterceptor(deviceService, scriptService);
        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
        when(request.getHeader("X-Device-Sn")).thenReturn("SN-001");
        when(request.getHeader("X-Device-Secret")).thenReturn("secret");
        when(deviceService.authenticate("SN-001", "secret")).thenReturn(device());
        when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
    }

    private void path(String servletPath) {
        when(request.getServletPath()).thenReturn(servletPath);
    }

    @Test
    void badCredentialsRejected() throws Exception {
        path("/files/scripts/pkg/5.zip");
        when(deviceService.authenticate("SN-001", "secret")).thenReturn(null);
        assertFalse(interceptor.preHandle(request, response, new Object()));
        verify(response).setStatus(401);
    }

    @Test
    void normalZipRequiresGrayAuthorization() throws Exception {
        path("/files/scripts/pkg/5.zip");
        when(scriptService.canDeviceDownload(any(), eq("pkg"), eq(5))).thenReturn(true);
        assertTrue(interceptor.preHandle(request, response, new Object()));
        verify(scriptService).canDeviceDownload(any(), eq("pkg"), eq(5));
    }

    @Test
    void grayDeniedReturns403() throws Exception {
        path("/files/scripts/pkg/5.zip");
        when(scriptService.canDeviceDownload(any(), eq("pkg"), eq(5))).thenReturn(false);
        assertFalse(interceptor.preHandle(request, response, new Object()));
        verify(response).setStatus(403);
    }

    @Test
    void matrixParamVariantStillAuthorized() throws Exception {
        // 容器剥掉矩阵参数后 servletPath 恒为规范形态，鉴权不可被 ;x=1 跳过
        when(request.getServletPath()).thenReturn("/files/scripts/pkg/5.zip");
        when(scriptService.canDeviceDownload(any(), eq("pkg"), eq(5))).thenReturn(true);
        assertTrue(interceptor.preHandle(request, response, new Object()));
        verify(scriptService).canDeviceDownload(any(), eq("pkg"), eq(5));
    }

    @Test
    void doubleSlashVariantNormalizedThenAuthorized() throws Exception {
        path("/files/scripts//pkg/5.zip");
        when(scriptService.canDeviceDownload(any(), eq("pkg"), eq(5))).thenReturn(true);
        assertTrue(interceptor.preHandle(request, response, new Object()));
        verify(scriptService).canDeviceDownload(any(), eq("pkg"), eq(5));
    }

    @Test
    void nonConformingScriptPathFailClosed() throws Exception {
        // 严格形态之外的 /files/scripts/** 一律 403，杜绝归一化差异绕过
        path("/files/scripts/pkg/5.zip;rn=evil");
        assertFalse(interceptor.preHandle(request, response, new Object()));
        verify(scriptService, never()).canDeviceDownload(any(), any(), Mockito.anyInt());

        path("/files/scripts/pkg/../other/5.zip");
        assertFalse(interceptor.preHandle(request, response, new Object()));
        verify(response, Mockito.times(2)).setStatus(403);
        verify(scriptService, never()).canDeviceDownload(any(), any(), Mockito.anyInt());
    }

    @Test
    void pathOutsideScriptsDirSkipsGrayCheck() throws Exception {
        path("/files/other.txt");
        assertTrue(interceptor.preHandle(request, response, new Object()));
        verify(scriptService, never()).canDeviceDownload(any(), any(), Mockito.anyInt());
    }
}
