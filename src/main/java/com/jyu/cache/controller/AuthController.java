package com.jyu.cache.controller;

import com.jyu.cache.common.ClientIpResolver;
import com.jyu.cache.common.Result;
import com.jyu.cache.common.UserContext;
import com.jyu.cache.service.TokenService;
import com.jyu.cache.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
 * v3.3：客户端 IP 改由 ClientIpResolver 解析——默认不信任 X-Forwarded-For，
 *       否则伪造该头即可换 IP 维度计数、绕过限流
 */
@Slf4j
@Tag(name = "认证", description = "注册 / 登录 / 登出 / 当前用户")
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserService userService;
    private final TokenService tokenService;
    private final ClientIpResolver clientIpResolver;

    public AuthController(UserService userService, TokenService tokenService, ClientIpResolver clientIpResolver) {
        this.userService = userService;
        this.tokenService = tokenService;
        this.clientIpResolver = clientIpResolver;
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

    @Operation(summary = "用户注册（游客可调）", description = "用户名至少 3 字符、密码至少 6 位；用户名已存在返回 409")
    @PostMapping("/register")
    public Result<UserContext.LoginUser> register(@jakarta.validation.Valid @RequestBody RegisterReq req) {
        return Result.success("注册成功", userService.register(req.getUsername(), req.getPassword(), req.getNickname()));
    }

    /** 登录：返回 token + 用户信息（带 IP 限流） */
    @Operation(summary = "登录签发 token（游客可调，IP 限流）", description = "按用户名与客户端 IP 双维度限流，超限 429；密码错误 401，账号禁用 403")
    @PostMapping("/login")
    public Result<Map<String, Object>> login(@jakarta.validation.Valid @RequestBody LoginReq req,
                                             HttpServletRequest request) {
        String ip = clientIpResolver.resolve(request);
        String token = userService.login(req.getUsername(), req.getPassword(), ip);
        UserContext.LoginUser user = tokenService.verify(token);
        return Result.success("登录成功", Map.of("token", token, "user", user));
    }

    @Operation(summary = "退出登录（需登录，作废 token）", description = "写操作先经拦截器校验登录，未登录 401")
    @PostMapping("/logout")
    public Result<Void> logout(@RequestHeader(value = "Authorization", required = false) String token) {
        userService.logout(token);
        return Result.success("已退出登录", null);
    }

    @Operation(summary = "当前登录用户（需登录）", description = "未登录返回 401")
    @GetMapping("/me")
    public Result<UserContext.LoginUser> me() {
        UserContext.LoginUser user = UserContext.getUser();
        if (user == null) {
            return Result.fail(401, "未登录");
        }
        return Result.success(user);
    }

    /** 提取客户端真实 IP（默认 socket 地址；只有直连方是可信代理时才解析 X-Forwarded-For） */
    private String clientIp(HttpServletRequest request) {
        return clientIpResolver.resolve(request);
    }
}
