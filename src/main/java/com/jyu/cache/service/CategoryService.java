package com.jyu.cache.service;

import com.jyu.cache.entity.Category;

import java.util.List;

/**
 * 商品分类 Service 接口
 */
public interface CategoryService {

    List<Category> listAll();

    Category getById(Long id);
}
