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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
    @DisplayName("缓存命中：直接返回缓存对象，不查数据库，并记一次热度")
    void cacheHit_returnsCachedProduct_withoutDb() {
        when(valueOps.get("cache:product:1")).thenReturn(dbProduct);

        Product result = productService.getById(1L);

        assertEquals(dbProduct, result);
        verify(productMapper, never()).selectById(anyLong());
        verify(statsService).recordHit(anyLong());
        verify(hotSpotService).recordAccess(1L);
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
        // 真实访问（回源命中）才记热度，并累加浏览量（v3.1）
        verify(hotSpotService).recordAccess(1L);
        verify(productMapper).incrementViewCount(1L);
    }

    @Test
    @DisplayName("穿透防护：命中空值标记直接返回 null，不查库不再回源，也不进热度榜")
    void nullMarkerHit_returnsNull_withoutDb() {
        when(valueOps.get("cache:product:999")).thenReturn("NULL_VALUE_MARKER");

        Product result = productService.getById(999L);

        assertNull(result);
        verify(productMapper, never()).selectById(anyLong());
        verify(statsService).recordNullHit(anyLong());
        // 防污染关键断言：不存在的 ID 绝不进榜
        verify(hotSpotService, never()).recordAccess(anyLong());
    }

    @Test
    @DisplayName("穿透防护：数据库也无此数据 -> 写入空值标记（短 TTL），且不进热度榜")
    void dbMissing_writesNullMarker() {
        when(valueOps.get("cache:product:999")).thenReturn(null);
        when(productMapper.selectById(999L)).thenReturn(null);
        when(distributedLock.executeWithLock(anyString(), anyLong(), any(), any()))
                .thenAnswer(inv -> ((java.util.function.Supplier<?>) inv.getArgument(2)).get());

        Product result = productService.getById(999L);

        assertNull(result);
        // 写入空值标记：set(key, NULL_VALUE_MARKER, nullTtl, SECONDS)
        verify(valueOps).set(eq("cache:product:999"), eq("NULL_VALUE_MARKER"), eq(60L), any());
        verify(hotSpotService, never()).recordAccess(anyLong());
        // 不存在的商品也不该累加浏览量
        verify(productMapper, never()).incrementViewCount(anyLong());
    }

    @Test
    @DisplayName("防污染：锁降级路径查到的商品才进榜，查不到就不进")
    void lockFallback_onlyRecordsExistingProducts() {
        when(valueOps.get("cache:product:1")).thenReturn(null);
        when(productMapper.selectById(1L)).thenReturn(dbProduct);
        when(distributedLock.executeWithLock(anyString(), anyLong(), any(), any()))
                .thenAnswer(inv -> ((java.util.function.Supplier<?>) inv.getArgument(3)).get());  // 走降级

        assertEquals(dbProduct, productService.getById(1L));
        verify(statsService).recordLockFallback();
        verify(hotSpotService).recordAccess(1L);
    }

    @Test
    @DisplayName("缓存脏值（非 Product 类型）：清除后回源，不让强转异常变成 500")
    void dirtyCacheValue_isEvictedAndReloaded() {
        when(valueOps.get("cache:product:1")).thenReturn("garbage-from-other-client");
        when(productMapper.selectById(1L)).thenReturn(dbProduct);
        when(distributedLock.executeWithLock(anyString(), anyLong(), any(), any()))
                .thenAnswer(inv -> ((java.util.function.Supplier<?>) inv.getArgument(2)).get());

        Product result = productService.getById(1L);

        assertEquals(dbProduct, result);
        verify(redisTemplate).delete("cache:product:1");
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

    @Test
    @DisplayName("删除：受影响行数为 0 -> 返回 false（Controller 据此 404），不触发双删/榜单清理")
    void delete_returnsFalse_whenNothingDeleted() {
        when(productMapper.deleteById(424242L)).thenReturn(0);

        assertFalse(productService.delete(424242L));

        verify(delayDeleteService, never()).evictWithDelay(anyString());
        verify(hotSpotService, never()).removeHot(anyLong());
    }

    @Test
    @DisplayName("删除：命中 -> 触发延迟双删 + 榜单清理")
    void delete_triggersEvictAndRankCleanup() {
        when(productMapper.deleteById(1L)).thenReturn(1);

        assertTrue(productService.delete(1L));

        verify(delayDeleteService).evictWithDelay("cache:product:1");
        verify(hotSpotService).removeHot(1L);
    }

    @Test
    @DisplayName("更新：受影响行数为 0 -> 返回 false，且不触发双删")
    void update_returnsFalse_whenNoRowAffected() {
        Product p = new Product();
        p.setId(424242L);
        when(productMapper.updateById(p)).thenReturn(0);

        assertFalse(productService.update(p));
        verify(delayDeleteService, never()).evictWithDelay(anyString());
    }
}
