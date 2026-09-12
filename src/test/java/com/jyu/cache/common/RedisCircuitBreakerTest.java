package com.jyu.cache.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RedisCircuitBreaker 单元测试（v3.2 新增）
 *
 * 用可控时钟验证状态机：关闭 -> 达到阈值打开 -> 冷却后半开（只放行一个探测）
 * -> 探测成功则关闭 / 探测失败则立刻重新打开。
 */
@DisplayName("Redis 本地熔断器单元测试")
class RedisCircuitBreakerTest {

    /** 可控时钟，避免依赖真实时间 */
    private static final class FakeClock implements java.util.function.LongSupplier {
        private final AtomicLong now = new AtomicLong(1_000L);

        @Override
        public long getAsLong() {
            return now.get();
        }

        void advance(long ms) {
            now.addAndGet(ms);
        }
    }

    @Test
    @DisplayName("初始状态：关闭，允许请求")
    void startsClosed() {
        RedisCircuitBreaker breaker = new RedisCircuitBreaker(3, 1000L, new FakeClock());

        assertFalse(breaker.isOpen());
        assertTrue(breaker.allowRequest());
        assertEquals(0L, breaker.openRemainingMs());
    }

    @Test
    @DisplayName("未达阈值：仍允许请求（容忍偶发抖动）")
    void staysClosedBelowThreshold() {
        RedisCircuitBreaker breaker = new RedisCircuitBreaker(3, 1000L, new FakeClock());

        breaker.recordFailure("boom-1");
        breaker.recordFailure("boom-2");

        assertFalse(breaker.isOpen());
        assertTrue(breaker.allowRequest());
    }

    @Test
    @DisplayName("达到阈值：打开熔断，期间 allowRequest 一律 false")
    void opensAtThreshold() {
        FakeClock clock = new FakeClock();
        RedisCircuitBreaker breaker = new RedisCircuitBreaker(2, 1000L, clock);

        breaker.recordFailure("boom-1");
        breaker.recordFailure("boom-2");

        assertTrue(breaker.isOpen());
        assertFalse(breaker.allowRequest());
        assertEquals(1000L, breaker.openRemainingMs());
    }

    @Test
    @DisplayName("成功一次即清零计数（不会攒失败）")
    void successResetsFailureCount() {
        RedisCircuitBreaker breaker = new RedisCircuitBreaker(2, 1000L, new FakeClock());

        breaker.recordFailure("boom-1");
        breaker.recordSuccess();
        breaker.recordFailure("boom-2");

        assertFalse(breaker.isOpen(), "中间成功过，两次失败不应累加触发熔断");
    }

    @Test
    @DisplayName("冷却结束：半开，只放行一个探测请求")
    void halfOpenAllowsSingleProbe() {
        FakeClock clock = new FakeClock();
        RedisCircuitBreaker breaker = new RedisCircuitBreaker(1, 1000L, clock);
        breaker.recordFailure("boom");
        assertFalse(breaker.allowRequest(), "熔断中不放行");

        clock.advance(1500L);

        assertTrue(breaker.allowRequest(), "冷却后第一个请求作为探测放行");
        assertFalse(breaker.allowRequest(), "同一时刻只放行一个探测");
    }

    @Test
    @DisplayName("探测成功 -> 关闭熔断并恢复放行")
    void probeSuccessClosesCircuit() {
        FakeClock clock = new FakeClock();
        RedisCircuitBreaker breaker = new RedisCircuitBreaker(1, 1000L, clock);
        breaker.recordFailure("boom");
        clock.advance(1500L);
        assertTrue(breaker.allowRequest());

        breaker.recordSuccess();
        breaker.releaseProbe();

        assertFalse(breaker.isOpen());
        assertTrue(breaker.allowRequest());
    }

    @Test
    @DisplayName("探测失败 -> 立刻重新打开熔断（不会让每个请求都去撞超时）")
    void probeFailureReopensImmediately() {
        FakeClock clock = new FakeClock();
        RedisCircuitBreaker breaker = new RedisCircuitBreaker(2, 1000L, clock);
        breaker.recordFailure("boom-1");
        breaker.recordFailure("boom-2");
        assertTrue(breaker.isOpen());

        clock.advance(1500L);
        assertTrue(breaker.allowRequest());     // 半开探测
        breaker.recordFailure("probe failed");  // 探测失败

        assertTrue(breaker.isOpen(), "探测失败应立刻重开，而不是再等阈值次失败");
        assertEquals(1000L, breaker.openRemainingMs());
    }

    @Test
    @DisplayName("探测令牌一定会被释放（finally 调用 releaseProbe），不会永久熔断")
    void releaseProbePreventsStuckOpen() {
        FakeClock clock = new FakeClock();
        RedisCircuitBreaker breaker = new RedisCircuitBreaker(1, 1000L, clock);
        breaker.recordFailure("boom");
        clock.advance(1500L);

        assertTrue(breaker.allowRequest());   // 拿到探测令牌
        breaker.releaseProbe();               // 调用方在 finally 里释放（异常路径也要释放）

        assertTrue(breaker.allowRequest(), "令牌释放后应能继续放行");
    }
}
