package com.jyu.cache.mapper;

import com.jyu.cache.entity.Product;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 商品 Mapper 接口（v3：新增分页查询）
 */
public interface ProductMapper {

    Product selectById(@Param("id") Long id);

    List<Product> selectAll();

    List<Product> selectHotProducts(@Param("limit") int limit);

    /** 【v3】分页查询（可带商品名关键字） */
    List<Product> selectPage(@Param("offset") int offset, @Param("size") int size, @Param("keyword") String keyword);

    /** 【v3】按关键字统计总数（配合分页） */
    long countByKeyword(@Param("keyword") String keyword);

    int insert(Product product);

    int updateById(Product product);

    int deleteById(@Param("id") Long id);

    int incrementViewCount(@Param("id") Long id);

    int countAll();

    /** 原子扣减库存：stock >= quantity 才扣，返回影响行数（0=库存不足） */
    int decreaseStock(@Param("id") Long id, @Param("quantity") int quantity);

    /** 回补库存（取消/退款/退货时） */
    int increaseStock(@Param("id") Long id, @Param("quantity") int quantity);
}
