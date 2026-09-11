package com.jyu.cache.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 商品分类实体类
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Category {

    private Long id;

    private String name;

    private Integer sort;
}
