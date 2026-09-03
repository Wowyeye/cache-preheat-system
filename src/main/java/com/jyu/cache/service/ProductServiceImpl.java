package com.jyu.cache.service;

import com.jyu.cache.config.CacheProperties;
import com.jyu.cache.entity.Product;
import com.jyu.cache.mapper.ProductMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 商品 Service 实现类 —— 本系统核心代码
 *
 * ================================================================
 * 一、缓存预热（Cache Preheating）
 * ================================================================
 * 项目启动时，将浏览量最高的 N 条商品提前加载到 Redis。
 * 目的：避免用户首次访问热点商品时缓存未命中导致大量请求打到数据库。
 *
 * ================================================================
 * 二、Cache Aside 旁路缓存模式（读策略）
 * ================================================================
 * 查询流程：
 *   1. 先查 Redis 缓存
 *   2. 命中 -> 直接返回（缓存命中）
 *   3. 未命中 -> 查数据库
 *   4. 数据库有数据 -> 写入 Redis 缓存 -> 返回
 *   5. 数据库无数据 -> 缓存空值（防穿透）-> 返回 null
 *
 * ================================================================
 * 三、缓存一致性保障（写策略：延迟双删）
 * ================================================================
 * 更新流程：
 *   1. 更新数据库
 *   2. 删除 Redis 缓存（第一次删除）
 *   3. 延迟 1.5 秒（等读请求把旧数据写回缓存的窗口期过去）
 *   4. 再次删除 Redis 缓存（第二次删除，清除可能被旧数据污染的缓存）
 *
 * 为什么需要延迟双删？
 *   - 第一次删除后，可能有一个「读请求」恰好查到了数据库旧数据并写回了缓存
 *   - 第二次删除就能清掉这个脏数据，保证最终一致性
 *
 * ================================================================
 * 四、缓存穿透防护（Cache Penetration）
 * ================================================================
 * 问题：查询一个数据库中根本不存在的数据，每次都穿透到数据库。
 * 方案：数据库查不到时，缓存一个空值标记（NULL_VALUE），设置较短TTL（60秒）。
 *      下次查询相同 key 时命中空值标记，直接返回 null，不再查数据库。
 *
 * ================================================================
 * 五、缓存击穿防护（Cache Breakdown）
 * ================================================================
 * 问题：热点 key 过期的瞬间，大量请求同时打到数据库。
 * 方案：缓存未命中时，使用互斥锁（synchronized）保证只有一个线程查数据库，
 *      其他线程等待或直接返回旧缓存。本系统使用简单的本地锁。
 *
 * ================================================================
 * 六、缓存雪崩防护（Cache Avalanche）
 * ================================================================
 * 问题：大量缓存 key 在同一时刻过期，所有请求打到数据库。
 * 方案：给缓存过期时间加上随机值（0~300秒），让 key 分散过期。
 */
@Slf4j
@Service
public class ProductServiceImpl implements ProductService {

    @Resource
    private ProductMapper productMapper;

    @Resource
    private RedisTemplate<String, Object> redisTemplate;

    @Resource
    private CacheProperties cacheProperties;

    /** 空值标记：用于缓存穿透防护，代表“数据库中不存在这条数据” */
    private static final String NULL_VALUE = "NULL_VALUE_MARKER";

    /** 缓存击穿防护：本地互斥锁（按商品ID加锁，粒度更细） */
    private final Object lock = new Object();

    /** 缓存监控：命中次数 */
    private final AtomicLong hitCount = new AtomicLong(0);

    /** 缓存监控：未命中次数 */
    private final AtomicLong missCount = new AtomicLong(0);

    /** 缓存监控：总请求次数 */
    private final AtomicLong totalRequests = new AtomicLong(0);

    // ================================================================
    // 工具方法：拼接 Redis Key（统一前缀，方便管理和统计）
    // ================================================================
    private String buildKey(Long id) {
        return cacheProperties.getPrefix() + ":" + id;
    }

    // ================================================================
    // 一、缓存预热
    // ================================================================
    @Override
    public int preheatCache() {
        log.info("===== 开始缓存预热，加载前 {} 条热点商品 =====", cacheProperties.getPreheatCount());
        List<Product> hotProducts = productMapper.selectHotProducts(cacheProperties.getPreheatCount());

        for (Product product : hotProducts) {
            String key = buildKey(product.getId());
            // 写入缓存，TTL 加随机值防雪崩
            long ttl = randomTtl();
            redisTemplate.opsForValue().set(key, product, ttl, TimeUnit.SECONDS);
            log.info("预热商品: id={}, name={}, viewCount={}, TTL={}s",
                    product.getId(), product.getName(), product.getViewCount(), ttl);
        }

        log.info("===== 缓存预热完成，共预热 {} 条商品 =====", hotProducts.size());
        return hotProducts.size();
    }

    /**
     * 计算带随机值的过期时间（防雪崩）
     * 基础 TTL + 0~300 秒随机值
     */
    private long randomTtl() {
        return cacheProperties.getTtl() + (long) (Math.random() * 300);
    }

    // ================================================================
    // 二、Cache Aside 读策略（含穿透/击穿防护）
    // ================================================================
    @Override
    public List<Product> listAll() {
        // 管理列表查询：不走缓存，直接查数据库（保证数据实时性）
        return productMapper.selectAll();
    }

    @Override
    public Product getById(Long id) {
        totalRequests.incrementAndGet();
        String key = buildKey(id);

        // --- 第一步：先查缓存 ---
        Object cached = redisTemplate.opsForValue().get(key);

        // 命中空值标记 -> 数据库不存在该数据，直接返回 null（防穿透）
        if (NULL_VALUE.equals(cached)) {
            hitCount.incrementAndGet();
            log.debug("[查询] 商品 {} 命中空值标记，直接返回 null（穿透防护）", id);
            return null;
        }

        // 命中真实数据 -> 直接返回
        if (cached != null) {
            hitCount.incrementAndGet();
            log.debug("[查询] 商品 {} 缓存命中", id);
            return (Product) cached;
        }

        // --- 第二步：未命中 -> 加锁查数据库（防击穿） ---
        missCount.incrementAndGet();
        log.info("[查询] 商品 {} 缓存未命中，尝试加锁查数据库", id);

        synchronized (lock) {
            // 双重检查：可能在等锁期间，别的线程已经把数据写回缓存了
            cached = redisTemplate.opsForValue().get(key);
            if (NULL_VALUE.equals(cached)) {
                return null;
            }
            if (cached != null) {
                return (Product) cached;
            }

            // 真正查数据库
            Product product = productMapper.selectById(id);

            if (product != null) {
                // 数据库有 -> 写入缓存 -> 返回
                redisTemplate.opsForValue().set(key, product, randomTtl(), TimeUnit.SECONDS);
                log.info("[查询] 商品 {} 数据库查到，已写入缓存", id);
                return product;
            } else {
                // 数据库无 -> 缓存空值标记（短TTL，防穿透）
                redisTemplate.opsForValue().set(key, NULL_VALUE, cacheProperties.getNullTtl(), TimeUnit.SECONDS);
                log.info("[查询] 商品 {} 数据库不存在，已缓存空值标记（穿透防护）", id);
                return null;
            }
        }
    }

    // ================================================================
    // 三、写操作 + 延迟双删
    // ================================================================
    @Override
    public void add(Product product) {
        // 新增商品，viewCount 默认为 0
        if (product.getViewCount() == null) {
            product.setViewCount(0L);
        }
        productMapper.insert(product);
        // 新增后清除可能存在的空值缓存（之前查过这个不存在的ID）
        redisTemplate.delete(buildKey(product.getId()));
        log.info("[新增] 商品 {} 已入库并清除空值缓存", product.getId());
    }

    @Override
    public void update(Product product) {
        productMapper.updateById(product);
        deleteCacheWithDelayDelete(product.getId());
        log.info("[更新] 商品 {} 已更新并触发延迟双删", product.getId());
    }

    @Override
    public void delete(Long id) {
        productMapper.deleteById(id);
        deleteCacheWithDelayDelete(id);
        log.info("[删除] 商品 {} 已删除并触发延迟双删", id);
    }

    /**
     * 延迟双删：更新数据库后
     *   1. 立即删除缓存（第一次）
     *   2. 延迟 delay-delete-ms 毫秒
     *   3. 再次删除缓存（第二次）
     */
    private void deleteCacheWithDelayDelete(Long id) {
        String key = buildKey(id);

        // 第一次删除
        redisTemplate.delete(key);
        log.info("[延迟双删] 商品 {} 第一次删除缓存完成", id);

        // 延迟后第二次删除（异步执行，不阻塞主流程）
        new Thread(() -> {
            try {
                Thread.sleep(cacheProperties.getDelayDeleteMs());
                redisTemplate.delete(key);
                log.info("[延迟双删] 商品 {} 第二次删除缓存完成（延迟 {} ms）", id, cacheProperties.getDelayDeleteMs());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("[延迟双删] 商品 {} 第二次删除被中断", id);
            }
        }, "delay-delete-" + id).start();
    }

    // ================================================================
    // 四、缓存监控统计
    // ================================================================
    @Override
    public Map<String, Object> getCacheStats() {
        Map<String, Object> stats = new HashMap<>(8);
        long hit = hitCount.get();
        long miss = missCount.get();
        long total = totalRequests.get();

        stats.put("hitCount", hit);
        stats.put("missCount", miss);
        stats.put("totalRequests", total);
        if (hit + miss == 0) {
            stats.put("hitRate", "0.00%");
        } else {
            stats.put("hitRate", String.format("%.2f%%", hit * 100.0 / (hit + miss)));
        }

        // Redis 中缓存 key 数量（按前缀统计）
        java.util.Set<String> keys = redisTemplate.keys(cacheProperties.getPrefix() + ":*");
        stats.put("cacheKeyCount", keys == null ? 0 : keys.size());
        stats.put("productTotal", productMapper.countAll());

        return stats;
    }

    @Override
    public int clearAllCache() {
        java.util.Set<String> keys = redisTemplate.keys(cacheProperties.getPrefix() + ":*");
        if (keys == null || keys.isEmpty()) {
            return 0;
        }
        Long deleted = redisTemplate.delete(keys);
        log.info("[监控] 已清除所有商品缓存，共 {} 条", deleted);
        return deleted == null ? 0 : deleted.intValue();
    }

    @Override
    public void clearCache(Long id) {
        redisTemplate.delete(buildKey(id));
        log.info("[监控] 已清除商品 {} 的缓存", id);
    }
}
