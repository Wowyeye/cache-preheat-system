package com.jyu.cache.service;

import com.jyu.cache.common.SafeRedisTemplate;
import com.jyu.cache.config.CacheProperties;
import com.jyu.cache.entity.Product;
import com.jyu.cache.mapper.ProductMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * 自动热点识别 Service（v3 加固）
 *
 * v2 三个缺陷：
 *   1. 榜单无 TTL、无清理、无容量上限，只增不减；
 *   2. 不存在的商品 ID 也进榜（查一次 ID 999，它就以 1 分出现在排行榜——
 *      恶意扫描可把垃圾 ID 顶进 TopN，让每轮定时预热空转查库）；
 *   3. member 被 JSON 序列化成带引号的 "999"，运维工具直读坑。
 *
 * v3 修复：
 *   1. recordAccess 先查空值标记：穿透防护已标记为不存在的 ID 直接不进榜；
 *   2. 每轮预热后裁剪榜单到 hotRankMaxSize（ZREMRANGEBYRANK 保头部）；
 *   3. 榜单 key 设置 hotRankTtlDays 天整体过期兜底；
 *   4. 删除商品时同步 ZREM 榜单成员。
 */
@Slf4j
@Service
public class HotSpotService {

    /** Redis ZSet key：商品实时热度排行榜（member=商品ID字符串，score=访问次数） */
    private static final String HOT_RANK_KEY = "cache:hotspot:rank";

    private final ProductMapper productMapper;
    private final RedisTemplate<String, Object> redisTemplate;
    private final SafeRedisTemplate safeRedis;
    private final CacheProperties cacheProperties;

    public HotSpotService(ProductMapper productMapper,
                          RedisTemplate<String, Object> redisTemplate,
                          SafeRedisTemplate safeRedis,
                          CacheProperties cacheProperties) {
        this.productMapper = productMapper;
        this.redisTemplate = redisTemplate;
        this.safeRedis = safeRedis;
        this.cacheProperties = cacheProperties;
    }

    // ================================================================
    // 一、热度记录（防污染：不存在的 ID 不进榜）
    // ================================================================

    /**
     * 记录一次商品访问（热度 +1）。
     * 防污染逻辑：若该商品已命中"空值标记"（数据库确认为不存在），直接跳过——
     * 恶意扫描不存在的 ID 无法再把垃圾 ID 顶进排行榜。
     */
    public void recordAccess(Long productId) {
        safeRedis.tryRun(() -> {
            // 空值标记存在 = 数据库确认无此商品，不进榜
            Object nullMark = redisTemplate.opsForValue().get(cacheProperties.getPrefix() + ":" + productId);
            if (nullMark != null && "NULL_VALUE_MARKER".equals(nullMark)) {
                return;
            }
            redisTemplate.opsForZSet().incrementScore(HOT_RANK_KEY, String.valueOf(productId), 1);
        });
    }

    /** 商品被删除时同步移除榜单成员 */
    public void removeHot(Long productId) {
        safeRedis.tryRun(() -> redisTemplate.opsForZSet().remove(HOT_RANK_KEY, String.valueOf(productId)));
    }

    // ================================================================
    // 二、定时任务：周期性自动预热当前热点
    // ================================================================

    @Scheduled(fixedDelayString = "${cache.auto-preheat-interval-ms:60000}", initialDelay = 15000)
    public void autoPreheat() {
        try {
            int count = hotSpotPreheat();
            if (count > 0) {
                log.info("[热点识别] 自动预热完成，本轮加载 {} 条热点商品到缓存", count);
            }
        } catch (Exception e) {
            log.error("[热点识别] 自动预热执行失败", e);
        }
    }

    /**
     * 热点预热核心：ZREVRANGE 取 TopN -> 查库 -> 写缓存 -> 裁剪榜单
     */
    public int hotSpotPreheat() {
        List<String> hotIds = topHotIds(cacheProperties.getPreheatCount());
        if (hotIds.isEmpty()) {
            log.debug("[热点识别] 热度排行榜为空，本轮跳过");
            return 0;
        }

        int count = 0;
        for (String idStr : hotIds) {
            long id = Long.parseLong(idStr);
            Product product = productMapper.selectById(id);
            if (product != null) {
                long ttl = cacheProperties.getTtl()
                        + ThreadLocalRandom.current().nextLong(cacheProperties.getTtlRandomSeconds());
                final long ttlFinal = ttl;
                count += safeRedis.degrade(
                        () -> {
                            redisTemplate.opsForValue().set(cacheProperties.getPrefix() + ":" + id,
                                    product, ttlFinal, TimeUnit.SECONDS);
                            return 1;
                        },
                        () -> 0);
            } else {
                // 数据库里已没有这个 ID（预热窗口商品被删）：从榜单移除，避免长期空转
                removeHot(id);
            }
        }

        // 榜单维护：裁剪容量 + 设置整体 TTL
        trimRank();
        return count;
    }

    /** 裁剪榜单：只保留前 hotRankMaxSize 名，其余清掉；并刷新整体 TTL */
    private void trimRank() {
        safeRedis.tryRun(() -> {
            Long size = redisTemplate.opsForZSet().zCard(HOT_RANK_KEY);
            if (size != null && size > cacheProperties.getHotRankMaxSize()) {
                // 保留 [0, maxSize-1]，移除排在后面的（分数低的）
                redisTemplate.opsForZSet().removeRange(HOT_RANK_KEY, 0, -(cacheProperties.getHotRankMaxSize() + 1));
            }
            redisTemplate.expire(HOT_RANK_KEY, cacheProperties.getHotRankTtlDays(), TimeUnit.DAYS);
        });
    }

    /** 取 TopN 的商品 ID 列表（纯字符串，不依赖序列化细节） */
    private List<String> topHotIds(int topN) {
        List<String> ids = new ArrayList<>();
        safeRedis.tryRun(() -> {
            Set<ZSetOperations.TypedTuple<Object>> tuples =
                    redisTemplate.opsForZSet().reverseRangeWithScores(HOT_RANK_KEY, 0, topN - 1);
            if (tuples != null) {
                for (ZSetOperations.TypedTuple<Object> tuple : tuples) {
                    if (tuple.getValue() != null) {
                        // member 统一按去掉引号的纯数字字符串处理（修复 v2 序列化带引号坑）
                        ids.add(String.valueOf(tuple.getValue()).replace("\"", ""));
                    }
                }
            }
        });
        return ids;
    }

    // ================================================================
    // 三、排行榜查询（给前端热点排行页）
    // ================================================================

    public List<Map<String, Object>> getHotRank(int topN) {
        List<Map<String, Object>> result = new ArrayList<>();
        List<String> ids = topHotIds(topN);
        for (String idStr : ids) {
            long id = Long.parseLong(idStr);
            Double score = safeRedis.degrade(
                    () -> redisTemplate.opsForZSet().score(HOT_RANK_KEY, String.valueOf(id)),
                    () -> null);
            Map<String, Object> item = new HashMap<>(5);
            item.put("productId", idStr);
            item.put("hotScore", score == null ? 0 : score);
            Product product = productMapper.selectById(id);
            if (product != null) {
                item.put("name", product.getName());
                item.put("viewCount", product.getViewCount());
                item.put("price", product.getPrice());
            }
            result.add(item);
        }
        return result;
    }

    // ================================================================
    // 四、自动预热统计（给前端监控页）
    // ================================================================

    public Map<String, Object> getAutoPreheatStats() {
        Map<String, Object> stats = new HashMap<>(4);
        safeRedis.tryRun(() -> {
            Object count = redisTemplate.opsForHash().get(HOT_RANK_KEY + ":stats", "lastCount");
            Object time = redisTemplate.opsForHash().get(HOT_RANK_KEY + ":stats", "lastTime");
            Object rounds = redisTemplate.opsForHash().get(HOT_RANK_KEY + ":stats", "rounds");
            stats.put("lastPreheatCount", count == null ? 0 : count);
            long t = time == null ? 0L : Long.parseLong(String.valueOf(time));
            stats.put("lastPreheatTime", t == 0 ? "未执行"
                    : new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(t)));
            stats.put("autoPreheatRounds", rounds == null ? 0 : rounds);
        });
        if (stats.isEmpty()) {
            stats.put("lastPreheatCount", 0);
            stats.put("lastPreheatTime", "未执行");
            stats.put("autoPreheatRounds", 0);
        }
        return stats;
    }

    public int manualPreheat() {
        int count = hotSpotPreheat();
        recordStats(count);
        return count;
    }

    /** 记录本轮预热结果到 Redis Hash */
    private void recordStats(int count) {
        safeRedis.tryRun(() -> {
            redisTemplate.opsForHash().put(HOT_RANK_KEY + ":stats", "lastCount", count);
            redisTemplate.opsForHash().put(HOT_RANK_KEY + ":stats", "lastTime", System.currentTimeMillis());
            redisTemplate.opsForHash().increment(HOT_RANK_KEY + ":stats", "rounds", 1);
        });
    }
}
