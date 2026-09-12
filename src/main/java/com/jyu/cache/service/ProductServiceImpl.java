package com.jyu.cache.service;

import com.jyu.cache.common.DistributedLock;
import com.jyu.cache.common.SafeRedisTemplate;
import com.jyu.cache.config.CacheProperties;
import com.jyu.cache.entity.Product;
import com.jyu.cache.mapper.ProductMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * 商品 Service 实现类 —— 系统核心（v3 工程化重构）
 *
 * 相对 v2 的六大策略，v3 的演进：
 *
 * 一、缓存击穿防护：synchronized -> Redisson 分布式锁
 *   多实例部署下全集群只有一把锁；拿不到锁/Redis 故障时降级直查 DB（可用性优先）。
 *
 * 二、Redis 降级：硬依赖 -> 弱依赖
 *   读路径全程 SafeRedisTemplate.degrade()：Redis 挂了自动走 DB，系统不 500。
 *
 * 三、监控指标：JVM AtomicLong -> Redis Hash（多实例聚合、重启不丢）。
 *
 * 四、KEYS -> SCAN：统计与清缓存不再阻塞 Redis。
 *
 * 五、延迟双删：new Thread() -> 专用延迟调度线程池，且失效动作后置到事务提交后（见 DelayDeleteService）。
 *
 * 六、热度榜单：防污染 + 容量上限
 *   热度只在"确认商品存在"后累加（缓存命中或回源命中），不存在的 ID 从源头进不了榜（见 HotSpotService）。
 *
 * Cache Aside 六大策略本身（预热/旁路读/双删/穿透/击穿/雪崩）与 v2 完全一致。
 */
@Slf4j
@Service
public class ProductServiceImpl implements ProductService {

    /** 空值标记：代表“数据库中不存在这条数据”（防穿透） */
    private static final String NULL_VALUE = "NULL_VALUE_MARKER";

    private final ProductMapper productMapper;
    private final RedisTemplate<String, Object> redisTemplate;
    private final SafeRedisTemplate safeRedis;
    private final CacheProperties cacheProperties;
    private final CacheStatsService statsService;
    private final DelayDeleteService delayDeleteService;
    private final HotSpotService hotSpotService;
    private final DistributedLock distributedLock;

    public ProductServiceImpl(ProductMapper productMapper,
                              RedisTemplate<String, Object> redisTemplate,
                              SafeRedisTemplate safeRedis,
                              CacheProperties cacheProperties,
                              CacheStatsService statsService,
                              DelayDeleteService delayDeleteService,
                              HotSpotService hotSpotService,
                              DistributedLock distributedLock) {
        this.productMapper = productMapper;
        this.redisTemplate = redisTemplate;
        this.safeRedis = safeRedis;
        this.cacheProperties = cacheProperties;
        this.statsService = statsService;
        this.delayDeleteService = delayDeleteService;
        this.hotSpotService = hotSpotService;
        this.distributedLock = distributedLock;
    }

    private String buildKey(Long id) {
        return cacheProperties.getPrefix() + ":" + id;
    }

    /** 防雪崩：基础 TTL + [0, ttlRandomSeconds) 随机抖动 */
    private long randomTtl() {
        return cacheProperties.getTtl() + ThreadLocalRandom.current().nextLong(cacheProperties.getTtlRandomSeconds());
    }

    // ================================================================
    // 一、缓存预热（启动预热 + 热点自动预热共用入口）
    // ================================================================
    @Override
    public int preheatCache() {
        log.info("===== [v3] 开始缓存预热，加载前 {} 条热点商品 =====", cacheProperties.getPreheatCount());
        List<Product> hotProducts = productMapper.selectHotProducts(cacheProperties.getPreheatCount());

        int count = 0;
        for (Product product : hotProducts) {
            count += safeRedis.degrade(
                    () -> {
                        long ttl = randomTtl();
                        redisTemplate.opsForValue().set(buildKey(product.getId()), product, ttl, TimeUnit.SECONDS);
                        log.info("预热商品: id={}, name={}, viewCount={}, TTL={}s",
                                product.getId(), product.getName(), product.getViewCount(), ttl);
                        return 1;
                    },
                    () -> {
                        log.warn("预热商品 {} 失败（Redis 不可用），跳过", product.getId());
                        return 0;
                    });
        }
        log.info("===== 缓存预热完成，成功预热 {}/{} 条 =====", count, hotProducts.size());
        return count;
    }

    // ================================================================
    // 二、Cache Aside 读策略（穿透/击穿/降级防护）
    // ================================================================
    @Override
    public Product getById(Long id) {
        long startNanos = System.nanoTime();
        String key = buildKey(id);

        // --- 第一步：查缓存（Redis 故障自动降级直查 DB） ---
        Object cached = safeRedis.degrade(
                () -> redisTemplate.opsForValue().get(key),
                () -> {
                    statsService.recordDegrade();
                    return null;
                });

        // 命中空值标记 -> 防穿透，直接返回 null（不记热度：这个 ID 根本不存在）
        if (NULL_VALUE.equals(cached)) {
            statsService.recordNullHit(System.nanoTime() - startNanos);
            log.debug("[查询] 商品 {} 命中空值标记（穿透防护）", id);
            return null;
        }

        // 命中真实数据 -> 记热度（只有存在的商品才进榜）并直接返回
        if (cached instanceof Product cachedProduct) {
            hotSpotService.recordAccess(id);
            statsService.recordHit(System.nanoTime() - startNanos);
            log.debug("[查询] 商品 {} 缓存命中", id);
            return cachedProduct;
        }

        // 缓存里是脏值（旧格式/被外部写入）：删掉当未命中处理，不让强转异常变成 500
        if (cached != null) {
            log.warn("[查询] 商品 {} 缓存值类型异常（{}），清除后回源", id, cached.getClass().getName());
            safeRedis.tryRun(() -> redisTemplate.delete(key));
        }

        // --- 第二步：未命中 -> 分布式锁内回源（防击穿；锁不可用则降级直查） ---
        log.info("[查询] 商品 {} 缓存未命中，尝试加分布式锁回源", id);

        return distributedLock.executeWithLock(
                "product:" + id,
                cacheProperties.getLockWaitMs(),
                () -> loadFromDbAndCache(key, id, startNanos),          // 拿到锁：双重检查 -> 查库 -> 写缓存
                () -> {                                                   // 未拿到锁/锁故障：直接查库
                    statsService.recordLockFallback();
                    log.warn("[查询] 商品 {} 未拿到锁，降级直查数据库", id);
                    Product p = productMapper.selectById(id);
                    statsService.recordMiss(System.nanoTime() - startNanos);
                    if (p != null) {
                        recordRealAccess(id);
                    }
                    return p;
                });
    }

    /** 锁内回源：双重检查缓存 -> 查库 -> 写缓存（或空值标记） */
    private Product loadFromDbAndCache(String key, Long id, long startNanos) {
        // 双重检查：等锁期间可能别的线程已把数据写回缓存
        Object cached = safeRedis.degrade(() -> redisTemplate.opsForValue().get(key), () -> null);
        if (NULL_VALUE.equals(cached)) {
            statsService.recordNullHit(System.nanoTime() - startNanos);
            return null;
        }
        if (cached instanceof Product cachedProduct) {
            hotSpotService.recordAccess(id);
            statsService.recordHit(System.nanoTime() - startNanos);
            return cachedProduct;
        }

        Product product = productMapper.selectById(id);
        statsService.recordMiss(System.nanoTime() - startNanos);

        if (product != null) {
            // 数据库有 -> 写入缓存 -> 返回
            safeRedis.tryRun(() -> redisTemplate.opsForValue()
                    .set(key, product, randomTtl(), TimeUnit.SECONDS));
            recordRealAccess(id);
            log.info("[查询] 商品 {} 数据库查到，已写入缓存", id);
            return product;
        }

        // 数据库无 -> 缓存空值标记（短 TTL 防穿透）
        safeRedis.tryRun(() -> redisTemplate.opsForValue()
                .set(key, NULL_VALUE, cacheProperties.getNullTtl(), TimeUnit.SECONDS));
        log.info("[查询] 商品 {} 数据库不存在，已缓存空值标记（穿透防护）", id);
        return null;
    }

    /**
     * 记录一次"真实存在的商品访问"：热度 +1，并把浏览量落库。
     *
     * 只有回源（真的读到 DB）才累加 view_count——缓存命中不写库，避免每次读都产生一次 DB 写。
     * 这也让启动预热用的 view_count 排序随时间真实变化，而不是永远停在种子数据上。
     */
    private void recordRealAccess(Long id) {
        hotSpotService.recordAccess(id);
        safeRedis.tryRun(() -> productMapper.incrementViewCount(id));
    }

    // ================================================================
    // 三、列表查询（v3 新增分页）
    // ================================================================
    @Override
    public List<Product> listAll() {
        return productMapper.selectAll();
    }

    @Override
    public Map<String, Object> listPage(int page, int size, String keyword) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);   // 上限 100，防恶意大页拉全表
        int offset = (safePage - 1) * safeSize;

        long total = productMapper.countByKeyword(keyword);
        List<Product> items = productMapper.selectPage(offset, safeSize, keyword);

        Map<String, Object> result = new HashMap<>(4);
        result.put("list", items);
        result.put("total", total);
        result.put("page", safePage);
        result.put("size", safeSize);
        return result;
    }

    // ================================================================
    // 四、写操作 + 延迟双删（线程池化）
    // ================================================================
    @Override
    public void add(Product product) {
        if (product.getViewCount() == null) {
            product.setViewCount(0L);
        }
        if (product.getStatus() == null) {
            product.setStatus(1);
        }
        productMapper.insert(product);
        // 新增后清除可能存在的空值缓存（之前查过这个不存在的 ID）
        safeRedis.tryRun(() -> redisTemplate.delete(buildKey(product.getId())));
        log.info("[新增] 商品 {} 已入库并清除空值缓存", product.getId());
    }

    @Override
    public boolean update(Product product) {
        if (productMapper.updateById(product) == 0) {
            log.warn("[更新] 商品 {} 不存在，未更新任何行", product.getId());
            return false;
        }
        delayDeleteService.evictWithDelay(buildKey(product.getId()));
        log.info("[更新] 商品 {} 已更新并触发延迟双删", product.getId());
        return true;
    }

    @Override
    public boolean delete(Long id) {
        if (productMapper.deleteById(id) == 0) {
            log.warn("[删除] 商品 {} 不存在，未删除任何行", id);
            return false;
        }
        delayDeleteService.evictWithDelay(buildKey(id));
        hotSpotService.removeHot(id);   // 商品删了，热度榜同步清掉，防止定时预热查空数据
        log.info("[删除] 商品 {} 已删除并触发延迟双删 + 榜单清理", id);
        return true;
    }

    // ================================================================
    // 五、缓存监控统计
    // ================================================================
    @Override
    public Map<String, Object> getCacheStats() {
        Map<String, Object> stats = statsService.getStats();
        // Redis 中缓存 key 数量（SCAN 替代 KEYS）
        stats.put("cacheKeyCount", safeRedis.scanKeys(cacheProperties.getPrefix() + ":*").size());
        stats.put("productTotal", productMapper.countAll());
        stats.put("firstDeleteFailures", delayDeleteService.getFirstDeleteFailures());
        stats.put("secondDeleteFailures", delayDeleteService.getSecondDeleteFailures());
        return stats;
    }

    @Override
    public int clearAllCache() {
        List<String> keys = safeRedis.scanKeys(cacheProperties.getPrefix() + ":*");
        if (keys.isEmpty()) {
            return 0;
        }
        Long deleted = safeRedis.degrade(() -> redisTemplate.delete(keys), () -> 0L);
        log.info("[监控] 已清除所有商品缓存，共 {} 条", deleted);
        return deleted == null ? 0 : deleted.intValue();
    }

    @Override
    public void clearCache(Long id) {
        safeRedis.tryRun(() -> redisTemplate.delete(buildKey(id)));
        log.info("[监控] 已清除商品 {} 的缓存", id);
    }

    @Override
    public void evictCache(Long id) {
        delayDeleteService.evictWithDelay(buildKey(id));
        log.info("[订单联动] 商品 {} 库存已变更，触发延迟双删失效缓存", id);
    }

    /** 公开摘要：只暴露演示需要的性能指标，内部运维计数（key 数/降级/删失败）留给管理员接口 */
    private static final List<String> PUBLIC_STAT_KEYS = List.of(
            "hitCount", "missCount", "totalRequests", "hitRate",
            "cacheAvgMs", "dbAvgMs", "cachePathCount", "dbPathCount", "productTotal");

    @Override
    public Map<String, Object> getCacheSummary() {
        Map<String, Object> full = getCacheStats();
        Map<String, Object> summary = new HashMap<>(PUBLIC_STAT_KEYS.size() * 2);
        for (String key : PUBLIC_STAT_KEYS) {
            if (full.containsKey(key)) {
                summary.put(key, full.get(key));
            }
        }
        // 降级状态本身不敏感，但"处于降级"这件事对演示页有用
        if (full.containsKey("degraded")) {
            summary.put("degraded", full.get("degraded"));
        }
        return summary;
    }
}
