package com.jyu.cache.controller;

import com.jyu.cache.common.BusinessException;
import com.jyu.cache.common.Result;
import com.jyu.cache.common.UserContext;
import com.jyu.cache.entity.Product;
import com.jyu.cache.service.ProductService;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;
import java.util.List;
import java.util.Map;

/**
 * 商品 Controller（v3：新增分页接口）
 *
 * 接口列表：
 *   GET    /api/product/{id}          查询商品详情（走缓存）
 *   GET    /api/product/list          查询所有商品（数据量小，管理下拉用）
 *   GET    /api/product/page          分页 + 关键字查询（v3 新增）
 *   POST   /api/product               新增商品（ADMIN）
 *   PUT    /api/product               更新商品（ADMIN，触发延迟双删）
 *   DELETE /api/product/{id}          删除商品（ADMIN，触发延迟双删 + 榜单清理）
 *   GET    /api/product/cache/summary 缓存摘要（公开：命中率/耗时等演示指标）
 *   GET    /api/product/cache/stats   缓存完整统计（ADMIN：含 key 数、降级/删失败计数）
 *   DELETE /api/product/cache/all     清除所有缓存（ADMIN，SCAN）
 *   DELETE /api/product/cache/{id}    清除指定商品缓存（ADMIN）
 *   POST   /api/product/preheat       手动触发缓存预热（ADMIN）
 */
@RestController
@RequestMapping("/api/product")
public class ProductController {

    @Resource
    private ProductService productService;

    @GetMapping("/{id}")
    public Result<Product> getById(@PathVariable Long id) {
        Product product = productService.getById(id);
        if (product == null) {
            return Result.fail(404, "商品不存在");
        }
        return Result.success(product);
    }

    @GetMapping("/list")
    public Result<List<Product>> list() {
        return Result.success(productService.listAll());
    }

    /** 分页查询：page 从 1 开始，size 上限 100，keyword 模糊匹配商品名 */
    @GetMapping("/page")
    public Result<Map<String, Object>> page(@RequestParam(defaultValue = "1") int page,
                                            @RequestParam(defaultValue = "10") int size,
                                            @RequestParam(required = false) String keyword) {
        return Result.success(productService.listPage(page, size, keyword));
    }

    @PostMapping
    public Result<Void> add(@RequestBody Product product) {
        UserContext.requireAdmin();
        productService.add(product);
        return Result.success("新增成功", null);
    }

    @PutMapping
    public Result<Void> update(@RequestBody Product product) {
        UserContext.requireAdmin();
        if (product.getId() == null) {
            return Result.fail("商品ID不能为空");
        }
        if (!productService.update(product)) {
            throw new BusinessException(404, "商品不存在，更新失败");
        }
        return Result.success("更新成功，已触发延迟双删策略", null);
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        UserContext.requireAdmin();
        if (!productService.delete(id)) {
            throw new BusinessException(404, "商品不存在，删除失败");
        }
        return Result.success("删除成功，已触发延迟双删策略", null);
    }

    /** 公开摘要：命中率、命中/未命中、缓存 vs DB 耗时（游客可见，供监控大盘/耗时对比页） */
    @GetMapping("/cache/summary")
    public Result<Map<String, Object>> cacheSummary() {
        return Result.success(productService.getCacheSummary());
    }

    /** 完整统计：额外含缓存 key 数、降级次数、双删失败次数等内部运维指标 -> 仅管理员 */
    @GetMapping("/cache/stats")
    public Result<Map<String, Object>> cacheStats() {
        UserContext.requireAdmin();
        return Result.success(productService.getCacheStats());
    }

    @DeleteMapping("/cache/all")
    public Result<Void> clearAllCache() {
        UserContext.requireAdmin();
        int count = productService.clearAllCache();
        return Result.success("已清除 " + count + " 条缓存", null);
    }

    @DeleteMapping("/cache/{id}")
    public Result<Void> clearCache(@PathVariable Long id) {
        UserContext.requireAdmin();
        productService.clearCache(id);
        return Result.success("已清除该商品缓存", null);
    }

    @PostMapping("/preheat")
    public Result<Void> preheat() {
        UserContext.requireAdmin();
        int count = productService.preheatCache();
        return Result.success("缓存预热完成，共加载 " + count + " 条热点商品", null);
    }
}
