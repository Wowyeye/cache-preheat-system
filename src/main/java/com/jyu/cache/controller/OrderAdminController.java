package com.jyu.cache.controller;

import com.jyu.cache.common.BusinessException;
import com.jyu.cache.common.Result;
import com.jyu.cache.common.UserContext;
import com.jyu.cache.entity.Order;
import com.jyu.cache.service.OrderService;
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

    @GetMapping("/list")
    public Result<List<Order>> list() {
        requireAdmin();
        return Result.success(orderService.allOrders());
    }

    @PostMapping("/{id}/refund/approve")
    public Result<Order> approveRefund(@PathVariable Long id) {
        requireAdmin();
        return Result.success("退款已通过，库存已回补", orderService.approveRefund(id));
    }

    @PostMapping("/{id}/return/approve")
    public Result<Order> approveReturn(@PathVariable Long id) {
        requireAdmin();
        return Result.success("退货已通过，库存已回补", orderService.approveReturn(id));
    }

    @PostMapping("/{id}/reject")
    public Result<Order> reject(@PathVariable Long id, @RequestBody(required = false) RejectReq req) {
        requireAdmin();
        String reason = req == null ? "不符合条件" : req.getReason();
        return Result.success("已拒绝", orderService.rejectAudit(id, reason));
    }

    private void requireAdmin() {
        UserContext.LoginUser user = UserContext.getUser();
        if (user == null || !user.isAdmin()) {
            throw new BusinessException(403, "需要管理员权限");
        }
    }
}
