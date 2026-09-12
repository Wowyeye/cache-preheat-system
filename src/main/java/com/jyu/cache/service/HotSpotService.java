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
 *   1. 【防污染的正确做法】recordAccess 只由"已确认商品存在"的调用方触发
 *      （读缓存命中 / 回源查到），不存在的 ID 从源头就不进榜，不再依赖事后查空值标记；
 *   2. 每轮预热后裁剪榜单到 hotRankMaxSize（ZREMRANGEBYRANK 保头部）；
 *   3. 榜单 key 设置 hotRankTtlDays 天整体过期兜底；
 *   4. 删除商品时同步 ZREM 榜单成员；
 *   5. 榜单出现非数字脏成员时跳过并移除，不再让整轮预热/整个接口 500。
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
     *
     * 【防污染约定】调用方必须已确认该商品真实存在（缓存命中，或回源查到非 null）。
     * 这样"扫描不存在 ID"的流量根本不会写进榜单，比"事后查空值标记再回滚"更彻底：
     * 空值标记有 60s TTL，攻击者只要每 60s 换一批新 ID 就能绕过旧方案灌榜。
     */
    public void recordAccess(Long productId) {
        safeRedis.tryRun(() ->
                redisTemplate.opsForZSet().incrementScore(HOT_RANK_KEY, String.valueOf(productId), 1));
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
            // 【v3 修正】自动预热同样要记账，否则监控页的"自动预热轮次/最近一轮"永远是 0/未执行
            recordStats(count);
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
            Long id = parseMemberId(idStr);
            if (id == null) {
                continue;   // 脏成员已在 parseMemberId 内移除，本轮跳过而不是让整轮预热崩掉
            }
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
                        // member 统一按去掉引号的纯数字字符串处理（兼容 v2 遗留的带引号格式）
                        ids.add(String.valueOf(tuple.getValue()).replace("\"", ""));
                    }
                }
            }
        });
        return ids;
    }

    /**
     * 解析榜单成员。兼容 v2 遗留的带引号格式与运维手工写入的脏数据：
     * 非法成员记 warn 并从榜单移除（自愈），返回 null 让调用方跳过。
     */
    private Long parseMemberId(String member) {
        if (member == null) {
            return null;
        }
        String cleaned = member.replace("\"", "").trim();
        try {
            return Long.parseLong(cleaned);
        } catch (NumberFormatException e) {
            log.warn("[热点识别] 榜单存在非数字成员（{}），已从榜单移除", member);
            safeRedis.tryRun(() -> redisTemplate.opsForZSet().remove(HOT_RANK_KEY, member));
            return null;
        }
    }

    // ================================================================
    // 三、排行榜查询（给前端热点排行页）
    // ================================================================

    public List<Map<String, Object>> getHotRank(int topN) {
        List<Map<String, Object>> result = new ArrayList<>();
        List<String> ids = topHotIds(topN);
        for (String idStr : ids) {
            Long id = parseMemberId(idStr);
            if (id == null) {
                continue;
            }
            Double score = safeRedis.degrade(
                    () -> redisTemplate.opsForZSet().score(HOT_RANK_KEY, String.valueOf(id)),
                    () -> null);
            Map<String, Object> item = new HashMap<>(5);
            item.put("productId", String.valueOf(id));
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
