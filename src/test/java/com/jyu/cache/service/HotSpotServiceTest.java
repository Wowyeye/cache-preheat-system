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
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * HotSpotService 单元测试：热度记账、防污染约定、榜单裁剪、脏数据自愈、自动预热记账
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("热点识别（ZSet 榜单）单元测试")
class HotSpotServiceTest {

    private static final String RANK = "cache:hotspot:rank";
    private static final String STATS = "cache:hotspot:rank:stats";

    @Mock private ProductMapper productMapper;
    @Mock private RedisTemplate<String, Object> redisTemplate;
    @Mock private SafeRedisTemplate safeRedis;
    @Mock private ZSetOperations<String, Object> zSetOps;
    @Mock private ValueOperations<String, Object> valueOps;
    @Mock private HashOperations<String, Object, Object> hashOps;

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

    /** 造一个 ZSet member */
    private Set<ZSetOperations.TypedTuple<Object>> tuplesOf(Object value, double score) {
        Set<ZSetOperations.TypedTuple<Object>> tuples = new LinkedHashSet<>();
        tuples.add(new ZSetOperations.TypedTuple<Object>() {
            @Override public Object getValue() { return value; }
            @Override public Double getScore() { return score; }
            @Override public int compareTo(ZSetOperations.TypedTuple<Object> o) { return 0; }
        });
        return tuples;
    }

    // ==================== 热度记账 ====================

    @Test
    @DisplayName("热度记账：ZINCRBY 分数 +1，且不再额外读商品缓存 key")
    void recordAccess_incrementsScore() {
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);

        hotSpotService.recordAccess(1L);

        verify(zSetOps).incrementScore(RANK, "1", 1);
        // 防污染改为“调用方只在确认商品存在后才记账”，热路径省掉一次 GET
        verify(redisTemplate, never()).opsForValue();
    }

    @Test
    @DisplayName("删除商品：ZREM 移除榜单成员")
    void removeHot_removesFromRank() {
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);

        hotSpotService.removeHot(1L);

        verify(zSetOps).remove(RANK, "1");
    }

    // ==================== 榜单裁剪 ====================

    @Test
    @DisplayName("榜单裁剪：超过容量上限时 ZREMRANGEBYRANK 保头部 + 刷新 TTL")
    void trimRank_keepsTopNAndRefreshesTtl() {
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
        // 榜单 TopN 有一个商品（否则 hotIds 为空，提前 return 0，trimRank 不执行）
        when(zSetOps.reverseRangeWithScores(RANK, 0, 9)).thenReturn(tuplesOf("1", 10.0));
        when(productMapper.selectById(1L)).thenReturn(new Product());
        when(zSetOps.zCard(RANK)).thenReturn(350L);   // 超过 200

        hotSpotService.hotSpotPreheat();

        verify(zSetOps).removeRange(RANK, 0, -201);   // 保留前 200
        verify(redisTemplate).expire(RANK, 7L, java.util.concurrent.TimeUnit.DAYS);
    }

    @Test
    @DisplayName("预热窗口商品被删：查库为 null -> 从榜单移除（不空转）")
    void preheat_removesDeletedProductsFromRank() {
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
        when(zSetOps.reverseRangeWithScores(RANK, 0, 9)).thenReturn(tuplesOf("999", 10.0));
        when(productMapper.selectById(999L)).thenReturn(null);        // 库里已删
        when(zSetOps.zCard(RANK)).thenReturn(1L);

        int count = hotSpotService.hotSpotPreheat();

        assertEquals(0, count);
        verify(zSetOps).remove(RANK, "999");          // 被移出榜单
    }

    @Test
    @DisplayName("榜单脏成员：非数字 member 被跳过并移除，不会让整轮预热中断")
    void dirtyMember_isSkippedAndRemoved() {
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
        when(zSetOps.reverseRangeWithScores(RANK, 0, 9)).thenReturn(tuplesOf("not-a-number", 5.0));

        int count = hotSpotService.hotSpotPreheat();

        assertEquals(0, count);
        verify(zSetOps).remove(RANK, "not-a-number");
        verify(productMapper, never()).selectById(anyLong());   // 不拿脏 ID 去查库
    }

    @Test
    @DisplayName("member 兼容处理：带引号的 \"999\" 也能正确解析（v2 序列化坑）")
    void topHotIds_stripsQuotesFromMember() {
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
        when(zSetOps.reverseRangeWithScores(RANK, 0, 9)).thenReturn(tuplesOf("\"999\"", 10.0));
        when(productMapper.selectById(999L)).thenReturn(null);
        when(zSetOps.zCard(RANK)).thenReturn(1L);

        // 若引号未去除，Long.parseLong("\"999\"") 会抛 NumberFormatException
        hotSpotService.hotSpotPreheat();

        verify(productMapper).selectById(999L);
    }

    // ==================== 自动预热记账（v3.1 修复） ====================

    @Test
    @DisplayName("自动预热也记账：轮次/最近一轮写入 Redis（监控页不再恒为 0/未执行）")
    void autoPreheat_recordsStats() {
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);   // 让回填缓存成功，本轮 count=1
        when(redisTemplate.opsForHash()).thenReturn(hashOps);
        when(zSetOps.reverseRangeWithScores(RANK, 0, 9)).thenReturn(tuplesOf("1", 10.0));
        when(productMapper.selectById(1L)).thenReturn(new Product());
        when(zSetOps.zCard(RANK)).thenReturn(1L);

        hotSpotService.autoPreheat();

        verify(hashOps).put(STATS, "lastCount", 1);
        verify(hashOps).put(eq(STATS), eq("lastTime"), anyLong());
        verify(hashOps).increment(STATS, "rounds", 1);
    }

    @Test
    @DisplayName("手动预热同样记账")
    void manualPreheat_recordsStats() {
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(redisTemplate.opsForHash()).thenReturn(hashOps);
        when(zSetOps.reverseRangeWithScores(RANK, 0, 9)).thenReturn(tuplesOf("1", 10.0));
        when(productMapper.selectById(1L)).thenReturn(new Product());
        when(zSetOps.zCard(RANK)).thenReturn(1L);

        assertEquals(1, hotSpotService.manualPreheat());

        verify(hashOps).put(STATS, "lastCount", 1);
        verify(hashOps).increment(STATS, "rounds", 1);
    }
}
