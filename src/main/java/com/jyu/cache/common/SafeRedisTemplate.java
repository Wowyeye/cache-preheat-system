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
 * Redis 安全操作工具（v3 新增）
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
 *   把 Redis 从"硬依赖"降级为"弱依赖"——缓存挂了系统仍可用，只是慢。
 */
@Slf4j
@Component
public class SafeRedisTemplate {

    private final RedisTemplate<String, Object> redisTemplate;

    public SafeRedisTemplate(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /** 原始模板（供确需直接操作的场景使用） */
    public RedisTemplate<String, Object> raw() {
        return redisTemplate;
    }

    /**
     * SCAN 增量扫描匹配 pattern 的所有 key（替代 KEYS）
     *
     * @param pattern key 匹配模式，如 "cache:product:*"
     * @return 匹配到的 key 列表（Redis 不可用时返回空列表，不抛异常）
     */
    public List<String> scanKeys(String pattern) {
        List<String> keys = new ArrayList<>();
        try {
            ScanOptions options = ScanOptions.scanOptions().match(pattern).count(500).build();
            try (Cursor<String> cursor = redisTemplate.scan(options)) {
                while (cursor.hasNext()) {
                    keys.add(cursor.next());
                }
            }
        } catch (Exception e) {
            log.warn("[SafeRedis] SCAN 扫描失败 pattern={}：{}", pattern, e.getMessage());
        }
        return keys;
    }

    /**
     * 降级执行：Redis 动作失败时自动走 fallback
     *
     * 使用姿势：
     *   Product p = safeRedis.degrade(() -> getFromRedis(key), () -> getFromDb(id));
     *
     * @param action   主路径（走 Redis）
     * @param fallback 降级路径（Redis 故障时执行，通常是查库）
     */
    public <T> T degrade(Supplier<T> action, Supplier<T> fallback) {
        try {
            return action.get();
        } catch (Exception e) {
            log.warn("[SafeRedis] Redis 操作失败，降级执行：{}", e.getMessage());
            return fallback.get();
        }
    }

    /**
     * 静默执行：Redis 动作失败只记日志不上抛（用于预热/统计等"锦上添花"操作）
     */
    public void tryRun(Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            log.warn("[SafeRedis] Redis 操作失败（忽略）：{}", e.getMessage());
        }
    }
}
