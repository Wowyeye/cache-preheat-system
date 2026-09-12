package com.jyu.cache.common;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;

import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SafeRedisTemplate 单元测试（v3.1 补齐 / v3.2 加熔断）
 *
 * 这里不再 mock SafeRedisTemplate 本身，而是直接验证它的真实行为：
 * 降级到底有没有生效、SCAN 失败会不会抛出去、异常会不会被吞、
 * 以及熔断打开后是否真的**不再发起** Redis 调用。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Redis 安全操作（降级 / SCAN / 熔断）单元测试")
class SafeRedisTemplateTest {

    @Mock private RedisTemplate<String, Object> redisTemplate;

    private RedisCircuitBreaker breaker;
    private SafeRedisTemplate safeRedis;

    @BeforeEach
    void setUp() {
        // 阈值 1、冷却 50ms：便于验证"失败即熔断"和冷却后自动恢复
        breaker = new RedisCircuitBreaker(1, 50L, System::currentTimeMillis);
        safeRedis = new SafeRedisTemplate(redisTemplate, breaker);
    }

    @Test
    @DisplayName("降级：Redis 动作正常时返回主路径结果，不执行 fallback")
    void degrade_returnsActionResult() {
        Supplier<String> fallback = () -> {
            throw new IllegalStateException("fallback 不该被执行");
        };

        assertEquals("from-redis", safeRedis.degrade(() -> "from-redis", fallback));
    }

    @Test
    @DisplayName("降级：Redis 动作抛异常时执行 fallback（弱依赖，不 500）")
    void degrade_fallsBackOnException() {
        String result = safeRedis.degrade(
                () -> {
                    throw new org.springframework.data.redis.RedisConnectionFailureException("redis down");
                },
                () -> "from-db");

        assertEquals("from-db", result);
    }

    @Test
    @DisplayName("降级：RuntimeException 之外的错误型异常同样走 fallback")
    void degrade_fallsBackOnRuntimeException() {
        String result = safeRedis.degrade(
                () -> {
                    throw new IllegalArgumentException("bad value");
                },
                () -> "from-db");

        assertEquals("from-db", result);
    }

    @Test
    @DisplayName("静默执行：动作异常被吞掉，不上抛（预热/统计等非关键路径）")
    void tryRun_swallowsException() {
        safeRedis.tryRun(() -> {
            throw new RuntimeException("redis down");
        });
        // 走到这里就说明没有上抛
        assertTrue(true);
    }

    @Test
    @DisplayName("静默执行：正常动作会被执行")
    void tryRun_executesAction() {
        StringBuilder flag = new StringBuilder();
        safeRedis.tryRun(() -> flag.append("ran"));

        assertEquals("ran", flag.toString());
    }

    @Test
    @DisplayName("SCAN：游标迭代收集全部匹配 key（不是 KEYS）")
    void scanKeys_collectsAllFromCursor() {
        @SuppressWarnings("unchecked")
        Cursor<String> cursor = mock(Cursor.class);
        when(cursor.hasNext()).thenReturn(true, true, false);
        when(cursor.next()).thenReturn("cache:product:1", "cache:product:2");
        when(redisTemplate.scan(any(ScanOptions.class))).thenReturn(cursor);

        List<String> keys = safeRedis.scanKeys("cache:product:*");

        assertEquals(List.of("cache:product:1", "cache:product:2"), keys);
        org.mockito.Mockito.verify(cursor).close();   // try-with-resources 要关闭游标
    }

    @Test
    @DisplayName("SCAN：Redis 不可用时返回空列表而不是抛异常")
    void scanKeys_returnsEmptyOnFailure() {
        when(redisTemplate.scan(any(ScanOptions.class)))
                .thenThrow(new org.springframework.data.redis.RedisConnectionFailureException("redis down"));

        assertTrue(safeRedis.scanKeys("cache:product:*").isEmpty());
    }

    @Test
    @DisplayName("raw()：暴露底层模板，供确有需要的场景使用")
    void raw_returnsUnderlyingTemplate() {
        assertSame(redisTemplate, safeRedis.raw());
    }

    // ==================== 熔断（v3.2） ====================

    @Test
    @DisplayName("熔断：失败后打开熔断，后续调用不再发起 Redis 请求（直接走降级）")
    void circuitOpensAfterFailure_thenSkipsRedis() {
        java.util.concurrent.atomic.AtomicInteger redisCalls = new java.util.concurrent.atomic.AtomicInteger();
        Supplier<String> failingAction = () -> {
            redisCalls.incrementAndGet();
            throw new org.springframework.data.redis.RedisConnectionFailureException("redis down");
        };

        assertEquals("from-db", safeRedis.degrade(failingAction, () -> "from-db"));
        assertTrue(breaker.isOpen(), "失败达到阈值后熔断应打开");

        // 熔断打开期间：action 不应再被执行（这正是把 28.8s 降到毫秒级的关键）
        assertEquals("from-db", safeRedis.degrade(failingAction, () -> "from-db"));
        assertEquals(1, redisCalls.get(), "熔断打开后不应再发起 Redis 请求");
    }

    @Test
    @DisplayName("熔断：tryRun 与 deleteQuietly 在熔断期间都直接跳过")
    void circuitOpen_skipsTryRunAndDelete() {
        safeRedis.degrade(() -> {
            throw new org.springframework.data.redis.RedisConnectionFailureException("redis down");
        }, () -> null);

        java.util.concurrent.atomic.AtomicInteger ran = new java.util.concurrent.atomic.AtomicInteger();
        safeRedis.tryRun(ran::incrementAndGet);

        assertEquals(0, ran.get(), "熔断期间 tryRun 应跳过");
        org.junit.jupiter.api.Assertions.assertFalse(safeRedis.deleteQuietly("cache:product:1"),
                "熔断期间删除应直接返回 false");
        org.mockito.Mockito.verify(redisTemplate, org.mockito.Mockito.never()).delete("cache:product:1");
    }

    @Test
    @DisplayName("熔断：冷却后放行探测，Redis 恢复则自动关闭熔断")
    void circuitClosesAfterCooldown_whenRedisRecovers() throws Exception {
        safeRedis.degrade(() -> {
            throw new org.springframework.data.redis.RedisConnectionFailureException("redis down");
        }, () -> null);
        assertTrue(breaker.isOpen());

        Thread.sleep(80L);   // 超过 50ms 冷却窗口

        assertEquals("ok", safeRedis.degrade(() -> "ok", () -> "from-db"));
        org.junit.jupiter.api.Assertions.assertFalse(breaker.isOpen(), "探测成功后熔断应关闭");
    }

    @Test
    @DisplayName("熔断：冷却后探测仍失败 -> 立刻重新打开熔断")
    void circuitReopensWhenProbeFails() throws Exception {
        safeRedis.degrade(() -> {
            throw new org.springframework.data.redis.RedisConnectionFailureException("down");
        }, () -> null);
        Thread.sleep(80L);

        safeRedis.degrade(() -> {
            throw new org.springframework.data.redis.RedisConnectionFailureException("still down");
        }, () -> null);

        assertTrue(breaker.isOpen(), "探测失败应立刻重新熔断");
    }
}
