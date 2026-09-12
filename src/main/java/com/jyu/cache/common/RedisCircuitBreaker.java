package com.jyu.cache.common;

import com.jyu.cache.config.CacheProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * Redis 本地熔断器（v3.2 新增）
 *
 * 【为什么需要它】实测（docker compose stop redis）发现：Redis 挂掉后单次商品查询要
 * **28.8 秒**才降级返回。因为一次请求会串行撞 5 次注定失败的 Redis 调用
 * （缓存 GET、recordDegrade、Redisson tryLock、recordMiss、热度 ZINCRBY），
 * 每次都各自等自己的连接/命令超时。可用性在，但延迟上等同不可用。
 *
 * 【策略】进程内熔断（不引入 Resilience4j 这类额外依赖，本项目的规模够用）：
 *   - 连续失败达到阈值 -> 打开熔断 openMs；
 *   - 打开期间：所有 Redis 操作**不发起**，直接走降级路径（读 DB），延迟回到毫秒级；
 *   - 冷却结束后进入半开：只放行**一个**探测请求，成功则关闭熔断，失败则立刻重新打开。
 *
 * 【取舍】阈值可配（cache.breaker-failure-threshold）。因为降级路径永远是"直查 DB"这个
 * 正确结果，所以宁可激进一点（默认 1 次失败即熔断）也不让用户等 5 秒超时。
 * 这不是"缓存雪崩"的放大器：熔断只持续 openMs，且期间流量由 DB 兜住。
 */
@Slf4j
@Component
public class RedisCircuitBreaker {

    private final int failureThreshold;
    private final long openMs;
    private final LongSupplier clock;

    /** 连续失败计数（成功即清零） */
    private final AtomicInteger consecutiveFailures = new AtomicInteger();

    /** 熔断到期时间戳（0 = 未熔断） */
    private volatile long openUntil = 0L;

    /** 半开状态下是否已有探测请求在飞（保证同一时刻只探测一次） */
    private final AtomicBoolean probeInFlight = new AtomicBoolean(false);

    /** Spring 注入用（类里还有一个给测试的可控时钟构造器，所以要显式标注） */
    @Autowired
    public RedisCircuitBreaker(CacheProperties properties) {
        this(properties.getBreakerFailureThreshold(), properties.getBreakerOpenMs(), System::currentTimeMillis);
    }

    /** 供测试注入可控时钟 */
    public RedisCircuitBreaker(int failureThreshold, long openMs, LongSupplier clock) {
        this.failureThreshold = Math.max(1, failureThreshold);
        this.openMs = Math.max(0L, openMs);
        this.clock = clock;
    }

    /**
     * 是否放行本次 Redis 操作。
     *
     * @return true = 可以走 Redis；false = 熔断打开，调用方必须直接走降级（不发请求）
     */
    public boolean allowRequest() {
        long until = openUntil;
        if (until == 0L) {
            return true;              // 未熔断
        }
        if (clock.getAsLong() < until) {
            return false;             // 熔断中：直接降级
        }
        // 冷却结束 -> 半开：只放行一个探测请求，其余继续降级
        boolean granted = probeInFlight.compareAndSet(false, true);
        if (granted) {
            log.info("[熔断] 冷却结束，放行 1 个探测请求试探 Redis");
        }
        return granted;
    }

    /** 操作成功：清零计数并关闭熔断 */
    public void recordSuccess() {
        boolean wasOpen = openUntil != 0L;
        consecutiveFailures.set(0);
        openUntil = 0L;
        probeInFlight.set(false);
        if (wasOpen) {
            log.info("[熔断] 探测成功，Redis 恢复正常，关闭熔断");
        }
    }

    /** 操作失败：累计失败；达到阈值（或探测失败）则打开熔断 */
    public void recordFailure(String reason) {
        boolean probeFailed = probeInFlight.getAndSet(false);
        int failures = consecutiveFailures.incrementAndGet();
        if (probeFailed || failures >= failureThreshold) {
            consecutiveFailures.set(0);
            openUntil = clock.getAsLong() + openMs;
            log.warn("[熔断] Redis 操作失败（{}），熔断 {}ms：期间读路径直接降级查 DB，不再等待超时", reason, openMs);
        } else {
            log.warn("[熔断] Redis 操作失败 {}/{}（{}）", failures, failureThreshold, reason);
        }
    }

    /** 幂等释放半开探测令牌（放在 finally 里，保证令牌不会泄漏导致熔断永不关闭） */
    public void releaseProbe() {
        probeInFlight.set(false);
    }

    /** 当前是否处于熔断打开状态（供监控/日志使用） */
    public boolean isOpen() {
        long until = openUntil;
        return until != 0L && clock.getAsLong() < until;
    }

    /** 距熔断冷却结束还剩多少毫秒（未熔断返回 0） */
    public long openRemainingMs() {
        long until = openUntil;
        long now = clock.getAsLong();
        return until != 0L && now < until ? until - now : 0L;
    }
}
