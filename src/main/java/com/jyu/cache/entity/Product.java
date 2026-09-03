package com.jyu.cache.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商品实体类
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Product {

    private Long id;

    private String name;

    private Long categoryId;

    private BigDecimal price;

    private Integer stock;

    private String description;

    private Integer status;

    private Long viewCount;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
