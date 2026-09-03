package com.jyu.cache.mapper;

import com.jyu.cache.entity.Category;

import java.util.List;

/**
 * 商品分类 Mapper 接口
 */
public interface CategoryMapper {


    List<Category> selectAll();

    Category selectById(Long id);
}
