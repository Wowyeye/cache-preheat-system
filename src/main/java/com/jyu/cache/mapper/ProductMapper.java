package com.jyu.cache.mapper;

import com.jyu.cache.entity.Product;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 商品 Mapper 接口
 */
public interface ProductMapper {


    Product selectById(@Param("id") Long id);


    List<Product> selectAll();


    List<Product> selectHotProducts(@Param("limit") int limit);

    int insert(Product product);

    int updateById(Product product);

    int deleteById(@Param("id") Long id);

    int incrementViewCount(@Param("id") Long id);

    int countAll();
}
