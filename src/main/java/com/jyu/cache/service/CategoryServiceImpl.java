package com.jyu.cache.service;

import com.jyu.cache.entity.Category;
import com.jyu.cache.mapper.CategoryMapper;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

/**
 * 商品分类 Service 实现类
 */
@Service
public class CategoryServiceImpl implements CategoryService {

    @Resource
    private CategoryMapper categoryMapper;

    @Override
    public List<Category> listAll() {
        return categoryMapper.selectAll();
    }

    @Override
    public Category getById(Long id) {
        return categoryMapper.selectById(id);
    }
}
