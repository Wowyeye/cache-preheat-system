package com.jyu.cache.service;

import com.jyu.cache.common.SafeRedisTemplate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * 缓存监控统计服务（v3 重写：指标从 JVM 内存迁到 Redis）
 *
 * v2 问题：hitCount/missCount 等指标存 AtomicLong（JVM 内存）：
 *   1. 多实例部署时各算各的，统计口径分裂；
 *   2. 应用重启即归零（实测重启后 hitCount 从 5 变 2）。
 *
 * v3 方案：全部指标存 Redis Hash，字段原子 INCR：
 *   key   = cache:stats（HINCRBY 原子递增）
 *   字段  = hit / miss / total / cachePathNanos / cachePathCount / dbPathNanos / dbPathCount
 *   效果  = 多实例天然聚合（同一个 Redis），重启不丢，监控大盘口径统一。
 *
 * 性能说明：每次读请求多 1~2 次 HINCRBY（本地内存 -> Redis 网络往返）。
 * 若未来 QPS 到数万级，可改为"本地累积 + 定期批量刷 Redis"的聚合模式，
 * 当前演示规模（教学/答辩）下直接写 Redis 最简单、最不容易出错。
 */
@Slf4j
@Service
public class CacheStatsService {

    private static final String STATS_KEY = "cache:stats";

    private final RedisTemplate<String, Object> redisTemplate;
    private final SafeRedisTemplate safeRedis;

    public CacheStatsService(RedisTemplate<String, Object> redisTemplate, SafeRedisTemplate safeRedis) {
        this.redisTemplate = redisTemplate;
        this.safeRedis = safeRedis;
    }

    /** 记录一次命中（含命中路径耗时，纳秒） */
    public void recordHit(long elapsedNanos) {
        safeRedis.tryRun(() -> {
            redisTemplate.opsForHash().increment(STATS_KEY, "hit", 1);
            redisTemplate.opsForHash().increment(STATS_KEY, "cachePathNanos", elapsedNanos);
            redisTemplate.opsForHash().increment(STATS_KEY, "cachePathCount", 1);
        });
    }

    /** 记录一次未命中（含回源路径耗时，纳秒） */
    public void recordMiss(long elapsedNanos) {
        safeRedis.tryRun(() -> {
            redisTemplate.opsForHash().increment(STATS_KEY, "miss", 1);
            redisTemplate.opsForHash().increment(STATS_KEY, "dbPathNanos", elapsedNanos);
            redisTemplate.opsForHash().increment(STATS_KEY, "dbPathCount", 1);
        });
    }

    /** 记录穿透命中（命中空值标记，计入 hit） */
    public void recordNullHit(long elapsedNanos) {
        recordHit(elapsedNanos);
    }

    /** 记录一次降级（Redis 故障直查 DB） */
    public void recordDegrade() {
        safeRedis.tryRun(() -> redisTemplate.opsForHash().increment(STATS_KEY, "degrade", 1));
    }

    /** 记录一次锁竞争降级（未拿到分布式锁直接回源） */
    public void recordLockFallback() {
        safeRedis.tryRun(() -> redisTemplate.opsForHash().increment(STATS_KEY, "lockFallback", 1));
    }

    /**
     * 读取全部统计指标
     * Redis 不可用时返回带 degraded=true 的空指标，监控页不至于 500
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>(12);
        try {
            Map<Object, Object> entries = redisTemplate.opsForHash().entries(STATS_KEY);
            long hit = toLong(entries.get("hit"));
            long miss = toLong(entries.get("miss"));
            long cNanos = toLong(entries.get("cachePathNanos"));
            long cCount = toLong(entries.get("cachePathCount"));
            long dNanos = toLong(entries.get("dbPathNanos"));
            long dCount = toLong(entries.get("dbPathCount"));

            stats.put("hitCount", hit);
            stats.put("missCount", miss);
            stats.put("totalRequests", hit + miss);
            stats.put("hitRate", hit + miss == 0 ? "0.00%" : String.format("%.2f%%", hit * 100.0 / (hit + miss)));
            stats.put("cacheAvgMs", cCount == 0 ? 0 : Math.round(cNanos / 1000000.0 / cCount * 100) / 100.0);
            stats.put("dbAvgMs", dCount == 0 ? 0 : Math.round(dNanos / 1000000.0 / dCount * 100) / 100.0);
            stats.put("cachePathCount", cCount);
            stats.put("dbPathCount", dCount);
            stats.put("degradeCount", toLong(entries.get("degrade")));
            stats.put("lockFallbackCount", toLong(entries.get("lockFallback")));
            stats.put("degraded", false);
        } catch (Exception e) {
            log.warn("[缓存统计] 读取失败（Redis 不可用？）：{}", e.getMessage());
            stats.put("degraded", true);
            stats.put("hitCount", 0);
            stats.put("missCount", 0);
            stats.put("hitRate", "0.00%");
        }
        return stats;
    }

    /** 清零全部指标（管理员"重置统计"用） */
    public void reset() {
        safeRedis.tryRun(() -> redisTemplate.delete(STATS_KEY));
    }

    private long toLong(Object v) {
        if (v == null) {
            return 0L;
        }
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
