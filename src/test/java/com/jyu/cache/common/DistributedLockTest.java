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
    private final DistributedLock distributedLock = new DistributedLock(redisson);

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
}
