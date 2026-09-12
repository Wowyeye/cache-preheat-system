package com.jyu.cache.controller;

import com.jyu.cache.common.BusinessException;
import com.jyu.cache.common.Result;
import com.jyu.cache.common.UserContext;
import com.jyu.cache.entity.Order;
import com.jyu.cache.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 订单管理控制器（管理员侧）：全部订单 / 退款退货审核
 */
@Slf4j
@Tag(name = "订单管理", description = "管理员：全部订单、退款 / 退货审核")
@RestController
@RequestMapping("/api/admin/order")
public class OrderAdminController {

    private final OrderService orderService;

    public OrderAdminController(OrderService orderService) {
        this.orderService = orderService;
    }

    @Data
    public static class RejectReq {
        private String reason;
    }

    @Operation(summary = "全部订单列表（ADMIN）", description = "未登录 401，非管理员 403")
    @SecurityRequirement(name = "token")
    @GetMapping("/list")
    public Result<List<Order>> list() {
        UserContext.requireAdmin();
        return Result.success(orderService.allOrders());
    }

    @Operation(summary = "退款审核通过（ADMIN，回补库存）", description = "并发下拿不到状态流转权返回 409")
    @SecurityRequirement(name = "token")
    @PostMapping("/{id}/refund/approve")
    public Result<Order> approveRefund(@PathVariable Long id) {
        UserContext.requireAdmin();
        return Result.success("退款已通过，库存已回补", orderService.approveRefund(id));
    }

    @Operation(summary = "退货审核通过（ADMIN，回补库存）", description = "并发下拿不到状态流转权返回 409")
    @SecurityRequirement(name = "token")
    @PostMapping("/{id}/return/approve")
    public Result<Order> approveReturn(@PathVariable Long id) {
        UserContext.requireAdmin();
        return Result.success("退货已通过，库存已回补", orderService.approveReturn(id));
    }

    @Operation(summary = "拒绝退款/退货审核（ADMIN）", description = "退回已支付状态；订单不在审核状态返回 409")
    @SecurityRequirement(name = "token")
    @PostMapping("/{id}/reject")
    public Result<Order> reject(@PathVariable Long id, @RequestBody(required = false) RejectReq req) {
        UserContext.requireAdmin();
        String reason = req == null ? "不符合条件" : req.getReason();
        return Result.success("已拒绝", orderService.rejectAudit(id, reason));
    }
}
