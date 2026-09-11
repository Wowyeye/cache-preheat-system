package com.jyu.cache.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单实体类（对应 orders 表）
 *
 * 状态机（8 态）：
 * PENDING_PAYMENT 待支付 → PAID 已支付 → COMPLETED 已完成
 *                  ↘ CANCELLED 已取消（回补库存）
 * PAID → REFUNDING 退款中 → REFUNDED 已退款（回补库存）
 * PAID → RETURNING 退货中 → RETURNED 已退货（回补库存）
 */
@Data
public class Order {

    /** 待支付 */
    public static final String STATUS_PENDING_PAYMENT = "PENDING_PAYMENT";
    /** 已支付 */
    public static final String STATUS_PAID = "PAID";
    /** 已完成 */
    public static final String STATUS_COMPLETED = "COMPLETED";
    /** 已取消 */
    public static final String STATUS_CANCELLED = "CANCELLED";
    /** 退款中（用户已申请，待管理员审核） */
    public static final String STATUS_REFUNDING = "REFUNDING";
    /** 已退款（审核通过，钱退回，库存回补） */
    public static final String STATUS_REFUNDED = "REFUNDED";
    /** 退货中（用户已申请，待管理员审核） */
    public static final String STATUS_RETURNING = "RETURNING";
    /** 已退货（审核通过，货收回，库存回补） */
    public static final String STATUS_RETURNED = "RETURNED";

    private Long id;

    /** 订单号（业务唯一，时间戳+随机数生成） */
    private String orderNo;

    /** 下单用户ID */
    private Long userId;

    /** 订单总金额 */
    private BigDecimal totalAmount;

    /** 状态：见 STATUS_* 常量 */
    private String status;

    /** 备注（退款/退货原因） */
    private String remark;

    private LocalDateTime createTime;

    private LocalDateTime payTime;

    private LocalDateTime finishTime;

    /** 订单明细列表（查询时 JOIN 带出） */
    private List<OrderItem> items;
}
