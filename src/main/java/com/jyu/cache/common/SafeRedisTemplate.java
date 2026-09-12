package com.jyu.cache.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Redis 安全操作工具（v3 新增 / v3.2 接入本地熔断）
 *
 * 解决 v2 的两个架构级问题：
 *
 * 一、KEYS -> SCAN
 *   v2 用 redisTemplate.keys("cache:product:*")，KEYS 是 O(N) 全库扫描并阻塞 Redis 主线程，
 *   key 上百万时生产 Redis 会直接卡死。
 *   v3 改用 SCAN 游标迭代：每次只扫一小批（count=500），分多次取完，不阻塞。
 *
 * 二、降级执行（degrade）
 *   v2 中 Redis 一挂，商品查询直接 500。
 *   v3 提供 degrade(action, fallback)：Redis 动作抛异常时自动走 fallback（通常是直查 DB），
 *   把 Redis 从"硬依赖"降级为"弱依赖"。
 *
 * 三、本地熔断（v3.2）
 *   光有降级还不够：Redis 挂掉后每次调用都要各自等连接/命令超时，实测单请求 28.8 秒才返回。
 *   现在所有操作都先问 RedisCircuitBreaker.allowRequest()：
 *   熔断打开期间**不发起** Redis 调用，直接走 fallback，延迟回到毫秒级。
 */
@Slf4j
@Component
public class SafeRedisTemplate {

    private final RedisTemplate<String, Object> redisTemplate;
    private final RedisCircuitBreaker breaker;

    public SafeRedisTemplate(RedisTemplate<String, Object> redisTemplate, RedisCircuitBreaker breaker) {
        this.redisTemplate = redisTemplate;
        this.breaker = breaker;
    }

    /** 原始模板（供确需直接操作的场景使用；注意它**不**带降级与熔断保护） */
    public RedisTemplate<String, Object> raw() {
        return redisTemplate;
    }

    /** 熔断是否打开（供其它组件做快速短路判断） */
    public boolean isCircuitOpen() {
        return breaker.isOpen();
    }

    /**
     * SCAN 增量扫描匹配 pattern 的所有 key（替代 KEYS）
     *
     * @param pattern key 匹配模式，如 "cache:product:*"
     * @return 匹配到的 key 列表（Redis 不可用/熔断中返回空列表，不抛异常）
     */
    public List<String> scanKeys(String pattern) {
        List<String> keys = new ArrayList<>();
        if (!breaker.allowRequest()) {
            log.debug("[SafeRedis] 熔断中，SCAN 直接返回空列表 pattern={}", pattern);
            return keys;
        }
        try {
            ScanOptions options = ScanOptions.scanOptions().match(pattern).count(500).build();
            try (Cursor<String> cursor = redisTemplate.scan(options)) {
                while (cursor.hasNext()) {
                    keys.add(cursor.next());
                }
            }
            breaker.recordSuccess();
        } catch (Exception e) {
            breaker.recordFailure("SCAN " + pattern + " -> " + e.getMessage());
            log.warn("[SafeRedis] SCAN 扫描失败 pattern={}：{}", pattern, e.getMessage());
        } finally {
            breaker.releaseProbe();
        }
        return keys;
    }

    /**
     * 降级执行：Redis 动作失败（或熔断打开）时自动走 fallback
     *
     * 使用姿势：
     *   Product p = safeRedis.degrade(() -> getFromRedis(key), () -> getFromDb(id));
     *
     * @param action   主路径（走 Redis）
     * @param fallback 降级路径（Redis 故障/熔断时执行，通常是查库）
     */
    public <T> T degrade(Supplier<T> action, Supplier<T> fallback) {
        if (!breaker.allowRequest()) {
            log.debug("[SafeRedis] 熔断中，直接执行降级分支");
            return fallback.get();
        }
        try {
            T result = action.get();
            breaker.recordSuccess();
            return result;
        } catch (Exception e) {
            breaker.recordFailure(e.getMessage());
            log.warn("[SafeRedis] Redis 操作失败，降级执行：{}", e.getMessage());
            return fallback.get();
        } finally {
            breaker.releaseProbe();
        }
    }

    /**
     * 静默执行：Redis 动作失败只记日志不上抛（用于预热/统计等"锦上添花"操作）
     * 熔断打开时直接跳过，不发起请求。
     */
    public void tryRun(Runnable action) {
        if (!breaker.allowRequest()) {
            log.debug("[SafeRedis] 熔断中，跳过本次 Redis 操作");
            return;
        }
        try {
            action.run();
            breaker.recordSuccess();
        } catch (Exception e) {
            breaker.recordFailure(e.getMessage());
            log.warn("[SafeRedis] Redis 操作失败（忽略）：{}", e.getMessage());
        } finally {
            breaker.releaseProbe();
        }
    }

    /**
     * 带熔断的删除：返回是否成功，失败由调用方自行记账（延迟双删需要区分成败）
     * 熔断打开时直接返回 false，不发起请求。
     */
    public boolean deleteQuietly(String key) {
        if (!breaker.allowRequest()) {
            log.debug("[SafeRedis] 熔断中，跳过删除 key={}", key);
            return false;
        }
        try {
            redisTemplate.delete(key);
            breaker.recordSuccess();
            return true;
        } catch (Exception e) {
            breaker.recordFailure(e.getMessage());
            log.warn("[SafeRedis] 删除失败 key={}：{}", key, e.getMessage());
            return false;
        } finally {
            breaker.releaseProbe();
        }
    }
}
