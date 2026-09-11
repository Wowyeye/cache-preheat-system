package com.jyu.cache.service;

import com.jyu.cache.common.BusinessException;
import com.jyu.cache.common.UserContext;
import com.jyu.cache.entity.SysUser;
import com.jyu.cache.mapper.UserMapper;
import com.jyu.cache.common.RateLimiter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 用户服务（v3：登录/注册接入限流）
 *
 * 密码安全：BCrypt 单向加密存储（自带盐），登录时 matches() 比对。
 * 登录态：token 由 TokenService 签发并存放 Redis。
 * v3 新增：RateLimiter 按"用户名 + IP"双维度限流（60 秒窗口 5 次），
 *          登录失败记账、成功清零，超限抛 429。
 */
@Slf4j
@Service
public class UserService {

    private final UserMapper userMapper;
    private final TokenService tokenService;
    private final RateLimiter rateLimiter;
    private final BCryptPasswordEncoder passwordEncoder;

    public UserService(UserMapper userMapper, TokenService tokenService, RateLimiter rateLimiter) {
        this.userMapper = userMapper;
        this.tokenService = tokenService;
        this.rateLimiter = rateLimiter;
        this.passwordEncoder = new BCryptPasswordEncoder();
    }

    /**
     * 注册：用户名唯一校验 + BCrypt 加密入库，默认普通用户角色
     */
    public UserContext.LoginUser register(String username, String password, String nickname) {
        if (username == null || username.trim().length() < 3) {
            throw new BusinessException(400, "用户名至少 3 个字符");
        }
        if (password == null || password.length() < 6) {
            throw new BusinessException(400, "密码至少 6 位");
        }
        String name = username.trim();
        if (userMapper.selectByUsername(name) != null) {
            throw new BusinessException(409, "用户名已存在");
        }

        SysUser user = new SysUser();
        user.setUsername(name);
        user.setPassword(passwordEncoder.encode(password));
        user.setNickname(nickname == null || nickname.isBlank() ? name : nickname.trim());
        user.setRole("USER");
        userMapper.insert(user);
        log.info("[用户注册] id={} username={}", user.getId(), name);
        return toLoginUser(user);
    }

    /**
     * 登录：限流检查 -> 密码校验 -> 签发 token
     * @param ip 客户端 IP（Controller 层提取，用于限流维度）
     */
    public String login(String username, String password, String ip) {
        rateLimiter.checkLoginAllowed(username, ip);

        SysUser user = userMapper.selectByUsername(username);
        if (user == null || !passwordEncoder.matches(password, user.getPassword())) {
            // 模糊提示，不暴露"用户不存在/密码错误"区别，防撞库
            rateLimiter.recordLoginFailure(username, ip);
            throw new BusinessException(401, "用户名或密码错误");
        }
        if (user.getStatus() == null || user.getStatus() != 1) {
            throw new BusinessException(403, "账号已被禁用");
        }
        rateLimiter.resetOnSuccess(username, ip);
        String token = tokenService.issue(toLoginUser(user));
        log.info("[用户登录] id={} username={} role={}", user.getId(), user.getUsername(), user.getRole());
        return token;
    }

    /** 登出：作废 token */
    public void logout(String token) {
        tokenService.invalidate(token);
    }

    private UserContext.LoginUser toLoginUser(SysUser user) {
        return new UserContext.LoginUser(user.getId(), user.getUsername(),
                user.getNickname() == null ? user.getUsername() : user.getNickname(), user.getRole());
    }
}
