package com.jyu.cache.service;

import com.jyu.cache.entity.Product;

import java.util.List;
import java.util.Map;

/**
 * 商品 Service 接口（v3：新增分页查询）
 */
public interface ProductService {

    /**
     * 【缓存预热】将热点商品批量加载到 Redis
     * @return 预热的商品数量
     */
    int preheatCache();

    /**
     * 【Cache Aside 读】根据ID查询商品
     * 包含穿透防护（空值缓存）、击穿防护（分布式锁）、Redis 降级（直查 DB）
     * @return 商品对象，不存在返回 null
     */
    Product getById(Long id);

    /**
     * 查询所有商品（管理列表用，数据量小）
     */
    List<Product> listAll();

    /**
     * 【v3 新增】分页 + 关键字查询
     * @param page    页码（从 1 开始）
     * @param size    每页条数（上限 100）
     * @param keyword 商品名关键字（可空）
     * @return {list, total, page, size}
     */
    Map<String, Object> listPage(int page, int size, String keyword);

    /**
     * 【Cache Aside 写 + 延迟双删】新增商品
     */
    void add(Product product);

    /**
     * 【Cache Aside 写 + 延迟双删】更新商品
     * @return 是否命中并更新了记录（false = 商品不存在，Controller 据此返回 404）
     */
    boolean update(Product product);

    /**
     * 【Cache Aside 写 + 延迟双删】删除商品（同步清理热度榜）
     * @return 是否真的删除了记录（false = 商品不存在，Controller 据此返回 404）
     */
    boolean delete(Long id);

    /**
     * 【缓存监控】获取缓存统计信息（指标存 Redis，多实例聚合）
     * 含内部运维指标（缓存 key 数、降级/删失败计数），只对管理员开放
     */
    Map<String, Object> getCacheStats();

    /**
     * 【缓存监控·公开】只读摘要：命中率、命中/未命中次数、缓存 vs DB 耗时
     * 不含任何内部运维计数，游客可看（监控大盘 / 耗时对比页对游客开放）
     */
    Map<String, Object> getCacheSummary();

    /**
     * 【缓存监控】手动清除所有商品缓存（SCAN）
     */
    int clearAllCache();

    /**
     * 【缓存监控】手动清除指定商品的缓存
     */
    void clearCache(Long id);

    /**
     * 【订单联动】库存变更后失效商品缓存（延迟双删）
     */
    void evictCache(Long id);
}
