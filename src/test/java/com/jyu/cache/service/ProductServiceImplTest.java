package com.jyu.cache.service;

import com.jyu.cache.common.DistributedLock;
import com.jyu.cache.common.SafeRedisTemplate;
import com.jyu.cache.config.CacheProperties;
import com.jyu.cache.entity.Product;
import com.jyu.cache.mapper.ProductMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ProductServiceImpl 缓存策略单元测试（纯 Mockito，不依赖真实 Redis/MySQL）
 *
 * 覆盖四条核心路径：
 *   1. 缓存命中      -> 直接返回，不查库
 *   2. 未命中+锁成功  -> 查库并回填缓存
 *   3. 穿透防护      -> 命中空值标记返回 null；库中无数据写入空值标记
 *   4. Redis 故障降级 -> 读缓存异常时走 DB 直查，系统不 500
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("商品缓存策略单元测试")
class ProductServiceImplTest {

    @Mock private ProductMapper productMapper;
    @Mock private RedisTemplate<String, Object> redisTemplate;
    @Mock private SafeRedisTemplate safeRedis;
    @Mock private ValueOperations<String, Object> valueOps;
    @Mock private CacheStatsService statsService;
    @Mock private DelayDeleteService delayDeleteService;
    @Mock private HotSpotService hotSpotService;
    @Mock private DistributedLock distributedLock;

    private CacheProperties cacheProperties;
    private ProductServiceImpl productService;

    private final Product dbProduct = new Product(1L, "iPhone 15", 1L,
            new java.math.BigDecimal("9999.00"), 500, "描述", 1, 99985L, null, null);

    @BeforeEach
    void setUp() {
        cacheProperties = new CacheProperties();
        cacheProperties.setPrefix("cache:product");
        cacheProperties.setTtl(1800L);
        cacheProperties.setTtlRandomSeconds(300);
        cacheProperties.setNullTtl(60L);
        cacheProperties.setLockWaitMs(3000L);

        productService = new ProductServiceImpl(productMapper, redisTemplate, safeRedis,
                cacheProperties, statsService, delayDeleteService, hotSpotService, distributedLock);

        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        // SafeRedisTemplate.degrade：真实语义是"异常走 fallback"，单测里手工模拟
        lenient().doAnswer(inv -> {
            java.util.function.Supplier<?> action = inv.getArgument(0);
            java.util.function.Supplier<?> fallback = inv.getArgument(1);
            try {
                return action.get();
            } catch (Exception e) {
                return fallback.get();
            }
        }).when(safeRedis).degrade(any(), any());
        lenient().doAnswer(inv -> {
            Runnable action = inv.getArgument(0);
            try {
                action.run();
            } catch (Exception ignored) {
            }
            return null;
        }).when(safeRedis).tryRun(any());
    }

    @Test
    @DisplayName("缓存命中：直接返回缓存对象，不查数据库")
    void cacheHit_returnsCachedProduct_withoutDb() {
        when(valueOps.get("cache:product:1")).thenReturn(dbProduct);

        Product result = productService.getById(1L);

        assertEquals(dbProduct, result);
        verify(productMapper, never()).selectById(anyLong());
        verify(statsService).recordHit(anyLong());
    }

    @Test
    @DisplayName("缓存未命中+拿到锁：查库并回填缓存（Cache Aside 写路径）")
    void cacheMiss_withLock_loadsFromDbAndCaches() {
        when(valueOps.get("cache:product:1")).thenReturn(null);   // 未命中
        when(productMapper.selectById(1L)).thenReturn(dbProduct);
        // 模拟分布式锁拿到：直接执行 action
        when(distributedLock.executeWithLock(anyString(), anyLong(), any(), any()))
                .thenAnswer(inv -> ((java.util.function.Supplier<?>) inv.getArgument(2)).get());

        Product result = productService.getById(1L);

        assertEquals(dbProduct, result);
        // 回填缓存：set(key, product, ttl, SECONDS)
        verify(valueOps).set(eq("cache:product:1"), eq(dbProduct), anyLong(), any());
        verify(statsService).recordMiss(anyLong());
    }

    @Test
    @DisplayName("穿透防护：命中空值标记直接返回 null，不查库不再回源")
    void nullMarkerHit_returnsNull_withoutDb() {
        when(valueOps.get("cache:product:999")).thenReturn("NULL_VALUE_MARKER");

        Product result = productService.getById(999L);

        assertNull(result);
        verify(productMapper, never()).selectById(anyLong());
        verify(statsService).recordNullHit(anyLong());
    }

    @Test
    @DisplayName("穿透防护：数据库也无此数据 -> 写入空值标记（短 TTL）")
    void dbMissing_writesNullMarker() {
        when(valueOps.get("cache:product:999")).thenReturn(null);
        when(productMapper.selectById(999L)).thenReturn(null);
        when(distributedLock.executeWithLock(anyString(), anyLong(), any(), any()))
                .thenAnswer(inv -> ((java.util.function.Supplier<?>) inv.getArgument(2)).get());

        Product result = productService.getById(999L);

        assertNull(result);
        // 写入空值标记：set(key, NULL_VALUE_MARKER, nullTtl, SECONDS)
        verify(valueOps).set(eq("cache:product:999"), eq("NULL_VALUE_MARKER"), eq(60L), any());
    }

    @Test
    @DisplayName("Redis 故障降级：读缓存抛连接异常 -> 直接查 DB，返回数据不抛错")
    void redisDown_degradesToDb_directly() {
        // 第一次读缓存（recordAccess 后的 degrade）抛异常 -> fallback 返回 null
        when(valueOps.get("cache:product:1"))
                .thenThrow(new RedisConnectionFailureException("connection refused"));
        when(productMapper.selectById(1L)).thenReturn(dbProduct);
        when(distributedLock.executeWithLock(anyString(), anyLong(), any(), any()))
                .thenAnswer(inv -> ((java.util.function.Supplier<?>) inv.getArgument(3)).get()); // 未拿到锁走 fallback

        Product result = productService.getById(1L);

        assertEquals(dbProduct, result);
        verify(statsService).recordDegrade();
    }

    @Test
    @DisplayName("防雪崩：回填缓存的 TTL 在 [1800, 2100) 区间内随机")
    void cacheWrite_ttlHasRandomJitter() {
        when(valueOps.get("cache:product:1")).thenReturn(null);
        when(productMapper.selectById(1L)).thenReturn(dbProduct);
        when(distributedLock.executeWithLock(anyString(), anyLong(), any(), any()))
                .thenAnswer(inv -> ((java.util.function.Supplier<?>) inv.getArgument(2)).get());

        productService.getById(1L);

        org.mockito.ArgumentCaptor<Long> ttlCaptor = org.mockito.ArgumentCaptor.forClass(Long.class);
        verify(valueOps).set(eq("cache:product:1"), eq(dbProduct), ttlCaptor.capture(), any());
        long ttl = ttlCaptor.getValue();
        org.junit.jupiter.api.Assertions.assertTrue(ttl >= 1800 && ttl < 2100,
                "TTL 应在 [1800, 2100) 区间，实际=" + ttl);
    }

    @Test
    @DisplayName("分页：page/size 边界钳制（page>=1，size<=100）")
    void listPage_clampsParams() {
        when(productMapper.countByKeyword(null)).thenReturn(500L);
        when(productMapper.selectPage(0, 100, null)).thenReturn(List.of());

        var result = productService.listPage(0, 9999, null);

        assertEquals(1, result.get("page"));
        assertEquals(100, result.get("size"));
        assertEquals(500L, result.get("total"));
    }
}
