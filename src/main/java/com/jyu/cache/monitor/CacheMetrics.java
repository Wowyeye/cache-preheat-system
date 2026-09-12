package com.jyu.cache.monitor;

import com.jyu.cache.common.RedisCircuitBreaker;
import com.jyu.cache.service.DelayDeleteService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 缓存指标（v3.4 新增）：把自研统计接到 Micrometer，最终由 /actuator/prometheus 暴露
 *
 * 【为什么要加】之前只有自研的 Redis Hash 统计（`/api/product/cache/stats`）：
 *   - 它是"业务视角"的聚合指标，重启不丢、多实例天然汇总，适合给监控大盘用；
 *   - 但它不是标准协议，Prometheus/Grafana 抓不到，也没有分位数、没有和 JVM/HTTP 指标并列。
 * 接入 Micrometer 之后：
 *   - `cache_access_total{result="hit|miss|null_hit|degrade|lock_fallback"}` 访问结果计数
 *   - `cache_path_seconds{path="cache|db"}` 两条路径的耗时（自动有 p50/p95/p99）
 *   - `cache_circuit_open` / `cache_circuit_open_remaining_ms` Redis 熔断状态
 *   - `cache_delete_failures{phase="first|second"}` 延迟双删失败累计（一致性缺口）
 * 这些和 JVM、HTTP、连接池指标一起就能在 Grafana 上拼出一块像样的看板。
 *
 * 设计取舍：计数**同时**写 Redis（给站内大盘）和 Micrometer（给 Prometheus），
 * 两套口径各自服务一个消费方；Micrometer 侧是各实例本地的单调计数，由 Prometheus 负责聚合。
 */
@Slf4j
@Component
public class CacheMetrics {

    private final Counter hitCounter;
    private final Counter missCounter;
    private final Counter nullHitCounter;
    private final Counter degradeCounter;
    private final Counter lockFallbackCounter;
    private final Timer cachePathTimer;
    private final Timer dbPathTimer;

    public CacheMetrics(MeterRegistry registry,
                        RedisCircuitBreaker circuitBreaker,
                        DelayDeleteService delayDeleteService) {

        this.hitCounter = Counter.builder("cache.access")
                .tag("result", "hit").description("缓存命中次数").register(registry);
        this.missCounter = Counter.builder("cache.access")
                .tag("result", "miss").description("缓存未命中并回源次数").register(registry);
        this.nullHitCounter = Counter.builder("cache.access")
                .tag("result", "null_hit").description("命中空值标记（穿透防护生效）次数").register(registry);
        this.degradeCounter = Counter.builder("cache.access")
                .tag("result", "degrade").description("Redis 不可用而降级直查 DB 次数").register(registry);
        this.lockFallbackCounter = Counter.builder("cache.access")
                .tag("result", "lock_fallback").description("未拿到分布式锁而降级直查 DB 次数").register(registry);

        this.cachePathTimer = Timer.builder("cache.path")
                .tag("path", "cache").description("走缓存路径的请求耗时").register(registry);
        this.dbPathTimer = Timer.builder("cache.path")
                .tag("path", "db").description("回源数据库路径的请求耗时").register(registry);

        // 熔断状态：1=打开（Redis 调用被短路，走降级），0=关闭
        Gauge.builder("cache.circuit.open", circuitBreaker, b -> b.isOpen() ? 1d : 0d)
                .description("Redis 本地熔断是否打开（1=打开）").register(registry);
        Gauge.builder("cache.circuit.open.remaining.ms", circuitBreaker, b -> (double) b.openRemainingMs())
                .description("距熔断冷却结束剩余毫秒").register(registry);

        // 延迟双删失败累计：一致性缺口的可观测出口
        Gauge.builder("cache.delete.failures", delayDeleteService, d -> (double) d.getFirstDeleteFailures())
                .tag("phase", "first").description("延迟双删第一次删除失败累计").register(registry);
        Gauge.builder("cache.delete.failures", delayDeleteService, d -> (double) d.getSecondDeleteFailures())
                .tag("phase", "second").description("延迟双删第二次删除失败累计").register(registry);

        log.info("[监控] 缓存指标已注册到 Micrometer（/actuator/prometheus）");
    }

    public void recordHit(long elapsedNanos) {
        hitCounter.increment();
        cachePathTimer.record(elapsedNanos, TimeUnit.NANOSECONDS);
    }

    public void recordMiss(long elapsedNanos) {
        missCounter.increment();
        dbPathTimer.record(elapsedNanos, TimeUnit.NANOSECONDS);
    }

    public void recordNullHit(long elapsedNanos) {
        nullHitCounter.increment();
        cachePathTimer.record(elapsedNanos, TimeUnit.NANOSECONDS);
    }

    public void recordDegrade() {
        degradeCounter.increment();
    }

    public void recordLockFallback() {
        lockFallbackCounter.increment();
    }
}
