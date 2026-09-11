package com.jyu.cache.scheduler;

import com.jyu.cache.service.OrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 待支付订单超时取消调度器（v3 新增）
 *
 * v2 问题：待支付订单无超时机制，用户下单不付，库存被永久占用。
 * v3 方案：每 60 秒扫描一次，创建时间超过 N 分钟（默认 30，可配）仍未支付的
 *          订单自动取消并回补库存。取消走状态机式 UPDATE（WHERE status=PENDING_PAYMENT），
 *          与用户手动取消并发时只有一个成功，无双回补。
 */
@Slf4j
@Component
public class OrderTimeoutScheduler {

    private final OrderService orderService;

    /** 订单超时时间（分钟），可通过 ORDER_TIMEOUT_MINUTES 环境变量调整 */
    @Value("${app.order.timeout-minutes:30}")
    private int timeoutMinutes;

    public OrderTimeoutScheduler(OrderService orderService) {
        this.orderService = orderService;
    }

    @Scheduled(fixedDelay = 60000, initialDelay = 30000)
    public void cancelTimeoutOrders() {
        try {
            int cancelled = orderService.cancelTimeoutOrders(timeoutMinutes);
            if (cancelled > 0) {
                log.info("[订单超时调度] 本轮自动取消 {} 笔超时未支付订单（超时阈值 {} 分钟）", cancelled, timeoutMinutes);
            }
        } catch (Exception e) {
            log.error("[订单超时调度] 执行失败", e);
        }
    }
}
