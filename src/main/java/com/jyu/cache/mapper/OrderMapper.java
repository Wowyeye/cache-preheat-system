package com.jyu.cache.mapper;

import com.jyu.cache.entity.Order;
import com.jyu.cache.entity.OrderItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单表 Mapper（v3：新增超时订单查询与状态机式更新）
 */
@Mapper
public interface OrderMapper {

    int insert(Order order);

    int insertItem(OrderItem item);

    Order selectById(@Param("id") Long id);

    Order selectByOrderNo(@Param("orderNo") String orderNo);

    List<Order> selectByUserId(@Param("userId") Long userId);

    List<Order> selectAll();

    /**
     * 【v3】状态机式更新：仅当当前状态等于 expectedStatus 时才更新为新状态（并发安全）
     *
     * 已删除无条件版本 updateStatus(id, status, ...)：先查后无条件改是 TOCTOU 双回补的根因，
     * 保留它迟早会被重新用上，所以直接从接口和 XML 里移除。
     * pay_time 仅在支付流转时传入（其余为 null，保持原值不动）。
     */
    int updateStatusIf(@Param("id") Long id,
                       @Param("newStatus") String newStatus,
                       @Param("remark") String remark,
                       @Param("expectedStatus") String expectedStatus,
                       @Param("payTime") LocalDateTime payTime,
                       @Param("finishTime") LocalDateTime finishTime);

    /** 【v3】查询超时未支付订单：状态为待支付且创建时间早于 deadline */
    List<Order> selectTimeoutPending(@Param("deadline") LocalDateTime deadline, @Param("limit") int limit);

    List<OrderItem> selectItemsByOrderId(@Param("orderId") Long orderId);
}
