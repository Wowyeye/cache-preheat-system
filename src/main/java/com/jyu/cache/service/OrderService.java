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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

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
 * 【并发安全约定】所有状态流转都必须是"条件 UPDATE"（updateStatusIf）：
 *   早期实现用 checkStatus（先查）+ updateStatus（无条件改），并发下两个请求都能通过校验，
 *   于是 cancel 与超时调度同时回补库存 -> 库存虚增 -> 超卖。现在条件 UPDATE 只有一方影响 1 行。
 *
 * 【超时取消】每单一个独立事务（TransactionTemplate + REQUIRES_NEW），
 *   单笔失败只回滚自己，"订单已取消但库存只回补一半"的半提交状态不会出现。
 */
@Slf4j
@Service
public class OrderService {

    private final OrderMapper orderMapper;
    private final ProductMapper productMapper;
    private final ProductService productService;
    private final TransactionTemplate transactionTemplate;

    public OrderService(OrderMapper orderMapper, ProductMapper productMapper, ProductService productService,
                        PlatformTransactionManager transactionManager) {
        this.orderMapper = orderMapper;
        this.productMapper = productMapper;
        this.productService = productService;
        // 超时取消按"每单一个事务"处理：REQUIRES_NEW 保证即使被包在外层事务里也各回滚各的
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
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

    /** 支付：待支付 → 已支付（条件更新，防与超时取消竞争） */
    @Transactional(rollbackFor = Exception.class)
    public Order pay(Long userId, Long orderId) {
        Order order = getOwnedOrder(userId, orderId);
        checkStatus(order, Order.STATUS_PENDING_PAYMENT, "只有待支付订单才能支付");
        int updated = orderMapper.updateStatusIf(order.getId(), Order.STATUS_PAID, order.getRemark(),
                Order.STATUS_PENDING_PAYMENT, LocalDateTime.now(), null);
        if (updated == 0) {
            throw new BusinessException(409, "订单状态已变更（可能已被超时取消），请刷新后重试");
        }
        log.info("[订单支付] orderNo={}", order.getOrderNo());
        return orderMapper.selectById(order.getId());
    }

    /** 取消：待支付 → 已取消，回补库存（条件更新，杜绝并发双回补） */
    @Transactional(rollbackFor = Exception.class)
    public Order cancel(Long userId, Long orderId) {
        Order order = getOwnedOrder(userId, orderId);
        checkStatus(order, Order.STATUS_PENDING_PAYMENT, "只有待支付订单才能取消");
        int updated = orderMapper.updateStatusIf(order.getId(), Order.STATUS_CANCELLED,
                order.getRemark() == null || order.getRemark().isEmpty() ? "用户主动取消" : order.getRemark(),
                Order.STATUS_PENDING_PAYMENT, null, LocalDateTime.now());
        if (updated == 0) {
            throw new BusinessException(409, "订单状态已变更（可能已被超时取消），请刷新后重试");
        }
        restoreStock(order);
        log.info("[订单取消] orderNo={} 库存已回补", order.getOrderNo());
        return orderMapper.selectById(order.getId());
    }

    /** 申请退款：已支付 → 退款中（未发货场景） */
    @Transactional(rollbackFor = Exception.class)
    public Order applyRefund(Long userId, Long orderId, String reason) {
        Order order = getOwnedOrder(userId, orderId);
        checkStatus(order, Order.STATUS_PAID, "只有已支付订单才能申请退款");
        if (orderMapper.updateStatusIf(order.getId(), Order.STATUS_REFUNDING, reason,
                Order.STATUS_PAID, null, null) == 0) {
            throw new BusinessException(409, "订单状态已变更，请刷新后重试");
        }
        log.info("[申请退款] orderNo={} reason={}", order.getOrderNo(), reason);
        return orderMapper.selectById(order.getId());
    }

    /** 申请退货：已支付 → 退货中（已发货/已收货场景） */
    @Transactional(rollbackFor = Exception.class)
    public Order applyReturn(Long userId, Long orderId, String reason) {
        Order order = getOwnedOrder(userId, orderId);
        checkStatus(order, Order.STATUS_PAID, "只有已支付订单才能申请退货");
        if (orderMapper.updateStatusIf(order.getId(), Order.STATUS_RETURNING, reason,
                Order.STATUS_PAID, null, null) == 0) {
            throw new BusinessException(409, "订单状态已变更，请刷新后重试");
        }
        log.info("[申请退货] orderNo={} reason={}", order.getOrderNo(), reason);
        return orderMapper.selectById(order.getId());
    }

    /** 管理员审核退款：退款中 → 已退款，回补库存 */
    @Transactional(rollbackFor = Exception.class)
    public Order approveRefund(Long orderId) {
        Order order = getOrder(orderId);
        checkStatus(order, Order.STATUS_REFUNDING, "该订单不在退款审核状态");
        if (orderMapper.updateStatusIf(order.getId(), Order.STATUS_REFUNDED, order.getRemark(),
                Order.STATUS_REFUNDING, null, LocalDateTime.now()) == 0) {
            throw new BusinessException(409, "订单状态已变更，请刷新后重试");
        }
        restoreStock(order);
        log.info("[退款通过] orderNo={} 库存已回补", order.getOrderNo());
        return orderMapper.selectById(order.getId());
    }

    /** 管理员审核退货：退货中 → 已退货，回补库存 */
    @Transactional(rollbackFor = Exception.class)
    public Order approveReturn(Long orderId) {
        Order order = getOrder(orderId);
        checkStatus(order, Order.STATUS_RETURNING, "该订单不在退货审核状态");
        if (orderMapper.updateStatusIf(order.getId(), Order.STATUS_RETURNED, order.getRemark(),
                Order.STATUS_RETURNING, null, LocalDateTime.now()) == 0) {
            throw new BusinessException(409, "订单状态已变更，请刷新后重试");
        }
        restoreStock(order);
        log.info("[退货通过] orderNo={} 库存已回补", order.getOrderNo());
        return orderMapper.selectById(order.getId());
    }

    /** 管理员拒绝退款/退货：退回已支付状态 */
    @Transactional(rollbackFor = Exception.class)
    public Order rejectAudit(Long orderId, String reason) {
        Order order = getOrder(orderId);
        String expected;
        String prefix;
        if (Order.STATUS_REFUNDING.equals(order.getStatus())) {
            expected = Order.STATUS_REFUNDING;
            prefix = "退款被拒：";
        } else if (Order.STATUS_RETURNING.equals(order.getStatus())) {
            expected = Order.STATUS_RETURNING;
            prefix = "退货被拒：";
        } else {
            throw new BusinessException(409, "该订单不在审核状态");
        }
        if (orderMapper.updateStatusIf(order.getId(), Order.STATUS_PAID, prefix + reason,
                expected, null, null) == 0) {
            throw new BusinessException(409, "订单状态已变更，请刷新后重试");
        }
        log.info("[审核拒绝] orderNo={} reason={}", order.getOrderNo(), reason);
        return orderMapper.selectById(order.getId());
    }

    // ==================== 超时自动取消（v3 新增） ====================

    /**
     * 取消所有超时未支付订单（由调度器周期调用，也可管理员手动触发）
     *
     * 每单一个独立事务（TransactionTemplate + REQUIRES_NEW）：
     *   单笔失败只回滚该笔，已处理成功的订单不受影响，也不会留下
     *   "状态已改成已取消、库存却只回补一半"的半提交状态。
     *
     * @param timeoutMinutes 超时分钟数
     * @return 取消的订单数
     */
    public int cancelTimeoutOrders(int timeoutMinutes) {
        LocalDateTime deadline = LocalDateTime.now().minusMinutes(timeoutMinutes);
        List<Order> timeouts = orderMapper.selectTimeoutPending(deadline, 100);
        int cancelled = 0;
        for (Order order : timeouts) {
            try {
                if (Boolean.TRUE.equals(
                        transactionTemplate.execute(status -> cancelTimeoutOrder(order, timeoutMinutes)))) {
                    cancelled++;
                }
            } catch (Exception e) {
                // 单笔失败：该笔已单独回滚，继续处理下一单
                log.error("[超时取消] orderNo={} 处理失败（本单已回滚，继续下一单）：{}",
                        order.getOrderNo(), e.getMessage());
            }
        }
        return cancelled;
    }

    /** 单笔超时取消（在独立事务内执行）：条件更新抢占状态 -> 回补库存 */
    private boolean cancelTimeoutOrder(Order order, int timeoutMinutes) {
        // 状态机式更新：UPDATE ... WHERE status='PENDING_PAYMENT'，并发安全（同时只有一个赢）
        int updated = orderMapper.updateStatusIf(order.getId(), Order.STATUS_CANCELLED,
                "超时未支付，系统自动取消", Order.STATUS_PENDING_PAYMENT, null, LocalDateTime.now());
        if (updated == 0) {
            return false;   // 用户同时支付/取消了：谁先改成功谁负责，这里直接跳过
        }
        restoreStock(order);
        log.info("[超时取消] orderNo={} 超过 {} 分钟未支付，已自动取消并回补库存",
                order.getOrderNo(), timeoutMinutes);
        return true;
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
