package com.jyu.cache.service;

import com.jyu.cache.config.CacheProperties;
import com.jyu.cache.mapper.ProductMapper;
import com.jyu.cache.entity.Product;
import com.jyu.cache.common.SafeRedisTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * HotSpotService 单元测试：ZSet 防污染 + 榜单裁剪
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("热点识别（ZSet 防污染）单元测试")
class HotSpotServiceTest {

    @Mock private ProductMapper productMapper;
    @Mock private RedisTemplate<String, Object> redisTemplate;
    @Mock private SafeRedisTemplate safeRedis;
    @Mock private ZSetOperations<String, Object> zSetOps;
    @Mock private org.springframework.data.redis.core.ValueOperations<String, Object> valueOps;

    private CacheProperties cacheProperties;
    private HotSpotService hotSpotService;

    @BeforeEach
    void setUp() {
        cacheProperties = new CacheProperties();
        cacheProperties.setPrefix("cache:product");
        cacheProperties.setTtl(1800L);
        cacheProperties.setTtlRandomSeconds(300);
        cacheProperties.setPreheatCount(10);
        cacheProperties.setHotRankMaxSize(200);
        cacheProperties.setHotRankTtlDays(7);

        hotSpotService = new HotSpotService(productMapper, redisTemplate, safeRedis, cacheProperties);

        // tryRun / degrade 直通执行（异常按真实语义处理）
        // lenient：部分用例不触发这两个桩，避免 UnnecessaryStubbing 报错
        lenient().doAnswer(inv -> {
            try {
                ((Runnable) inv.getArgument(0)).run();
            } catch (Exception ignored) {
            }
            return null;
        }).when(safeRedis).tryRun(any());
        lenient().doAnswer(inv -> {
            try {
                return ((java.util.function.Supplier<?>) inv.getArgument(0)).get();
            } catch (Exception e) {
                return ((java.util.function.Supplier<?>) inv.getArgument(1)).get();
            }
        }).when(safeRedis).degrade(any(), any());
    }

    @Test
    @DisplayName("防污染：正常商品访问 -> ZINCRBY 热度 +1")
    void recordAccess_incrementsScore() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
        when(valueOps.get("cache:product:1")).thenReturn(null);   // 无空值标记

        hotSpotService.recordAccess(1L);

        verify(zSetOps).incrementScore("cache:hotspot:rank", "1", 1);
    }

    @Test
    @DisplayName("防污染：命中空值标记（不存在的 ID）-> 不进榜（不 ZINCRBY）")
    void recordAccess_skipsNullMarkedId() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("cache:product:999")).thenReturn("NULL_VALUE_MARKER");

        hotSpotService.recordAccess(999L);

        verify(zSetOps, never()).incrementScore(anyString(), anyString(), anyDouble());
    }

    @Test
    @DisplayName("删除商品：ZREM 移除榜单成员")
    void removeHot_removesFromRank() {
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);

        hotSpotService.removeHot(1L);

        verify(zSetOps).remove("cache:hotspot:rank", "1");
    }

    @Test
    @DisplayName("榜单裁剪：超过容量上限时 ZREMRANGEBYRANK 保头部 + 刷新 TTL")
    void trimRank_keepsTopNAndRefreshesTtl() {
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
        // 榜单 TopN 有一个商品（否则 hotIds 为空，提前 return 0，trimRank 不执行）
        Set<ZSetOperations.TypedTuple<Object>> tuples = new LinkedHashSet<>();
        ZSetOperations.TypedTuple<Object> t = new ZSetOperations.TypedTuple<Object>() {
            @Override public Object getValue() { return "1"; }
            @Override public Double getScore() { return 10.0; }
            @Override public int compareTo(ZSetOperations.TypedTuple<Object> o) { return 0; }
        };
        tuples.add(t);
        when(zSetOps.reverseRangeWithScores("cache:hotspot:rank", 0, 9)).thenReturn(tuples);
        when(productMapper.selectById(1L)).thenReturn(new Product());
        when(zSetOps.zCard("cache:hotspot:rank")).thenReturn(350L);   // 超过 200

        hotSpotService.hotSpotPreheat();

        verify(zSetOps).removeRange("cache:hotspot:rank", 0, -201);   // 保留前 200
        verify(redisTemplate).expire("cache:hotspot:rank", 7L, java.util.concurrent.TimeUnit.DAYS);
    }

    @Test
    @DisplayName("预热窗口商品被删：查库为 null -> 从榜单移除（不空转）")
    void preheat_removesDeletedProductsFromRank() {
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
        // 榜单 TopN 只有不存在的 999
        Set<ZSetOperations.TypedTuple<Object>> tuples = new LinkedHashSet<>();
        ZSetOperations.TypedTuple<Object> t = new ZSetOperations.TypedTuple<Object>() {
            @Override public Object getValue() { return "999"; }
            @Override public Double getScore() { return 10.0; }
            @Override public int compareTo(ZSetOperations.TypedTuple<Object> o) { return 0; }
        };
        tuples.add(t);
        when(zSetOps.reverseRangeWithScores("cache:hotspot:rank", 0, 9)).thenReturn(tuples);
        when(productMapper.selectById(999L)).thenReturn(null);        // 库里已删
        when(zSetOps.zCard("cache:hotspot:rank")).thenReturn(1L);

        int count = hotSpotService.hotSpotPreheat();

        org.junit.jupiter.api.Assertions.assertEquals(0, count);
        verify(zSetOps).remove("cache:hotspot:rank", "999");          // 被移出榜单
    }

    @Test
    @DisplayName("member 兼容处理：带引号的 \"999\" 也能正确解析（v2 序列化坑）")
    void topHotIds_stripsQuotesFromMember() {
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
        Set<ZSetOperations.TypedTuple<Object>> tuples = new LinkedHashSet<>();
        ZSetOperations.TypedTuple<Object> t = new ZSetOperations.TypedTuple<Object>() {
            @Override public Object getValue() { return "\"999\""; }  // v2 遗留带引号格式
            @Override public Double getScore() { return 10.0; }
            @Override public int compareTo(ZSetOperations.TypedTuple<Object> o) { return 0; }
        };
        tuples.add(t);
        when(zSetOps.reverseRangeWithScores("cache:hotspot:rank", 0, 9)).thenReturn(tuples);
        when(productMapper.selectById(999L)).thenReturn(null);
        when(zSetOps.zCard("cache:hotspot:rank")).thenReturn(1L);

        // 若引号未去除，Long.parseLong("\"999\"") 会抛 NumberFormatException
        hotSpotService.hotSpotPreheat();

        verify(productMapper).selectById(999L);
    }
}
