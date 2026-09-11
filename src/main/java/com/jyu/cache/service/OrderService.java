package com.jyu.cache.service;

import com.jyu.cache.common.BusinessException;
import com.jyu.cache.common.UserContext;
import com.jyu.cache.entity.Order;
import com.jyu.cache.entity.OrderItem;
import com.jyu.cache.entity.Product;
import com.jyu.cache.mapper.OrderMapper;
import com.jyu.cache.mapper.ProductMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 订单服务（v3：新增待支付超时自动取消）
 *
 * 状态机（8 态）：
 *   PENDING_PAYMENT --支付--> PAID --确认收货--> COMPLETED
 *        |                     |--申请退款--> REFUNDING --审核通过--> REFUNDED（回补库存）
 *        |                     |--申请退货--> RETURNING --审核通过--> RETURNED（回补库存）
 *        |--取消--> CANCELLED（回补库存）
 *
 * v3 新增：OrderTimeoutScheduler 每 60s 扫描超时未支付的订单自动取消并回补库存，
 *          解决 v2"待支付订单永久占用库存"的问题。
 *
 * 缓存一致性（项目核心卖点）：库存的每次变更（扣减/回补）都触发延迟双删。
 */
@Slf4j
@Service
public class OrderService {

    private final OrderMapper orderMapper;
    private final ProductMapper productMapper;
    private final ProductService productService;

    public OrderService(OrderMapper orderMapper, ProductMapper productMapper, ProductService productService) {
        this.orderMapper = orderMapper;
        this.productMapper = productMapper;
        this.productService = productService;
    }

    // ==================== 下单 ====================

    /**
     * 下单：校验商品 → 原子扣库存 → 生成订单+明细（快照价格）→ 延迟双删商品缓存
     */
    @Transactional(rollbackFor = Exception.class)
    public Order createOrder(Long userId, List<Map<String, Object>> items, String remark) {
        if (items == null || items.isEmpty()) {
            throw new BusinessException(400, "购买清单不能为空");
        }

        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, Object> item : items) {
            Long productId = Long.valueOf(String.valueOf(item.get("productId")));
            int quantity = Integer.parseInt(String.valueOf(item.get("quantity")));
            if (quantity <= 0) {
                throw new BusinessException(400, "购买数量必须大于 0");
            }
            Product product = productMapper.selectById(productId);
            if (product == null || product.getStatus() == null || product.getStatus() != 1) {
                throw new BusinessException(404, "商品不存在或已下架");
            }
            // 原子扣减：WHERE stock >= quantity，0 行受影响即库存不足
            if (productMapper.decreaseStock(productId, quantity) == 0) {
                throw new BusinessException(409, "商品「" + product.getName() + "」库存不足，当前库存 " + product.getStock());
            }
            total = total.add(product.getPrice().multiply(BigDecimal.valueOf(quantity)));
            productService.evictCache(productId);
        }

        Order order = new Order();
        order.setOrderNo(generateOrderNo());
        order.setUserId(userId);
        order.setTotalAmount(total);
        order.setStatus(Order.STATUS_PENDING_PAYMENT);
        order.setRemark(remark == null ? "" : remark);
        orderMapper.insert(order);

        for (Map<String, Object> item : items) {
            Long productId = Long.valueOf(String.valueOf(item.get("productId")));
            int quantity = Integer.parseInt(String.valueOf(item.get("quantity")));
            Product product = productMapper.selectById(productId);
            OrderItem orderItem = new OrderItem();
            orderItem.setOrderId(order.getId());
            orderItem.setProductId(productId);
            orderItem.setProductName(product.getName());
            orderItem.setProductPrice(product.getPrice());
            orderItem.setQuantity(quantity);
            orderItem.setSubtotal(product.getPrice().multiply(BigDecimal.valueOf(quantity)));
            orderMapper.insertItem(orderItem);
        }

        log.info("[订单创建] orderNo={} userId={} total={} items={}", order.getOrderNo(), userId, total, items.size());
        return orderMapper.selectById(order.getId());
    }

    // ==================== 状态流转 ====================

    /** 支付：待支付 → 已支付 */
    @Transactional(rollbackFor = Exception.class)
    public Order pay(Long userId, Long orderId) {
        Order order = getOwnedOrder(userId, orderId);
        checkStatus(order, Order.STATUS_PENDING_PAYMENT, "只有待支付订单才能支付");
        orderMapper.updateStatus(order.getId(), Order.STATUS_PAID, order.getRemark(), LocalDateTime.now(), null);
        log.info("[订单支付] orderNo={}", order.getOrderNo());
        return orderMapper.selectById(order.getId());
    }

    /** 取消：待支付 → 已取消，回补库存 */
    @Transactional(rollbackFor = Exception.class)
    public Order cancel(Long userId, Long orderId) {
        Order order = getOwnedOrder(userId, orderId);
        checkStatus(order, Order.STATUS_PENDING_PAYMENT, "只有待支付订单才能取消");
        orderMapper.updateStatus(order.getId(), Order.STATUS_CANCELLED,
                order.getRemark() == null || order.getRemark().isEmpty() ? "用户主动取消" : order.getRemark(),
                null, LocalDateTime.now());
        restoreStock(order);
        log.info("[订单取消] orderNo={} 库存已回补", order.getOrderNo());
        return orderMapper.selectById(order.getId());
    }

    /** 申请退款：已支付 → 退款中（未发货场景） */
    @Transactional(rollbackFor = Exception.class)
    public Order applyRefund(Long userId, Long orderId, String reason) {
        Order order = getOwnedOrder(userId, orderId);
        checkStatus(order, Order.STATUS_PAID, "只有已支付订单才能申请退款");
        orderMapper.updateStatus(order.getId(), Order.STATUS_REFUNDING, reason, null, null);
        log.info("[申请退款] orderNo={} reason={}", order.getOrderNo(), reason);
        return orderMapper.selectById(order.getId());
    }

    /** 申请退货：已支付 → 退货中（已发货/已收货场景） */
    @Transactional(rollbackFor = Exception.class)
    public Order applyReturn(Long userId, Long orderId, String reason) {
        Order order = getOwnedOrder(userId, orderId);
        checkStatus(order, Order.STATUS_PAID, "只有已支付订单才能申请退货");
        orderMapper.updateStatus(order.getId(), Order.STATUS_RETURNING, reason, null, null);
        log.info("[申请退货] orderNo={} reason={}", order.getOrderNo(), reason);
        return orderMapper.selectById(order.getId());
    }

    /** 管理员审核退款：退款中 → 已退款，回补库存 */
    @Transactional(rollbackFor = Exception.class)
    public Order approveRefund(Long orderId) {
        Order order = getOrder(orderId);
        checkStatus(order, Order.STATUS_REFUNDING, "该订单不在退款审核状态");
        orderMapper.updateStatus(order.getId(), Order.STATUS_REFUNDED, order.getRemark(), null, LocalDateTime.now());
        restoreStock(order);
        log.info("[退款通过] orderNo={} 库存已回补", order.getOrderNo());
        return orderMapper.selectById(order.getId());
    }

    /** 管理员审核退货：退货中 → 已退货，回补库存 */
    @Transactional(rollbackFor = Exception.class)
    public Order approveReturn(Long orderId) {
        Order order = getOrder(orderId);
        checkStatus(order, Order.STATUS_RETURNING, "该订单不在退货审核状态");
        orderMapper.updateStatus(order.getId(), Order.STATUS_RETURNED, order.getRemark(), null, LocalDateTime.now());
        restoreStock(order);
        log.info("[退货通过] orderNo={} 库存已回补", order.getOrderNo());
        return orderMapper.selectById(order.getId());
    }

    /** 管理员拒绝退款/退货：退回已支付状态 */
    @Transactional(rollbackFor = Exception.class)
    public Order rejectAudit(Long orderId, String reason) {
        Order order = getOrder(orderId);
        if (!Order.STATUS_REFUNDING.equals(order.getStatus()) && !Order.STATUS_RETURNING.equals(order.getStatus())) {
            throw new BusinessException(409, "该订单不在审核状态");
        }
        String prefix = Order.STATUS_REFUNDING.equals(order.getStatus()) ? "退款被拒：" : "退货被拒：";
        orderMapper.updateStatus(order.getId(), Order.STATUS_PAID, prefix + reason, null, null);
        log.info("[审核拒绝] orderNo={} reason={}", order.getOrderNo(), reason);
        return orderMapper.selectById(order.getId());
    }

    // ==================== 超时自动取消（v3 新增） ====================

    /**
     * 取消所有超时未支付订单（由调度器周期调用，也可管理员手动触发）
     *
     * @param timeoutMinutes 超时分钟数
     * @return 取消的订单数
     */
    @Transactional(rollbackFor = Exception.class)
    public int cancelTimeoutOrders(int timeoutMinutes) {
        LocalDateTime deadline = LocalDateTime.now().minusMinutes(timeoutMinutes);
        List<Order> timeouts = orderMapper.selectTimeoutPending(deadline, 100);
        int cancelled = 0;
        for (Order order : timeouts) {
            try {
                // 状态机式更新：UPDATE ... WHERE status='PENDING_PAYMENT'，并发安全（同时只有一个赢）
                int updated = orderMapper.updateStatusIf(order.getId(), Order.STATUS_CANCELLED,
                        "超时未支付，系统自动取消", Order.STATUS_PENDING_PAYMENT, LocalDateTime.now());
                if (updated > 0) {
                    restoreStock(order);
                    cancelled++;
                    log.info("[超时取消] orderNo={} 超过 {} 分钟未支付，已自动取消并回补库存",
                            order.getOrderNo(), timeoutMinutes);
                }
            } catch (Exception e) {
                // 单个订单失败不阻断整批（事务内已回滚该单，继续处理下一单的语义由外层逐单事务保证）
                log.error("[超时取消] orderNo={} 处理失败：{}", order.getOrderNo(), e.getMessage());
            }
        }
        return cancelled;
    }

    // ==================== 查询 ====================

    public List<Order> myOrders(Long userId) {
        return orderMapper.selectByUserId(userId);
    }

    public List<Order> allOrders() {
        return orderMapper.selectAll();
    }

    public Order detail(Long userId, Long orderId) {
        Order order = getOrder(orderId);
        UserContext.LoginUser current = UserContext.getUser();
        if (!order.getUserId().equals(userId) && (current == null || !current.isAdmin())) {
            throw new BusinessException(403, "无权查看他人订单");
        }
        return order;
    }

    // ==================== 私有工具 ====================

    /** 回补库存 + 延迟双删缓存（取消/退款/退货共用） */
    private void restoreStock(Order order) {
        // 重查明细：订单对象可能由 selectTimeoutPending 查出、未带 items
        List<OrderItem> items = order.getItems() != null && !order.getItems().isEmpty()
                ? order.getItems()
                : orderMapper.selectItemsByOrderId(order.getId());
        for (OrderItem item : items) {
            productMapper.increaseStock(item.getProductId(), item.getQuantity());
            productService.evictCache(item.getProductId());
        }
    }

    private Order getOwnedOrder(Long userId, Long orderId) {
        Order order = getOrder(orderId);
        if (!order.getUserId().equals(userId)) {
            throw new BusinessException(403, "无权操作他人订单");
        }
        return order;
    }

    private Order getOrder(Long orderId) {
        Order order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException(404, "订单不存在");
        }
        return order;
    }

    private void checkStatus(Order order, String expected, String message) {
        if (!expected.equals(order.getStatus())) {
            throw new BusinessException(409, message + "，当前状态：" + statusText(order.getStatus()));
        }
    }

    private String generateOrderNo() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyMMddHHmmss"))
                + String.format("%06d", ThreadLocalRandom.current().nextInt(1000000));
    }

    public static String statusText(String status) {
        return switch (status) {
            case "PENDING_PAYMENT" -> "待支付";
            case "PAID" -> "已支付";
            case "COMPLETED" -> "已完成";
            case "CANCELLED" -> "已取消";
            case "REFUNDING" -> "退款中";
            case "REFUNDED" -> "已退款";
            case "RETURNING" -> "退货中";
            case "RETURNED" -> "已退货";
            default -> status;
        };
    }
}
