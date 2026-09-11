package com.jyu.cache.controller;

import com.jyu.cache.common.Result;
import com.jyu.cache.common.UserContext;
import com.jyu.cache.service.TokenService;
import com.jyu.cache.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 认证控制器：注册 / 登录 / 登出 / 当前用户信息
 * v3：登录请求提取客户端 IP 传给限流器；请求体加 @NotBlank 校验
 */
@Slf4j
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserService userService;
    private final TokenService tokenService;

    public AuthController(UserService userService, TokenService tokenService) {
        this.userService = userService;
        this.tokenService = tokenService;
    }

    @Data
    public static class RegisterReq {
        @jakarta.validation.constraints.NotBlank(message = "用户名不能为空")
        private String username;
        @jakarta.validation.constraints.NotBlank(message = "密码不能为空")
        private String password;
        private String nickname;
    }

    @Data
    public static class LoginReq {
        @NotBlank(message = "用户名不能为空")
        private String username;
        @NotBlank(message = "密码不能为空")
        private String password;
    }

    @PostMapping("/register")
    public Result<UserContext.LoginUser> register(@jakarta.validation.Valid @RequestBody RegisterReq req) {
        return Result.success("注册成功", userService.register(req.getUsername(), req.getPassword(), req.getNickname()));
    }

    /** 登录：返回 token + 用户信息（带 IP 限流） */
    @PostMapping("/login")
    public Result<Map<String, Object>> login(@jakarta.validation.Valid @RequestBody LoginReq req,
                                             HttpServletRequest request) {
        String ip = clientIp(request);
        String token = userService.login(req.getUsername(), req.getPassword(), ip);
        UserContext.LoginUser user = tokenService.verify(token);
        return Result.success("登录成功", Map.of("token", token, "user", user));
    }

    @PostMapping("/logout")
    public Result<Void> logout(@RequestHeader(value = "Authorization", required = false) String token) {
        userService.logout(token);
        return Result.success("已退出登录", null);
    }

    @GetMapping("/me")
    public Result<UserContext.LoginUser> me() {
        UserContext.LoginUser user = UserContext.getUser();
        if (user == null) {
            return Result.fail(401, "未登录");
        }
        return Result.success(user);
    }

    /** 提取客户端真实 IP（优先 X-Forwarded-For，兼容反向代理场景） */
    private String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
