package com.jyu.cache.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jyu.cache.common.Result;
import com.jyu.cache.common.UserContext;
import com.jyu.cache.service.TokenService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 登录鉴权拦截器
 *
 * 职责（单一）：校验 Authorization 头中的 token，把用户写入 UserContext。
 * 路由规则在 AuthWebConfig 中集中配置（游客/登录/管理员三档）。
 */
public class AuthInterceptor implements HandlerInterceptor {

    private final TokenService tokenService;
    private final ObjectMapper objectMapper;

    public AuthInterceptor(TokenService tokenService, ObjectMapper objectMapper) {
        this.tokenService = tokenService;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String token = request.getHeader("Authorization");
        UserContext.LoginUser user = tokenService.verify(token);

        // 安全方法（GET 只读）：游客放行，登录用户写入上下文
        // 非安全方法（POST/PUT/DELETE 写操作）：必须登录
        if ("GET".equalsIgnoreCase(request.getMethod())) {
            if (user != null) {
                UserContext.set(user);
            }
            return true;
        }
        if (user == null) {
            writeJson(response, Result.fail(401, "未登录或登录已过期，请重新登录"));
            return false;
        }
        UserContext.set(user);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                 Object handler, Exception ex) {
        UserContext.clear();
    }

    private void writeJson(HttpServletResponse response, Result<Void> result) throws Exception {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(result));
    }
}
