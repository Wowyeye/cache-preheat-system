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

    int updateStatus(@Param("id") Long id,
                     @Param("status") String status,
                     @Param("remark") String remark,
                     @Param("payTime") LocalDateTime payTime,
                     @Param("finishTime") LocalDateTime finishTime);

    /** 【v3】状态机式更新：仅当当前状态等于 expectedStatus 时才更新为新状态（并发安全） */
    int updateStatusIf(@Param("id") Long id,
                       @Param("newStatus") String newStatus,
                       @Param("remark") String remark,
                       @Param("expectedStatus") String expectedStatus,
                       @Param("finishTime") LocalDateTime finishTime);

    /** 【v3】查询超时未支付订单：状态为待支付且创建时间早于 deadline */
    List<Order> selectTimeoutPending(@Param("deadline") LocalDateTime deadline, @Param("limit") int limit);

    List<OrderItem> selectItemsByOrderId(@Param("orderId") Long orderId);
}
