package com.jyu.cache.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DistributedLock 单元测试：拿锁成功/失败/中断/Redis 故障四条路径
 */
@DisplayName("分布式锁单元测试")
class DistributedLockTest {

    private final RedissonClient redisson = mock(RedissonClient.class);
    private final RLock lock = mock(RLock.class);
    /** 阈值 1 + 极短冷却，便于在单测里验证"失败即熔断"与冷却后恢复 */
    private final RedisCircuitBreaker breaker = new RedisCircuitBreaker(1, 50L, System::currentTimeMillis);
    private final DistributedLock distributedLock = new DistributedLock(redisson, breaker);

    @Test
    @DisplayName("拿到锁：执行 action 并正确释放")
    void executesActionWhenLocked() throws Exception {
        when(redisson.getLock("lock:product:1")).thenReturn(lock);
        when(lock.tryLock(3000L, TimeUnit.MILLISECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        AtomicInteger ran = new AtomicInteger();

        String result = distributedLock.executeWithLock("product:1", 3000L,
                () -> { ran.incrementAndGet(); return "action"; },
                () -> "fallback");

        assertEquals("action", result);
        assertEquals(1, ran.get());
        verify(lock).unlock();
    }

    @Test
    @DisplayName("未拿到锁（等待超时）：降级执行 fallback")
    void fallsBackWhenLockNotAcquired() throws Exception {
        when(redisson.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(anyLong(), any(TimeUnit.class))).thenReturn(false);

        String result = distributedLock.executeWithLock("product:1", 3000L,
                () -> "action", () -> "fallback");

        assertEquals("fallback", result);
        verify(lock, never()).unlock();
    }

    @Test
    @DisplayName("获取锁被中断：恢复中断标记并走 fallback")
    void fallsBackOnInterrupt() throws Exception {
        when(redisson.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(anyLong(), any(TimeUnit.class))).thenThrow(new InterruptedException());

        String result = distributedLock.executeWithLock("product:1", 3000L,
                () -> "action", () -> "fallback");

        assertEquals("fallback", result);
        // 中断标记必须恢复（否则线程池里的线程状态被污染）
        org.junit.jupiter.api.Assertions.assertTrue(Thread.interrupted(), "中断标记应被恢复");
    }

    @Test
    @DisplayName("Redisson 连接故障：降级执行 fallback，不抛异常")
    void fallsBackOnRedissonFailure() {
        when(redisson.getLock(anyString())).thenThrow(new RuntimeException("redis down"));

        String result = distributedLock.executeWithLock("product:1", 3000L,
                () -> "action", () -> "fallback");

        assertEquals("fallback", result);
    }

    @Test
    @DisplayName("action 抛异常：降级执行 fallback（catch 兜底），锁仍被释放")
    void releasesLockWhenActionThrows() throws Exception {
        when(redisson.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        String result = distributedLock.executeWithLock("product:1", 3000L,
                () -> { throw new RuntimeException("boom"); },
                () -> "fallback");

        assertEquals("fallback", result);
        verify(lock).unlock();   // finally 中锁必须释放
    }

    // ==================== 定时任务多实例互斥（v3.3） ====================

    @Test
    @DisplayName("定时任务：抢到租约锁 -> 执行任务；**不主动释放**（租约到期才放，防相位不同的实例重复执行）")
    void tryExecuteOnce_runsTaskAndHoldsLease() throws Exception {
        when(redisson.getLock("lock:scheduled:auto-preheat")).thenReturn(lock);
        when(lock.tryLock(0L, 50_000L, TimeUnit.MILLISECONDS)).thenReturn(true);
        AtomicInteger runs = new AtomicInteger();

        boolean executed = distributedLock.tryExecuteOnce("auto-preheat", 50_000L, false, runs::incrementAndGet);

        assertEquals(true, executed);
        assertEquals(1, runs.get());
        // 关键回归：跑完不能 unlock，否则 60 秒定时器相位不同的另一实例会立刻抢到空锁再跑一遍
        verify(lock, never()).unlock();
    }

    @Test
    @DisplayName("定时任务：别的实例已持有锁 -> 跳过本轮，不执行任务")
    void tryExecuteOnce_skipsWhenLockHeldByOtherInstance() throws Exception {
        when(redisson.getLock("lock:scheduled:auto-preheat")).thenReturn(lock);
        when(lock.tryLock(0L, 50_000L, TimeUnit.MILLISECONDS)).thenReturn(false);
        AtomicInteger runs = new AtomicInteger();

        boolean executed = distributedLock.tryExecuteOnce("auto-preheat", 50_000L, false, runs::incrementAndGet);

        assertEquals(false, executed);
        assertEquals(0, runs.get(), "没抢到锁就不该执行任务");
        verify(lock, never()).unlock();
    }

    @Test
    @DisplayName("定时任务：Redis 故障 + fail-open -> 仍然执行（订单超时扫描不能停）")
    void tryExecuteOnce_failOpenStillRunsWhenRedisDown() {
        when(redisson.getLock(anyString())).thenThrow(new RuntimeException("redis down"));
        AtomicInteger runs = new AtomicInteger();

        boolean executed = distributedLock.tryExecuteOnce("order-timeout-scan", 50_000L, true, runs::incrementAndGet);

        assertEquals(true, executed);
        assertEquals(1, runs.get());
    }

    @Test
    @DisplayName("定时任务：Redis 故障 + fail-closed -> 跳过本轮（预热可以等下一轮）")
    void tryExecuteOnce_failClosedSkipsWhenRedisDown() {
        when(redisson.getLock(anyString())).thenThrow(new RuntimeException("redis down"));
        AtomicInteger runs = new AtomicInteger();

        boolean executed = distributedLock.tryExecuteOnce("auto-preheat", 50_000L, false, runs::incrementAndGet);

        assertEquals(false, executed);
        assertEquals(0, runs.get());
    }

    @Test
    @DisplayName("定时任务：熔断打开时不再去撞 Redis（按 fail-open/fail-closed 分别处理）")
    void tryExecuteOnce_respectsCircuitBreaker() {
        breaker.recordFailure("redis down");   // 阈值=1，立即熔断
        AtomicInteger runs = new AtomicInteger();

        assertEquals(true, distributedLock.tryExecuteOnce("order-timeout-scan", 50_000L, true, runs::incrementAndGet));
        assertEquals(false, distributedLock.tryExecuteOnce("auto-preheat", 50_000L, false, runs::incrementAndGet));
        assertEquals(1, runs.get());
        // 熔断期间不应真的去拿锁
        verify(redisson, never()).getLock(anyString());
    }
}
