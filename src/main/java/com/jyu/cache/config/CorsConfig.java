package com.jyu.cache.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 跨域配置（v3 收紧）
 *
 * v2 问题：allowedOriginPatterns("*") 全开，任何网站都能调本系统 API。
 * v3 修复：来源改为可配置白名单（CORS_ALLOWED_ORIGINS，逗号分隔），
 *          默认仅放行本地开发常用来源；未配置时退回 *（保持演示便利，README 说明）。
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    /** 允许的来源列表，逗号分隔；未配置时为 * */
    @Value("${app.cors.allowed-origins:*}")
    private String allowedOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] origins = allowedOrigins.split("\\s*,\\s*");
        registry.addMapping("/api/**")
                .allowedOriginPatterns(origins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(false)
                .maxAge(3600);
    }
}
