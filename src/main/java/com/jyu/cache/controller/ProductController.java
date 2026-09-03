package com.jyu.cache.controller;

import com.jyu.cache.common.Result;
import com.jyu.cache.entity.Product;
import com.jyu.cache.service.ProductService;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.List;
import java.util.Map;

/**
 * 商品 Controller
 *
 * 提供商品 CRUD 的 REST API + 缓存监控相关的 API。
 * 所有接口返回统一的 Result 格式。
 *
 * 接口列表：
 *   GET    /api/product/{id}       查询商品详情（走缓存）
 *   GET    /api/product/list       查询所有商品（不走缓存）
 *   POST   /api/product            新增商品
 *   PUT    /api/product            更新商品（触发延迟双删）
 *   DELETE /api/product/{id}       删除商品（触发延迟双删）
 *   GET    /api/product/cache/stats  缓存统计信息
 *   DELETE /api/product/cache/all    清除所有缓存
 *   DELETE /api/product/cache/{id}   清除指定商品缓存
 *   POST   /api/product/preheat    手动触发缓存预热
 */
@RestController
@RequestMapping("/api/product")
public class ProductController {

    @Resource
    private ProductService productService;

    /**
     * 查询商品详情（走 Cache Aside 缓存）
     */
    @GetMapping("/{id}")
    public Result<Product> getById(@PathVariable Long id) {
        Product product = productService.getById(id);
        if (product == null) {
            return Result.fail(404, "商品不存在");
        }
        return Result.success(product);
    }

    /**
     * 查询所有商品（不走缓存，直接查数据库，用于管理列表）
     */
    @GetMapping("/list")
    public Result<List<Product>> list() {
        return Result.success(productService.listAll());
    }

    /**
     * 新增商品（清除空值缓存）
     */
    @PostMapping
    public Result<Void> add(@RequestBody Product product) {
        productService.add(product);
        return Result.success("新增成功", null);
    }

    /**
     * 更新商品（触发延迟双删）
     */
    @PutMapping
    public Result<Void> update(@RequestBody Product product) {
        if (product.getId() == null) {
            return Result.fail("商品ID不能为空");
        }
        productService.update(product);
        return Result.success("更新成功，已触发延迟双删策略", null);
    }

    /**
     * 删除商品（触发延迟双删）
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        productService.delete(id);
        return Result.success("删除成功，已触发延迟双删策略", null);
    }

    /**
     * 缓存统计信息（命中数、未命中数、命中率、key数量等）
     */
    @GetMapping("/cache/stats")
    public Result<Map<String, Object>> cacheStats() {
        return Result.success(productService.getCacheStats());
    }

    /**
     * 清除所有商品缓存
     */
    @DeleteMapping("/cache/all")
    public Result<Void> clearAllCache() {
        int count = productService.clearAllCache();
        return Result.success("已清除 " + count + " 条缓存", null);
    }

    /**
     * 清除指定商品的缓存
     */
    @DeleteMapping("/cache/{id}")
    public Result<Void> clearCache(@PathVariable Long id) {
        productService.clearCache(id);
        return Result.success("已清除该商品缓存", null);
    }

    /**
     * 手动触发缓存预热
     */
    @PostMapping("/preheat")
    public Result<Void> preheat() {
        int count = productService.preheatCache();
        return Result.success("缓存预热完成，共加载 " + count + " 条热点商品", null);
    }
}
