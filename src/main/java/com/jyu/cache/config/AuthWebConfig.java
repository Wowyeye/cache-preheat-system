package com.jyu.cache.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jyu.cache.service.TokenService;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 鉴权路由配置
 *
 * 三档权限模型：
 * 1. 游客可访问：登录/注册、商城浏览（GET 读接口由拦截器按方法放行）
 * 2. 登录可访问：下单、支付、我的订单、退款/退货申请、登出
 * 3. 管理员可访问：商品的增删改、全部订单管理、订单审核、缓存管理
 */
@Configuration
public class AuthWebConfig implements WebMvcConfigurer {

    private final TokenService tokenService;
    private final ObjectMapper objectMapper;

    public AuthWebConfig(TokenService tokenService, ObjectMapper objectMapper) {
        this.tokenService = tokenService;
        this.objectMapper = objectMapper;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AuthInterceptor(tokenService, objectMapper))
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        "/api/auth/login", "/api/auth/register"
                );
    }
}
