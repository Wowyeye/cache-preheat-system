package com.jyu.cache.controller;

import com.jyu.cache.common.Result;
import com.jyu.cache.entity.Category;
import com.jyu.cache.service.CategoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.Resource;
import java.util.List;

/**
 * 商品分类 Controller
 */
@Tag(name = "分类", description = "商品分类列表")
@RestController
@RequestMapping("/api/category")
public class CategoryController {

    @Resource
    private CategoryService categoryService;

    @Operation(summary = "商品分类列表（游客可读）", description = "返回全部分类")
    @GetMapping("/list")
    public Result<List<Category>> list() {
        return Result.success(categoryService.listAll());
    }
}
