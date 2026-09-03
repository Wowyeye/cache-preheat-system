package com.jyu.cache.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 跨域配置（CORS）
 *
 * 前端 index.html 是独立静态页面（file:// 或本地静态服务器打开），
 * 与后端 8081 端口不同源，浏览器会拦截跨域请求。
 * 此配置允许所有来源访问本系统 API，使前端面板可以正常调用后端接口。
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("*")           // 允许所有来源（file:// 的 origin 为 null 也放行）
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS") // 放行全部接口方法
                .allowedHeaders("*")                  // 允许所有请求头（含 Content-Type: application/json）
                .allowCredentials(false)              // 不携带 Cookie，可安全使用通配符
                .maxAge(3600);                        // 预检请求缓存1小时，减少 OPTIONS 次数
    }
}
