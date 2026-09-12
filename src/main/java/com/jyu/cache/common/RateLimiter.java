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
 *   计数  = 仅在"登录失败"时 INCR（首次 INCR 时设置窗口 TTL）
 *   超限  = 抛 429，前端提示"尝试过于频繁"
 *
 * 【重要修正】检查必须是只读的：
 *   早期实现把 INCR 放在 overLimit() 里，于是"检查一次 + 失败一次"= 每次失败计数 +2，
 *   声明"60 秒 5 次"实际第 4 次就 429。现在 check 只 GET 不 INCR，阈值语义与文档一致。
 *
 * TTL 自愈：INCR 与 EXPIRE 是两条命令，若 EXPIRE 失败会留下"永不过期"的计数键，
 *   该用户名/IP 会被永久 429。所以非首次计数时顺带检查 TTL，发现 -1 立即补设。
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
    private final RedisCircuitBreaker breaker;

    public RateLimiter(RedisTemplate<String, Object> redisTemplate, RedisCircuitBreaker breaker) {
        this.redisTemplate = redisTemplate;
        this.breaker = breaker;
    }

    /**
     * 登录前检查：用户名或 IP 任一维度超限则抛 429
     * Redis 故障/熔断时放行（限流是安全增强，不能因它故障而拒绝所有用户登录）
     */
    public void checkLoginAllowed(String username, String ip) {
        if (breaker.isOpen()) {
            log.warn("[限流] Redis 熔断中，本次放行（不阻断登录主流程）");
            return;
        }
        try {
            if (overLimit(KEY_USERNAME + username, MAX_ATTEMPTS)) {
                log.warn("[限流] 用户名 {} 登录尝试过于频繁", username);
                throw new com.jyu.cache.common.BusinessException(429, "登录尝试过于频繁，请 1 分钟后再试");
            }
            if (overLimit(KEY_IP + ip, MAX_ATTEMPTS)) {
                log.warn("[限流] IP {} 登录尝试过于频繁", ip);
                throw new com.jyu.cache.common.BusinessException(429, "该 IP 登录尝试过于频繁，请 1 分钟后再试");
            }
            breaker.recordSuccess();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            breaker.recordFailure("限流检查 -> " + e.getMessage());
            log.warn("[限流] Redis 不可用，本次放行：{}", e.getMessage());
        }
    }

    /** 登录失败后记账：两个维度各 +1 */
    public void recordLoginFailure(String username, String ip) {
        if (breaker.isOpen()) {
            log.debug("[限流] Redis 熔断中，跳过失败记账");
            return;
        }
        try {
            incr(KEY_USERNAME + username);
            incr(KEY_IP + ip);
            breaker.recordSuccess();
        } catch (Exception e) {
            breaker.recordFailure("限流记账 -> " + e.getMessage());
            log.warn("[限流] 记录失败次数异常（忽略）：{}", e.getMessage());
        }
    }

    /** 登录成功后清零，避免误伤后续正常登录 */
    public void resetOnSuccess(String username, String ip) {
        if (breaker.isOpen()) {
            log.debug("[限流] Redis 熔断中，跳过清零（计数键到期自然消失）");
            return;
        }
        try {
            redisTemplate.delete(KEY_USERNAME + username);
            redisTemplate.delete(KEY_IP + ip);
            breaker.recordSuccess();
        } catch (Exception e) {
            breaker.recordFailure("限流清零 -> " + e.getMessage());
            log.warn("[限流] 清零失败（忽略）：{}", e.getMessage());
        }
    }

    /** 只读检查：计数达到上限即拒绝（不递增，否则每次失败会被计两次、阈值腰斩） */
    private boolean overLimit(String key, int maxAttempts) {
        Object value = redisTemplate.opsForValue().get(key);
        if (value == null) {
            return false;
        }
        try {
            return Long.parseLong(String.valueOf(value)) >= maxAttempts;
        } catch (NumberFormatException e) {
            log.warn("[限流] 计数键 {} 的值不是数字（{}），按未超限处理", key, value);
            return false;
        }
    }

    // ================================================================
    // 下单限流（v3.4）：按用户维度限制下单频率
    // 为什么要加：下单会真扣库存 + 写订单表，是唯一有"写放大"的用户入口；
    //            刷单会同时打满 DB 连接和库存行锁，比登录爆破更伤系统。
    // ================================================================

    private static final String KEY_ORDER = "order:rate:";

    /** 窗口内允许的最大下单次数 */
    private static final int ORDER_MAX_PER_WINDOW = 10;

    /** 下单前检查（Redis 故障/熔断时放行：下单是核心业务，不能因限流组件故障而不可用） */
    public void checkOrderAllowed(Long userId) {
        if (breaker.isOpen()) {
            log.warn("[限流] Redis 熔断中，下单检查放行");
            return;
        }
        try {
            if (overLimit(KEY_ORDER + userId, ORDER_MAX_PER_WINDOW)) {
                log.warn("[限流] 用户 {} 下单过于频繁", userId);
                throw new BusinessException(429, "下单过于频繁，请稍后再试");
            }
            breaker.recordSuccess();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            breaker.recordFailure("下单限流检查 -> " + e.getMessage());
            log.warn("[限流] Redis 不可用，下单检查放行：{}", e.getMessage());
        }
    }

    /** 下单成功后记账 */
    public void recordOrderPlaced(Long userId) {
        if (breaker.isOpen()) {
            log.debug("[限流] Redis 熔断中，跳过下单记账");
            return;
        }
        try {
            incr(KEY_ORDER + userId);
            breaker.recordSuccess();
        } catch (Exception e) {
            breaker.recordFailure("下单记账 -> " + e.getMessage());
            log.warn("[限流] 下单记账失败（忽略）：{}", e.getMessage());
        }
    }

    /** 失败记账：INCR + 窗口 TTL（首次设置；异常残留时自愈补设） */
    private void incr(String key) {
        Long count = redisTemplate.opsForValue().increment(key);
        if (count == null) {
            return;
        }
        if (count == 1) {
            redisTemplate.expire(key, WINDOW_SECONDS, TimeUnit.SECONDS);
            return;
        }
        // 自愈：无 TTL（= -1）说明此前 EXPIRE 没生效，补上，避免永久 429
        Long ttl = redisTemplate.getExpire(key);
        if (ttl != null && ttl < 0) {
            log.warn("[限流] 计数键 {} 缺少 TTL，补设 {}s 窗口", key, WINDOW_SECONDS);
            redisTemplate.expire(key, WINDOW_SECONDS, TimeUnit.SECONDS);
        }
    }
}
