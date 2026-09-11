package com.jyu.cache.entity;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 订单明细实体类（对应 order_item 表）
 *
 * 商品快照设计：下单时冻结商品名称和单价，后续商品改价/改名不影响历史订单展示。
 */
@Data
public class OrderItem {

    private Long id;

    private Long orderId;

    private Long productId;

    /** 商品名称快照（下单时冻结） */
    private String productName;

    /** 成交单价快照（下单时冻结） */
    private BigDecimal productPrice;

    /** 购买数量 */
    private Integer quantity;

    /** 小计 = 单价 × 数量 */
    private BigDecimal subtotal;
}
