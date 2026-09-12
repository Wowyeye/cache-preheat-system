package com.jyu.cache.controller;

import com.jyu.cache.common.BusinessException;
import com.jyu.cache.common.RateLimiter;
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
import java.util.Map;

/**
 * 订单控制器（用户侧）：下单 / 支付 / 取消 / 退款退货申请 / 我的订单
 *
 * 鉴权：全部接口都需要登录——POST 由拦截器挡（未登录 401），
 *      GET（/my 与 /{id}）在方法内 requireLogin() 挡，语义一致。
 *      因此这里在类级标注 @SecurityRequirement，Swagger UI 里所有接口都带锁。
 */
@Slf4j
@Tag(name = "订单", description = "下单原子扣库存；8 态状态机流转")
@SecurityRequirement(name = "token")
@RestController
@RequestMapping("/api/order")
public class OrderController {

    private final OrderService orderService;
    private final RateLimiter rateLimiter;

    public OrderController(OrderService orderService, RateLimiter rateLimiter) {
        this.orderService = orderService;
        this.rateLimiter = rateLimiter;
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

    @Operation(summary = "创建订单（需登录，原子扣库存）",
            description = "清单为空或数量非法 400，商品不存在 404，库存不足 409；**按用户限流：60 秒 10 单，超限 429**")
    @PostMapping("/create")
    public Result<Order> create(@RequestBody CreateOrderReq req) {
        UserContext.LoginUser user = requireLogin();
        // v3.4：下单是唯一有"写放大"的用户入口（扣库存+写订单表），按用户维度限流；
        // Redis 故障时放行，不因限流组件不可用而挡住核心业务
        rateLimiter.checkOrderAllowed(user.id());
        Order order = orderService.createOrder(user.id(), req.getItems(), req.getRemark());
        rateLimiter.recordOrderPlaced(user.id());
        return Result.success("下单成功，请及时支付", order);
    }

    @Operation(summary = "支付订单（需登录）", description = "待支付 → 已支付；并发下拿不到状态流转权返回 409")
    @PostMapping("/{id}/pay")
    public Result<Order> pay(@PathVariable Long id) {
        UserContext.LoginUser user = requireLogin();
        return Result.success("支付成功", orderService.pay(user.id(), id));
    }

    @Operation(summary = "取消订单（需登录，回补库存）", description = "待支付 → 已取消；并发下拿不到状态流转权返回 409")
    @PostMapping("/{id}/cancel")
    public Result<Order> cancel(@PathVariable Long id) {
        UserContext.LoginUser user = requireLogin();
        return Result.success("订单已取消，库存已回补", orderService.cancel(user.id(), id));
    }

    @Operation(summary = "申请退款（需登录，待管理员审核）", description = "已支付 → 退款中；并发下拿不到状态流转权返回 409")
    @PostMapping("/{id}/refund")
    public Result<Order> refund(@PathVariable Long id, @RequestBody(required = false) ReasonReq req) {
        UserContext.LoginUser user = requireLogin();
        String reason = req == null ? "" : req.getReason();
        return Result.success("退款申请已提交，等待管理员审核", orderService.applyRefund(user.id(), id, reason));
    }

    @Operation(summary = "申请退货（需登录，待管理员审核）", description = "已支付 → 退货中；并发下拿不到状态流转权返回 409")
    @PostMapping("/{id}/return")
    public Result<Order> applyReturn(@PathVariable Long id, @RequestBody(required = false) ReasonReq req) {
        UserContext.LoginUser user = requireLogin();
        String reason = req == null ? "" : req.getReason();
        return Result.success("退货申请已提交，等待管理员审核", orderService.applyReturn(user.id(), id, reason));
    }

    @Operation(summary = "我的订单列表（需登录）", description = "未登录返回 401；只返回当前用户自己的订单")
    @GetMapping("/my")
    public Result<List<Order>> myOrders() {
        UserContext.LoginUser user = requireLogin();
        return Result.success(orderService.myOrders(user.id()));
    }

    @Operation(summary = "订单详情（需登录，仅本人）", description = "订单不存在 404；查看他人订单 403")
    @GetMapping("/{id}")
    public Result<Order> detail(@PathVariable Long id) {
        UserContext.LoginUser user = requireLogin();
        return Result.success(orderService.detail(user.id(), id));
    }

    /** 显式登录校验：GET 接口拦截器不强制登录，所以方法内兜底（未登录 401，而不是 NPE 500） */
    private UserContext.LoginUser requireLogin() {
        UserContext.LoginUser user = UserContext.getUser();
        if (user == null) {
            throw new BusinessException(401, "未登录或登录已过期，请重新登录");
        }
        return user;
    }
}
