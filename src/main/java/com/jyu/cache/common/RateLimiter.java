package com.jyu.cache.common;

import com.jyu.cache.config.CacheProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 登录/注册限流器（v3 新增，基于 Redis 固定窗口计数）
 *
 * v2 问题：登录接口无任何频率限制，可被脚本暴力破解（对 6 位密码爆破成本极低）。
 *
 * v3 方案：按"用户名 + IP"两个维度分别计数，任一维度超限即拒绝：
 *   key   = login:rate:{username} / login:rate:ip:{ip}
 *   计数  = INCR，首次 INCR 时设置窗口 TTL
 *   超限  = 抛 429，前端提示"尝试过于频繁"
 *
 * 为什么用 Redis 而不是本地计数：多实例部署时共享同一个计数，防的住分布式爆破。
 */
@Slf4j
@Service
public class RateLimiter {

    private static final String KEY_USERNAME = "login:rate:";
    private static final String KEY_IP = "login:rate:ip:";

    /** 窗口长度（秒） */
    private static final long WINDOW_SECONDS = 60;

    /** 窗口内允许的最大尝试次数 */
    private static final int MAX_ATTEMPTS = 5;

    private final RedisTemplate<String, Object> redisTemplate;

    public RateLimiter(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 登录前检查：用户名或 IP 任一维度超限则抛 429
     * Redis 故障时放行（限流是安全增强，不能因它故障而拒绝所有用户登录）
     */
    public void checkLoginAllowed(String username, String ip) {
        try {
            if (overLimit(KEY_USERNAME + username)) {
                log.warn("[限流] 用户名 {} 登录尝试过于频繁", username);
                throw new com.jyu.cache.common.BusinessException(429, "登录尝试过于频繁，请 1 分钟后再试");
            }
            if (overLimit(KEY_IP + ip)) {
                log.warn("[限流] IP {} 登录尝试过于频繁", ip);
                throw new com.jyu.cache.common.BusinessException(429, "该 IP 登录尝试过于频繁，请 1 分钟后再试");
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[限流] Redis 不可用，本次放行：{}", e.getMessage());
        }
    }

    /** 登录失败后记账：两个维度各 +1 */
    public void recordLoginFailure(String username, String ip) {
        try {
            incr(KEY_USERNAME + username);
            incr(KEY_IP + ip);
        } catch (Exception e) {
            log.warn("[限流] 记录失败次数异常（忽略）：{}", e.getMessage());
        }
    }

    /** 登录成功后清零，避免误伤后续正常登录 */
    public void resetOnSuccess(String username, String ip) {
        try {
            redisTemplate.delete(KEY_USERNAME + username);
            redisTemplate.delete(KEY_IP + ip);
        } catch (Exception e) {
            log.warn("[限流] 清零失败（忽略）：{}", e.getMessage());
        }
    }

    /** INCR + 首次设置 TTL，返回当前计数 */
    private boolean overLimit(String key) {
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1) {
            redisTemplate.expire(key, WINDOW_SECONDS, TimeUnit.SECONDS);
        }
        return count != null && count > MAX_ATTEMPTS;
    }

    private void incr(String key) {
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1) {
            redisTemplate.expire(key, WINDOW_SECONDS, TimeUnit.SECONDS);
        }
    }
}
