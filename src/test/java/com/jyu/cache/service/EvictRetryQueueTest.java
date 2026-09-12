package com.jyu.cache.service;

import com.jyu.cache.common.SafeRedisTemplate;
import com.jyu.cache.config.CacheProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * EvictRetryQueue 单元测试（v3.4 新增）
 *
 * 覆盖：重试成功、失败退避、熔断期不消耗次数、超过上限放弃、同 key 去重、队列容量保护。
 * 用可控时钟验证退避，不依赖真实等待。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("缓存失效重试队列单元测试")
class EvictRetryQueueTest {

    private static final String KEY = "cache:product:1";

    @Mock private SafeRedisTemplate safeRedis;

    private SimpleMeterRegistry registry;
    private CacheProperties properties;
    private AtomicLong now;
    private EvictRetryQueue queue;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        properties = new CacheProperties();
        properties.setEvictRetryMaxAttempts(3);
        properties.setEvictRetryMaxEntries(2);
        now = new AtomicLong(1_000_000L);
        queue = new EvictRetryQueue(safeRedis, properties, registry, now::get);
        lenient().when(safeRedis.isCircuitOpen()).thenReturn(false);
    }

    private double counter(String result) {
        return registry.get("cache.evict.retry").tag("result", result).counter().count();
    }

    @Test
    @DisplayName("重试成功：出队 + 记 success 指标 + pending 归零")
    void retrySucceedsAndDequeues() {
        when(safeRedis.deleteQuietly(KEY)).thenReturn(true);

        queue.submit(KEY, "第二次删除失败");
        assertThat(queue.pendingCount()).isEqualTo(1);

        queue.drain();

        assertThat(queue.pendingCount()).isZero();
        assertThat(queue.getRetrySuccess()).isEqualTo(1);
        assertThat(counter("success")).isEqualTo(1.0);
        assertThat(registry.get("cache.evict.retry.pending").gauge().value()).isZero();
    }

    @Test
    @DisplayName("重试失败：留在队列 + 按退避推迟（时钟没到不会再打 Redis）")
    void retryFailureBacksOff() {
        when(safeRedis.deleteQuietly(KEY)).thenReturn(false);

        queue.submit(KEY, "第一次删除失败");
        queue.drain();                       // 第 1 次失败 -> 2s 后再试
        assertThat(queue.pendingCount()).isEqualTo(1);

        queue.drain();                       // 时钟没动：不应再发起删除
        verify(safeRedis, times(1)).deleteQuietly(KEY);

        now.addAndGet(2_500L);               // 越过退避窗口
        queue.drain();
        verify(safeRedis, times(2)).deleteQuietly(KEY);
        assertThat(queue.getExhausted()).isZero();
    }

    @Test
    @DisplayName("Redis 熔断中：不消耗重试次数、不发起删除，等恢复后继续")
    void circuitOpenDefersWithoutConsumingAttempts() {
        when(safeRedis.isCircuitOpen()).thenReturn(true);

        queue.submit(KEY, "第二次删除失败");
        queue.drain();
        now.addAndGet(10_000L);
        queue.drain();

        verify(safeRedis, never()).deleteQuietly(anyString());
        assertThat(queue.pendingCount()).isEqualTo(1);
        assertThat(queue.getExhausted()).isZero();

        // 熔断恢复后仍有机会重试成功
        when(safeRedis.isCircuitOpen()).thenReturn(false);
        when(safeRedis.deleteQuietly(KEY)).thenReturn(true);
        now.addAndGet(10_000L);
        queue.drain();
        assertThat(queue.getRetrySuccess()).isEqualTo(1);
    }

    @Test
    @DisplayName("超过最大重试次数：放弃并记 exhausted（最终由 TTL 收敛）")
    void givesUpAfterMaxAttempts() {
        when(safeRedis.deleteQuietly(KEY)).thenReturn(false);

        queue.submit(KEY, "第二次删除失败");
        for (int i = 0; i < 3; i++) {
            queue.drain();
            now.addAndGet(70_000L);          // 越过任何退避窗口
        }

        assertThat(queue.pendingCount()).isZero();
        assertThat(queue.getExhausted()).isEqualTo(1);
        assertThat(counter("exhausted")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("同一个 key 重复提交只排队一次（写路径可能并发提交同一 key）")
    void deduplicatesByKey() {
        queue.submit(KEY, "第一次删除失败");
        queue.submit(KEY, "第二次删除失败");

        assertThat(queue.pendingCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("队列满：丢弃新任务并记 dropped（保护内存）")
    void dropsWhenQueueFull() {
        queue.submit("cache:product:1", "失败");
        queue.submit("cache:product:2", "失败");
        queue.submit("cache:product:3", "失败");   // 上限 2

        assertThat(queue.pendingCount()).isEqualTo(2);
        assertThat(queue.getDropped()).isEqualTo(1);
        assertThat(counter("dropped")).isEqualTo(1.0);
    }
}
