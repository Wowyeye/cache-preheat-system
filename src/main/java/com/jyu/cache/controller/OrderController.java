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
import java.util.Map;

/**
 * 订单控制器（用户侧）：下单 / 支付 / 取消 / 退款退货申请 / 我的订单
 */
@Slf4j
@RestController
@RequestMapping("/api/order")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @Data
    public static class CreateOrderReq {
        private List<Map<String, Object>> items;
        private String remark;
    }

    @Data
    public static class ReasonReq {
        private String reason;
    }

    @PostMapping("/create")
    public Result<Order> create(@RequestBody CreateOrderReq req) {
        UserContext.LoginUser user = UserContext.getUser();
        Order order = orderService.createOrder(user.id(), req.getItems(), req.getRemark());
        return Result.success("下单成功，请及时支付", order);
    }

    @PostMapping("/{id}/pay")
    public Result<Order> pay(@PathVariable Long id) {
        UserContext.LoginUser user = UserContext.getUser();
        return Result.success("支付成功", orderService.pay(user.id(), id));
    }

    @PostMapping("/{id}/cancel")
    public Result<Order> cancel(@PathVariable Long id) {
        UserContext.LoginUser user = UserContext.getUser();
        return Result.success("订单已取消，库存已回补", orderService.cancel(user.id(), id));
    }

    @PostMapping("/{id}/refund")
    public Result<Order> refund(@PathVariable Long id, @RequestBody(required = false) ReasonReq req) {
        UserContext.LoginUser user = UserContext.getUser();
        String reason = req == null ? "" : req.getReason();
        return Result.success("退款申请已提交，等待管理员审核", orderService.applyRefund(user.id(), id, reason));
    }

    @PostMapping("/{id}/return")
    public Result<Order> applyReturn(@PathVariable Long id, @RequestBody(required = false) ReasonReq req) {
        UserContext.LoginUser user = UserContext.getUser();
        String reason = req == null ? "" : req.getReason();
        return Result.success("退货申请已提交，等待管理员审核", orderService.applyReturn(user.id(), id, reason));
    }

    @GetMapping("/my")
    public Result<List<Order>> myOrders() {
        UserContext.LoginUser user = requireLogin();
        return Result.success(orderService.myOrders(user.id()));
    }

    @GetMapping("/{id}")
    public Result<Order> detail(@PathVariable Long id) {
        UserContext.LoginUser user = requireLogin();
        return Result.success(orderService.detail(user.id(), id));
    }

    private UserContext.LoginUser requireLogin() {
        UserContext.LoginUser user = UserContext.getUser();
        if (user == null) {
            throw new BusinessException(401, "未登录或登录已过期，请重新登录");
        }
        return user;
    }
}
