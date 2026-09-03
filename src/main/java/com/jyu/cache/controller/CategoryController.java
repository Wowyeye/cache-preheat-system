package com.jyu.cache.controller;

import com.jyu.cache.common.Result;
import com.jyu.cache.entity.Category;
import com.jyu.cache.service.CategoryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.util.List;

/**
 * 商品分类 Controller
 *
 * 提供分类列表查询接口，供前端下拉框使用。
 */
@RestController
@RequestMapping("/api/category")
public class CategoryController {

    @Resource
    private CategoryService categoryService;

    /**
     * 查询所有分类
     * 前端访问 GET /api/category/list
     */
    @GetMapping("/list")
    public Result<List<Category>> list() {
        return Result.success(categoryService.listAll());
    }
}
