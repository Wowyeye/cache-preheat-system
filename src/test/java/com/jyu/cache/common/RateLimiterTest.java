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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RateLimiter 单元测试：固定窗口限流（60 秒 5 次）+ Redis 故障放行
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("登录限流器单元测试")
class RateLimiterTest {

    @Mock private RedisTemplate<String, Object> redisTemplate;
    @Mock private ValueOperations<String, Object> valueOps;

    private RateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        rateLimiter = new RateLimiter(redisTemplate);
    }

    @Test
    @DisplayName("窗口内前 5 次放行，第 6 次抛 429（用户名维度）")
    void allowsFirstFiveThenRejects() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        // 用户名与 IP 两个维度各自计数 1..6；IP 维度用不同 key 区分
        when(valueOps.increment("login:rate:admin")).thenReturn(1L, 2L, 3L, 4L, 5L, 6L);
        when(valueOps.increment("login:rate:ip:1.2.3.4")).thenReturn(1L, 2L, 3L, 4L, 5L, 6L);

        for (int i = 1; i <= 5; i++) {
            assertDoesNotThrow(() -> rateLimiter.checkLoginAllowed("admin", "1.2.3.4"),
                    "第 " + i + " 次应放行");
        }
        BusinessException ex = assertThrows(BusinessException.class,
                () -> rateLimiter.checkLoginAllowed("admin", "1.2.3.4"));
        assertEquals(429, ex.getCode());
    }

    @Test
    @DisplayName("IP 维度超限同样拒绝（换用户名也刷不动）")
    void rejectsOnIpDimension() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        // 用户名维度计数正常（1），IP 维度已爆（99）
        when(valueOps.increment(eq("login:rate:userA"))).thenReturn(1L);
        when(valueOps.increment(eq("login:rate:userB"))).thenReturn(1L);
        when(valueOps.increment(eq("login:rate:ip:9.9.9.9"))).thenReturn(99L);

        assertThrows(BusinessException.class,
                () -> rateLimiter.checkLoginAllowed("userA", "9.9.9.9"));
        assertThrows(BusinessException.class,
                () -> rateLimiter.checkLoginAllowed("userB", "9.9.9.9"));
    }

    @Test
    @DisplayName("首次计数时设置 60 秒窗口 TTL")
    void setsWindowTtlOnFirstIncrement() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment("login:rate:admin")).thenReturn(1L);
        when(valueOps.increment("login:rate:ip:1.2.3.4")).thenReturn(1L);

        rateLimiter.checkLoginAllowed("admin", "1.2.3.4");

        verify(redisTemplate).expire("login:rate:admin", 60L, TimeUnit.SECONDS);
        verify(redisTemplate).expire("login:rate:ip:1.2.3.4", 60L, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("Redis 故障时放行（限流不阻断登录主流程）")
    void allowsWhenRedisDown() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(anyString()))
                .thenThrow(new RedisConnectionFailureException("connection refused"));

        assertDoesNotThrow(() -> rateLimiter.checkLoginAllowed("admin", "1.2.3.4"));
    }

    @Test
    @DisplayName("登录失败记账：两个维度各 +1")
    void recordFailure_incrementsBothDimensions() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment("login:rate:admin")).thenReturn(2L);
        when(valueOps.increment("login:rate:ip:1.2.3.4")).thenReturn(2L);

        rateLimiter.recordLoginFailure("admin", "1.2.3.4");

        verify(valueOps).increment("login:rate:admin");
        verify(valueOps).increment("login:rate:ip:1.2.3.4");
        // 已有计数（非首次），不应重设 TTL
        verify(redisTemplate, never()).expire(anyString(), anyLong(), any(TimeUnit.class));
    }

    @Test
    @DisplayName("登录成功清零：删除两个维度的计数 key")
    void resetOnSuccess_deletesKeys() {
        rateLimiter.resetOnSuccess("admin", "1.2.3.4");

        verify(redisTemplate).delete("login:rate:admin");
        verify(redisTemplate).delete("login:rate:ip:1.2.3.4");
    }
}
