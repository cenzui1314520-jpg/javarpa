package com.rpa.server.config;

import com.rpa.server.security.ApiTokenInterceptor;
import com.rpa.server.security.DeviceAuthInterceptor;
import com.rpa.server.security.JwtInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    private final JwtInterceptor jwtInterceptor;
    private final ApiTokenInterceptor apiTokenInterceptor;
    private final DeviceAuthInterceptor deviceAuthInterceptor;

    @Value("${rpa.upload-dir:./data/scripts}")
    private String uploadDir;

    // 调试期默认放行所有来源（*）；生产用 RPA_CORS_ORIGINS 覆盖为逗号分隔白名单
    @Value("${rpa.cors-origins:*}")
    private String corsOrigins;

    public WebConfig(JwtInterceptor jwtInterceptor,
                     ApiTokenInterceptor apiTokenInterceptor,
                     DeviceAuthInterceptor deviceAuthInterceptor) {
        this.jwtInterceptor = jwtInterceptor;
        this.apiTokenInterceptor = apiTokenInterceptor;
        this.deviceAuthInterceptor = deviceAuthInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(jwtInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns(
                        "/auth/login", "/ws/**", "/files/**", "/open/**",
                        "/error", "/favicon.ico");
        registry.addInterceptor(apiTokenInterceptor).addPathPatterns("/open/**");
        registry.addInterceptor(deviceAuthInterceptor).addPathPatterns("/files/**");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String location = uploadDir.startsWith("/")
                ? "file:" + uploadDir + "/"
                : "file:" + System.getProperty("user.dir") + "/" + uploadDir + "/";
        registry.addResourceHandler("/files/scripts/**").addResourceLocations(location);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns(corsOrigins.split(","))
                .allowedMethods("*")
                .allowedHeaders("*");
    }
}
