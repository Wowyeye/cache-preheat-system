package com.jyu.cache.mapper;

import com.jyu.cache.entity.Category;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 商品分类 Mapper 接口
 */
@Mapper
public interface CategoryMapper {

    List<Category> selectAll();

    Category selectById(Long id);
}
