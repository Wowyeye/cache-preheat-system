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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OrderService 单元测试：订单状态机 + 原子扣减防超卖 + 逐单事务的超时取消
 *
 * v3.1 重点：所有状态流转都走条件 UPDATE（updateStatusIf），
 * 拿不到流转权（影响 0 行）必须抛 409 且**不回补库存**——这是双回补/库存虚增的回归测试。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("订单状态机与超卖防护单元测试")
class OrderServiceTest {

    @Mock private OrderMapper orderMapper;
    @Mock private ProductMapper productMapper;
    @Mock private ProductService productService;
    @Mock private PlatformTransactionManager transactionManager;
    @Mock private TransactionStatus transactionStatus;

    private OrderService orderService;

    private Order pendingOrder;
    private Product stockProduct;

    @BeforeEach
    void setUp() {
        // 超时取消用 TransactionTemplate（每单一个独立事务）；测试里用事务管理器桩代跑
        lenient().when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
        orderService = new OrderService(orderMapper, productMapper, productService, transactionManager);

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

    private OrderItem item(long productId, int quantity) {
        OrderItem item = new OrderItem();
        item.setProductId(productId);
        item.setQuantity(quantity);
        return item;
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
        verify(orderMapper, never()).updateStatusIf(anyLong(), anyString(), any(), anyString(), any(), any());
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

    @Test
    @DisplayName("状态机：支付走条件更新并写入 pay_time")
    void pay_usesConditionalUpdateWithPayTime() {
        when(orderMapper.selectById(100L)).thenReturn(pendingOrder);
        when(orderMapper.updateStatusIf(eq(100L), eq(Order.STATUS_PAID), any(), eq(Order.STATUS_PENDING_PAYMENT),
                any(LocalDateTime.class), isNull())).thenReturn(1);

        orderService.pay(2L, 100L);

        verify(orderMapper).updateStatusIf(eq(100L), eq(Order.STATUS_PAID), any(), eq(Order.STATUS_PENDING_PAYMENT),
                any(LocalDateTime.class), isNull());
    }

    @Test
    @DisplayName("并发安全：支付时若已被超时取消（条件更新 0 行）-> 409，不静默成功")
    void pay_conflictsWhenTransitionLost() {
        when(orderMapper.selectById(100L)).thenReturn(pendingOrder);
        when(orderMapper.updateStatusIf(anyLong(), anyString(), any(), anyString(), any(), any())).thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class, () -> orderService.pay(2L, 100L));

        assertEquals(409, ex.getCode());
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

        verify(productMapper).decreaseStock(1L, 2);       // 原子扣减被调用
        verify(productService).evictCache(1L);            // 库存变更触发延迟双删
        assertEquals(100L, order.getId());
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

    @Test
    @DisplayName("入参校验：数量为 0 -> 400（而不是掉进 500）")
    void createOrder_rejectsNonPositiveQuantity() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.createOrder(2L, List.of(Map.of("productId", 1, "quantity", 0)), ""));

        assertEquals(400, ex.getCode());
    }

    @Test
    @DisplayName("入参校验：空清单 -> 400")
    void createOrder_rejectsEmptyItems() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.createOrder(2L, List.of(), ""));

        assertEquals(400, ex.getCode());
    }

    // ==================== 取消订单：条件更新才回补库存 ====================

    @Test
    @DisplayName("取消订单：抢到流转权 -> 状态置为已取消并回补库存")
    void cancel_restoresStock() {
        when(orderMapper.selectById(100L)).thenReturn(pendingOrder);
        when(orderMapper.updateStatusIf(eq(100L), eq(Order.STATUS_CANCELLED), anyString(),
                eq(Order.STATUS_PENDING_PAYMENT), isNull(), any(LocalDateTime.class))).thenReturn(1);
        when(orderMapper.selectItemsByOrderId(100L)).thenReturn(List.of(item(1L, 2)));

        orderService.cancel(2L, 100L);

        verify(productMapper).increaseStock(1L, 2);   // 库存回补
        verify(productService).evictCache(1L);        // 回补后缓存失效
    }

    @Test
    @DisplayName("并发安全（v3.1 修复）：重复取消 / 与超时调度撞车 -> 409 且不双回补库存")
    void cancel_conflictsWhenTransitionLost_doesNotRestoreStockTwice() {
        when(orderMapper.selectById(100L)).thenReturn(pendingOrder);
        when(orderMapper.updateStatusIf(anyLong(), anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(0);   // 另一方已抢先改成 CANCELLED

        BusinessException ex = assertThrows(BusinessException.class, () -> orderService.cancel(2L, 100L));

        assertEquals(409, ex.getCode());
        verify(productMapper, never()).increaseStock(anyLong(), anyInt());
    }

    @Test
    @DisplayName("审核退款：并发下拿不到流转权 -> 409 且不回补库存")
    void approveRefund_conflictsWhenTransitionLost() {
        Order refunding = new Order();
        refunding.setId(102L);
        refunding.setUserId(2L);
        refunding.setStatus(Order.STATUS_REFUNDING);
        when(orderMapper.selectById(102L)).thenReturn(refunding);
        when(orderMapper.updateStatusIf(anyLong(), anyString(), any(), anyString(), any(), any())).thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.approveRefund(102L));

        assertEquals(409, ex.getCode());
        verify(productMapper, never()).increaseStock(anyLong(), anyInt());
    }

    // ==================== 超时取消：每单独立事务 ====================

    @Test
    @DisplayName("超时取消：状态机式更新成功（返回 1）-> 回补库存")
    void cancelTimeout_cancelsAndRestores() {
        pendingOrder.setItems(List.of(item(1L, 2)));
        when(orderMapper.selectTimeoutPending(any(LocalDateTime.class), anyInt()))
                .thenReturn(List.of(pendingOrder));
        when(orderMapper.updateStatusIf(eq(100L), eq(Order.STATUS_CANCELLED), anyString(),
                eq(Order.STATUS_PENDING_PAYMENT), isNull(), any(LocalDateTime.class))).thenReturn(1);

        int cancelled = orderService.cancelTimeoutOrders(30);

        assertEquals(1, cancelled);
        verify(productMapper).increaseStock(1L, 2);
        verify(transactionManager).commit(transactionStatus);
    }

    @Test
    @DisplayName("超时取消：与手动取消并发（UPDATE 影响 0 行）-> 不回补库存，防双回补")
    void cancelTimeout_skipsWhenUpdateAffectsZeroRows() {
        when(orderMapper.selectTimeoutPending(any(LocalDateTime.class), anyInt()))
                .thenReturn(List.of(pendingOrder));
        when(orderMapper.updateStatusIf(anyLong(), anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(0);   // 用户手动取消已抢先（状态已不是 PENDING_PAYMENT）

        int cancelled = orderService.cancelTimeoutOrders(30);

        assertEquals(0, cancelled);
        verify(productMapper, never()).increaseStock(anyLong(), anyInt());  // 绝不回补
    }

    @Test
    @DisplayName("超时取消：单笔异常只回滚该笔并继续处理下一笔（逐单事务）")
    void cancelTimeout_continuesOnSingleFailure_withPerOrderRollback() {
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
        when(orderMapper.updateStatusIf(anyLong(), anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(1);
        when(orderMapper.selectItemsByOrderId(100L)).thenReturn(List.of(item(1L, 2)));

        int cancelled = orderService.cancelTimeoutOrders(30);

        assertEquals(1, cancelled, "第二笔应正常取消，第一笔被单独回滚");
        verify(transactionManager, times(1)).rollback(any());   // 只有失败的那笔回滚
        verify(productMapper).increaseStock(1L, 2);
    }
}
