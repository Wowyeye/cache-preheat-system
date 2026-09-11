package com.jyu.cache.service;

import com.jyu.cache.common.BusinessException;
import com.jyu.cache.mapper.OrderMapper;
import com.jyu.cache.mapper.ProductMapper;
import com.jyu.cache.entity.Order;
import com.jyu.cache.entity.OrderItem;
import com.jyu.cache.entity.Product;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OrderService 单元测试：订单状态机 + 原子扣减防超卖 + 超时取消
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("订单状态机与超卖防护单元测试")
class OrderServiceTest {

    @Mock private OrderMapper orderMapper;
    @Mock private ProductMapper productMapper;
    @Mock private ProductService productService;

    private OrderService orderService;

    private Order pendingOrder;
    private Product stockProduct;

    @BeforeEach
    void setUp() {
        orderService = new OrderService(orderMapper, productMapper, productService);

        pendingOrder = new Order();
        pendingOrder.setId(100L);
        pendingOrder.setOrderNo("260911000001");
        pendingOrder.setUserId(2L);
        pendingOrder.setStatus(Order.STATUS_PENDING_PAYMENT);
        pendingOrder.setTotalAmount(new BigDecimal("19998.00"));

        stockProduct = new Product();
        stockProduct.setId(1L);
        stockProduct.setName("iPhone 15");
        stockProduct.setPrice(new BigDecimal("9999.00"));
        stockProduct.setStock(5);
        stockProduct.setStatus(1);
    }

    // ==================== 状态机：非法流转拒绝 ====================

    @Test
    @DisplayName("状态机：待支付订单才能支付，已取消订单支付被拒（409）")
    void pay_rejectsNonPendingOrder() {
        Order cancelled = new Order();
        cancelled.setId(101L);
        cancelled.setUserId(2L);
        cancelled.setStatus(Order.STATUS_CANCELLED);
        when(orderMapper.selectById(101L)).thenReturn(cancelled);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.pay(2L, 101L));
        assertEquals(409, ex.getCode());
    }

    @Test
    @DisplayName("状态机：只有已支付订单才能申请退款，待支付被拒")
    void applyRefund_rejectsPendingOrder() {
        when(orderMapper.selectById(100L)).thenReturn(pendingOrder);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.applyRefund(2L, 100L, "不想要了"));
        assertEquals(409, ex.getCode());
    }

    @Test
    @DisplayName("越权：操作他人订单被拒（403）")
    void cancel_rejectsOtherUsersOrder() {
        when(orderMapper.selectById(100L)).thenReturn(pendingOrder);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.cancel(3L, 100L));
        assertEquals(403, ex.getCode());
    }

    // ==================== 防超卖：原子扣减 ====================

    @Test
    @DisplayName("防超卖：库存充足 -> 扣减成功，订单创建，缓存失效被触发")
    void createOrder_decreasesStockAndEvictsCache() {
        when(productMapper.selectById(1L)).thenReturn(stockProduct);
        when(productMapper.decreaseStock(1L, 2)).thenReturn(1);   // 扣减成功
        // 模拟 MyBatis 回填自增 ID：insert 后 order.getId() 由 null 变为 100L
        when(orderMapper.insert(any(Order.class))).thenAnswer(inv -> {
            inv.getArgument(0, Order.class).setId(100L);
            return 1;
        });
        when(orderMapper.insertItem(any(OrderItem.class))).thenReturn(1);
        when(orderMapper.selectById(100L)).thenReturn(pendingOrder);

        Order order = orderService.createOrder(2L,
                List.of(Map.of("productId", 1, "quantity", 2)), "");

        assertEquals(Order.STATUS_PENDING_PAYMENT, order.getStatus());
        verify(productMapper).decreaseStock(1L, 2);       // 原子扣减被调用
        verify(productService).evictCache(1L);            // 库存变更触发延迟双删
    }

    @Test
    @DisplayName("防超卖：库存不足（原子扣减返回 0 行）-> 抛 409，不生成订单")
    void createOrder_rejectsWhenStockInsufficient() {
        stockProduct.setStock(1);
        when(productMapper.selectById(1L)).thenReturn(stockProduct);
        when(productMapper.decreaseStock(1L, 2)).thenReturn(0);   // WHERE stock>=2 不满足

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.createOrder(2L, List.of(Map.of("productId", 1, "quantity", 2)), ""));

        assertEquals(409, ex.getCode());
        verify(orderMapper, never()).insert(any(Order.class));
    }

    // ==================== 取消订单：回补库存 ====================

    @Test
    @DisplayName("取消订单：状态置为已取消并回补库存")
    void cancel_restoresStock() {
        when(orderMapper.selectById(100L)).thenReturn(pendingOrder);
        OrderItem item = new OrderItem();
        item.setProductId(1L);
        item.setQuantity(2);
        when(orderMapper.selectItemsByOrderId(100L)).thenReturn(List.of(item));

        orderService.cancel(2L, 100L);

        verify(productMapper).increaseStock(1L, 2);   // 库存回补
        verify(productService).evictCache(1L);        // 回补后缓存失效
    }

    // ==================== 超时取消（v3 新增） ====================

    @Test
    @DisplayName("超时取消：状态机式更新成功（返回 1）-> 回补库存")
    void cancelTimeout_cancelsAndRestores() {
        OrderItem item = new OrderItem();
        item.setProductId(1L);
        item.setQuantity(2);
        pendingOrder.setItems(List.of(item));
        when(orderMapper.selectTimeoutPending(any(LocalDateTime.class), anyInt()))
                .thenReturn(List.of(pendingOrder));
        when(orderMapper.updateStatusIf(eq(100L), eq(Order.STATUS_CANCELLED),
                anyString(), eq(Order.STATUS_PENDING_PAYMENT), any(LocalDateTime.class)))
                .thenReturn(1);   // 抢到取消权

        int cancelled = orderService.cancelTimeoutOrders(30);

        assertEquals(1, cancelled);
        verify(productMapper).increaseStock(1L, 2);
    }

    @Test
    @DisplayName("超时取消：与手动取消并发（UPDATE 影响 0 行）-> 不回补库存，防双回补")
    void cancelTimeout_skipsWhenUpdateAffectsZeroRows() {
        when(orderMapper.selectTimeoutPending(any(LocalDateTime.class), anyInt()))
                .thenReturn(List.of(pendingOrder));
        when(orderMapper.updateStatusIf(anyLong(), anyString(), anyString(), anyString(),
                any(LocalDateTime.class)))
                .thenReturn(0);   // 用户手动取消已抢先（状态已不是 PENDING_PAYMENT）

        int cancelled = orderService.cancelTimeoutOrders(30);

        assertEquals(0, cancelled);
        verify(productMapper, never()).increaseStock(anyLong(), anyInt());  // 绝不回补
    }

    @Test
    @DisplayName("超时取消：单个订单异常不阻断整批")
    void cancelTimeout_continuesOnSingleFailure() {
        Order bad = new Order();
        bad.setId(200L);
        bad.setOrderNo("260911000002");
        bad.setUserId(2L);
        bad.setStatus(Order.STATUS_PENDING_PAYMENT);
        bad.setItems(List.of());
        when(orderMapper.selectTimeoutPending(any(LocalDateTime.class), anyInt()))
                .thenReturn(List.of(bad, pendingOrder));
        // 第一笔：回补库存时数据库异常
        when(orderMapper.selectItemsByOrderId(200L)).thenThrow(new RuntimeException("db error"));
        when(orderMapper.updateStatusIf(anyLong(), anyString(), anyString(), anyString(),
                any(LocalDateTime.class))).thenReturn(1);
        OrderItem item = new OrderItem();
        item.setProductId(1L);
        item.setQuantity(2);
        when(orderMapper.selectItemsByOrderId(100L)).thenReturn(List.of(item));

        int cancelled = orderService.cancelTimeoutOrders(30);

        assertTrue(cancelled >= 1, "第二笔应正常取消");
    }
}
