package com.jyu.cache.service;

import com.jyu.cache.entity.Product;

import java.util.List;
import java.util.Map;

/**
 * 商品 Service 接口
 */
public interface ProductService {

    /**
     * 【缓存预热】将热点商品批量加载到 Redis
     * 在项目启动时由 CachePreheatRunner 自动调用
     * @return 预热的商品数量
     */
    int preheatCache();

    /**
     * 【Cache Aside 读】根据ID查询商品
     * 策略：先查缓存 -> 命中则返回 -> 未命中查数据库 -> 写入缓存
     * 包含缓存穿透防护（空值缓存）和缓存击穿防护（互斥锁）
     * @param id 商品ID
     * @return 商品对象，不存在返回 null
     */
    Product getById(Long id);

    /**
     * 查询所有商品（不走缓存，直接查数据库，用于管理列表）
     * @return 商品列表
     */
    List<Product> listAll();

    /**
     * 【Cache Aside 写 + 延迟双删】新增商品
     * 新增后需要清除可能存在的空值缓存
     * @param product 商品对象
     */
    void add(Product product);

    /**
     * 【Cache Aside 写 + 延迟双删】更新商品
     * 策略：先更新数据库 -> 删除缓存 -> 延迟一段时间 -> 再次删除缓存
     * @param product 商品对象
     */
    void update(Product product);

    /**
     * 【Cache Aside 写 + 延迟双删】删除商品
     * 删除数据库记录 -> 删除缓存 -> 延迟 -> 再次删除缓存
     * @param id 商品ID
     */
    void delete(Long id);

    /**
     * 【缓存监控】获取缓存统计信息
     * @return 包含缓存命中数、未命中数、命中率、缓存key数量等
     */
    Map<String, Object> getCacheStats();

    /**
     * 【缓存监控】手动清除所有商品缓存
     * @return 清除的缓存数量
     */
    int clearAllCache();

    /**
     * 【缓存监控】手动清除指定商品的缓存
     * @param id 商品ID
     */
    void clearCache(Long id);
}
