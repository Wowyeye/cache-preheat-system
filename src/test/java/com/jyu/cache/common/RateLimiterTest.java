package com.jyu.cache.common;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RateLimiter 单元测试：固定窗口限流（60 秒 5 次失败）+ Redis 故障放行
 *
 * v3.1 回归重点：检查（checkLoginAllowed）必须是只读的。
 * 早期版本在检查里也 INCR，导致“检查 1 次 + 失败记 1 次”= 每次失败计数 +2，
 * 声明 5 次实际第 4 次就 429（已在运行实例上复现过）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("登录限流器单元测试")
class RateLimiterTest {

    private static final String USER_KEY = "login:rate:admin";
    private static final String IP_KEY = "login:rate:ip:1.2.3.4";

    @Mock private RedisTemplate<String, Object> redisTemplate;
    @Mock private ValueOperations<String, Object> valueOps;

    private RateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        // 阈值 1 + 极短冷却：默认语义（失败即熔断）在单测里可直接验证
        RedisCircuitBreaker breaker = new RedisCircuitBreaker(1, 50L, System::currentTimeMillis);
        rateLimiter = new RateLimiter(redisTemplate, breaker);
    }

    /** 用真实计数的模拟：increment 递增、get 读当前值，语义与 Redis 一致 */
    private void stubRealCounters() {
        AtomicLong userCount = new AtomicLong();
        AtomicLong ipCount = new AtomicLong();
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(USER_KEY)).thenAnswer(inv -> userCount.incrementAndGet());
        when(valueOps.increment(IP_KEY)).thenAnswer(inv -> ipCount.incrementAndGet());
        when(valueOps.get(USER_KEY)).thenAnswer(inv -> userCount.get() == 0 ? null : userCount.get());
        when(valueOps.get(IP_KEY)).thenAnswer(inv -> ipCount.get() == 0 ? null : ipCount.get());
    }

    @Test
    @DisplayName("窗口内允许 5 次失败，第 6 次检查抛 429（阈值语义与文档一致）")
    void allowsFiveFailuresThenRejects() {
        stubRealCounters();

        for (int i = 1; i <= 5; i++) {
            assertDoesNotThrow(() -> rateLimiter.checkLoginAllowed("admin", "1.2.3.4"),
                    "第 " + i + " 次尝试应放行（此前失败 " + (i - 1) + " 次）");
            rateLimiter.recordLoginFailure("admin", "1.2.3.4");
        }

        BusinessException ex = assertThrows(BusinessException.class,
                () -> rateLimiter.checkLoginAllowed("admin", "1.2.3.4"));
        assertEquals(429, ex.getCode());
    }

    @Test
    @DisplayName("回归：检查自身不计数（否则每次失败被算两次，阈值腰斩）")
    void checkDoesNotIncrement() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(USER_KEY)).thenReturn(null);
        when(valueOps.get(IP_KEY)).thenReturn(null);

        rateLimiter.checkLoginAllowed("admin", "1.2.3.4");

        verify(valueOps, never()).increment(anyString());
    }

    @Test
    @DisplayName("IP 维度超限同样拒绝（换用户名也刷不动）")
    void rejectsOnIpDimension() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("login:rate:userA")).thenReturn(null);
        when(valueOps.get("login:rate:userB")).thenReturn(null);
        when(valueOps.get("login:rate:ip:9.9.9.9")).thenReturn(99L);

        assertThrows(BusinessException.class,
                () -> rateLimiter.checkLoginAllowed("userA", "9.9.9.9"));
        assertThrows(BusinessException.class,
                () -> rateLimiter.checkLoginAllowed("userB", "9.9.9.9"));
    }

    @Test
    @DisplayName("缺少 TTL 的计数键会被自愈补设窗口（否则该账号被永久 429）")
    void healsTtlWhenMissing() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(USER_KEY)).thenReturn(2L);      // 非首次
        when(valueOps.increment(IP_KEY)).thenReturn(2L);
        when(redisTemplate.getExpire(USER_KEY)).thenReturn(-1L); // 无 TTL
        when(redisTemplate.getExpire(IP_KEY)).thenReturn(-1L);

        rateLimiter.recordLoginFailure("admin", "1.2.3.4");

        verify(redisTemplate).expire(USER_KEY, 60L, TimeUnit.SECONDS);
        verify(redisTemplate).expire(IP_KEY, 60L, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("首次计数时设置 60 秒窗口 TTL")
    void setsWindowTtlOnFirstIncrement() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(USER_KEY)).thenReturn(1L);
        when(valueOps.increment(IP_KEY)).thenReturn(1L);

        rateLimiter.recordLoginFailure("admin", "1.2.3.4");

        verify(redisTemplate).expire(USER_KEY, 60L, TimeUnit.SECONDS);
        verify(redisTemplate).expire(IP_KEY, 60L, TimeUnit.SECONDS);
        // 首次已设 TTL，不需要再读 getExpire
        verify(redisTemplate, never()).getExpire(anyString());
    }

    @Test
    @DisplayName("Redis 故障时放行（限流不阻断登录主流程）")
    void allowsWhenRedisDown() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString()))
                .thenThrow(new RedisConnectionFailureException("connection refused"));

        assertDoesNotThrow(() -> rateLimiter.checkLoginAllowed("admin", "1.2.3.4"));
    }

    @Test
    @DisplayName("计数键值非数字（脏数据）时按未超限处理，不抛异常")
    void toleratesNonNumericCounter() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(USER_KEY)).thenReturn("not-a-number");
        when(valueOps.get(IP_KEY)).thenReturn(null);

        assertDoesNotThrow(() -> rateLimiter.checkLoginAllowed("admin", "1.2.3.4"));
    }

    @Test
    @DisplayName("登录失败记账：两个维度各 +1")
    void recordFailure_incrementsBothDimensions() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(USER_KEY)).thenReturn(2L);
        when(valueOps.increment(IP_KEY)).thenReturn(2L);
        when(redisTemplate.getExpire(USER_KEY)).thenReturn(30L);   // 已有 TTL，不补设
        when(redisTemplate.getExpire(IP_KEY)).thenReturn(30L);

        rateLimiter.recordLoginFailure("admin", "1.2.3.4");

        verify(valueOps).increment(USER_KEY);
        verify(valueOps).increment(IP_KEY);
        verify(redisTemplate, never()).expire(anyString(), anyLong(), any(TimeUnit.class));
    }

    @Test
    @DisplayName("登录成功清零：删除两个维度的计数 key")
    void resetOnSuccess_deletesKeys() {
        rateLimiter.resetOnSuccess("admin", "1.2.3.4");

        verify(redisTemplate).delete(USER_KEY);
        verify(redisTemplate).delete(IP_KEY);
    }
}
