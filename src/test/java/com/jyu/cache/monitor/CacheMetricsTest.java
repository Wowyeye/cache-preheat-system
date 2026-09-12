package com.jyu.cache.monitor;

import com.jyu.cache.common.RedisCircuitBreaker;
import com.jyu.cache.service.DelayDeleteService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CacheMetrics 单元测试（v3.4 新增）
 *
 * 用 SimpleMeterRegistry 断言"指标真的被记录/暴露"，而不是只看代码里有没有写 increment。
 */
@DisplayName("缓存指标（Micrometer）单元测试")
class CacheMetricsTest {

    private SimpleMeterRegistry registry;
    private RedisCircuitBreaker breaker;
    private DelayDeleteService delayDeleteService;
    private CacheMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        breaker = new RedisCircuitBreaker(1, 50L, System::currentTimeMillis);   // 阈值 1，便于验证熔断指标
        delayDeleteService = mock(DelayDeleteService.class);
        metrics = new CacheMetrics(registry, breaker, delayDeleteService);
    }

    private double counter(String result) {
        return registry.get("cache.access").tag("result", result).counter().count();
    }

    @Test
    @DisplayName("访问结果分维度计数：hit / miss / null_hit / degrade / lock_fallback")
    void recordsAccessResultsByTag() {
        metrics.recordHit(1_000_000L);
        metrics.recordHit(2_000_000L);
        metrics.recordMiss(5_000_000L);
        metrics.recordNullHit(500_000L);
        metrics.recordDegrade();
        metrics.recordLockFallback();

        assertThat(counter("hit")).isEqualTo(2.0);
        assertThat(counter("miss")).isEqualTo(1.0);
        assertThat(counter("null_hit")).isEqualTo(1.0);
        assertThat(counter("degrade")).isEqualTo(1.0);
        assertThat(counter("lock_fallback")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("两条路径的耗时各自计时（cache / db），次数与命中未命中一致")
    void recordsPathTimers() {
        metrics.recordHit(3_000_000L);
        metrics.recordMiss(9_000_000L);

        assertThat(registry.get("cache.path").tag("path", "cache").timer().count()).isEqualTo(1L);
        assertThat(registry.get("cache.path").tag("path", "db").timer().count()).isEqualTo(1L);
        assertThat(registry.get("cache.path").tag("path", "cache").timer().totalTime(java.util.concurrent.TimeUnit.MILLISECONDS))
                .isGreaterThan(0.0);
    }

    @Test
    @DisplayName("熔断指标反映真实状态：关闭 0 -> 打开 1 -> 冷却后回 0")
    void circuitGaugeFollowsBreakerState() {
        assertThat(registry.get("cache.circuit.open").gauge().value()).isEqualTo(0.0);

        breaker.recordFailure("redis down");   // 阈值 1，立即熔断
        assertThat(registry.get("cache.circuit.open").gauge().value()).isEqualTo(1.0);
        assertThat(registry.get("cache.circuit.open.remaining.ms").gauge().value()).isGreaterThan(0.0);

        breaker.recordSuccess();
        assertThat(registry.get("cache.circuit.open").gauge().value()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("双删失败累计作为 gauge 暴露（phase=first/second），暴露一致性缺口")
    void deleteFailuresExposedAsGauge() {
        when(delayDeleteService.getFirstDeleteFailures()).thenReturn(2L);
        when(delayDeleteService.getSecondDeleteFailures()).thenReturn(3L);

        assertThat(registry.get("cache.delete.failures").tag("phase", "first").gauge().value()).isEqualTo(2.0);
        assertThat(registry.get("cache.delete.failures").tag("phase", "second").gauge().value()).isEqualTo(3.0);
    }
}
