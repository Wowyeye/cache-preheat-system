package com.jyu.cache.controller;

import com.jyu.cache.common.BusinessException;
import com.jyu.cache.common.ClientIpResolver;
import com.jyu.cache.common.UserContext;
import com.jyu.cache.entity.Product;
import com.jyu.cache.service.OrderService;
import com.jyu.cache.service.ProductService;
import com.jyu.cache.service.TokenService;
import com.jyu.cache.service.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web 层接口契约测试（v3.3 新增，MockMvc，不依赖 DB/Redis）
 *
 * 【为什么需要它】v3.1~v3.3 修的一批问题全都是"接口契约"层面的：
 *   未登录要 401（原来 403）、越权要 403、方法不支持要 405（原来 500）、
 *   参数类型错要 400（原来 500）、删不存在的资源要 404（原来 200）、限流要 429、
 *   内部统计接口不能让游客读。
 * 之前这些只用 curl 手工验过一遍——重构一次就可能悄悄回退。这里把它们钉成自动化断言。
 */
@WebMvcTest(controllers = {ProductController.class, OrderAdminController.class, AuthController.class})
@DisplayName("Web 层契约测试（状态码 / 权限矩阵）")
class WebLayerContractTest {

    private static final String ADMIN_TOKEN = "token-admin";
    private static final String USER_TOKEN = "token-user";

    @Autowired private MockMvc mockMvc;

    @MockBean private ProductService productService;
    @MockBean private OrderService orderService;
    @MockBean private UserService userService;
    @MockBean private TokenService tokenService;
    @MockBean private ClientIpResolver clientIpResolver;

    /** 让 XFF 解析走真实实现（默认不信任代理） */
    private void stubClientIp() {
        lenient().when(clientIpResolver.resolve(any())).thenReturn("127.0.0.1");
    }

    private void asAdmin() {
        when(tokenService.verify(ADMIN_TOKEN))
                .thenReturn(new UserContext.LoginUser(1L, "admin", "系统管理员", "ADMIN"));
    }

    private void asNormalUser() {
        when(tokenService.verify(USER_TOKEN))
                .thenReturn(new UserContext.LoginUser(2L, "user1", "测试用户一", "USER"));
    }

    // ==================== 权限矩阵 ====================

    @Test
    @DisplayName("未登录访问管理接口 -> 401（不是 403，客户端要能区分'去登录'和'换账号'）")
    void guestOnAdminEndpoint_returns401() throws Exception {
        mockMvc.perform(get("/api/admin/order/list"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    @DisplayName("普通用户访问管理接口 -> 403")
    void normalUserOnAdminEndpoint_returns403() throws Exception {
        asNormalUser();

        mockMvc.perform(get("/api/admin/order/list").header("Authorization", USER_TOKEN))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    @DisplayName("管理员访问管理接口 -> 200")
    void adminOnAdminEndpoint_returns200() throws Exception {
        asAdmin();
        when(orderService.allOrders()).thenReturn(List.of());

        mockMvc.perform(get("/api/admin/order/list").header("Authorization", ADMIN_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @DisplayName("写接口不带 token -> 401（拦截器直接拦下）")
    void writeWithoutToken_returns401() throws Exception {
        stubClientIp();

        mockMvc.perform(post("/api/product")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"price\":1,\"stock\":1}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    @DisplayName("普通用户调用管理员写接口 -> 403，且不会真的执行")
    void normalUserOnAdminWrite_returns403() throws Exception {
        asNormalUser();

        mockMvc.perform(delete("/api/product/1").header("Authorization", USER_TOKEN))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    // ==================== 公开读路径 ====================

    @Test
    @DisplayName("游客可读商品详情 -> 200（GET 只读放行）")
    void guestCanReadProduct() throws Exception {
        when(productService.getById(1L)).thenReturn(new Product(1L, "iPhone", 1L,
                new BigDecimal("9.99"), 5, "d", 1, 1L, null, null));

        mockMvc.perform(get("/api/product/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(1));
    }

    @Test
    @DisplayName("缓存摘要对游客开放 -> 200，且不含内部运维计数")
    void guestCanReadCacheSummary() throws Exception {
        when(productService.getCacheSummary()).thenReturn(Map.of("hitRate", "88.00%", "hitCount", 88));

        mockMvc.perform(get("/api/product/cache/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hitRate").value("88.00%"))
                .andExpect(jsonPath("$.data.cacheKeyCount").doesNotExist());
    }

    @Test
    @DisplayName("缓存完整统计游客读 -> 401；管理员读 -> 200")
    void cacheStats_isAdminOnly() throws Exception {
        mockMvc.perform(get("/api/product/cache/stats"))
                .andExpect(status().isUnauthorized());

        asAdmin();
        when(productService.getCacheStats()).thenReturn(Map.of("hitRate", "88.00%", "cacheKeyCount", 10));

        mockMvc.perform(get("/api/product/cache/stats").header("Authorization", ADMIN_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cacheKeyCount").value(10));
    }

    // ==================== 错误语义 ====================

    @Test
    @DisplayName("POST 打到只支持 GET 的接口 -> 405（原来被兜底成 500）")
    void postOnReadOnlyEndpoint_returns405() throws Exception {
        asAdmin();

        mockMvc.perform(post("/api/product/list").header("Authorization", ADMIN_TOKEN))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value(405));
    }

    @Test
    @DisplayName("参数类型不匹配（page=abc）-> 400（原来被兜底成 500）")
    void badRequestParamType_returns400() throws Exception {
        mockMvc.perform(get("/api/product/page").param("page", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("删除不存在的商品 -> 404（原来返回 200 '删除成功'）")
    void deleteMissingProduct_returns404() throws Exception {
        asAdmin();
        when(productService.delete(anyLong())).thenReturn(false);

        mockMvc.perform(delete("/api/product/424242").header("Authorization", ADMIN_TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    @DisplayName("更新不存在的商品 -> 404")
    void updateMissingProduct_returns404() throws Exception {
        asAdmin();
        when(productService.update(any())).thenReturn(false);

        mockMvc.perform(put("/api/product")
                        .header("Authorization", ADMIN_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":424242,\"name\":\"x\",\"price\":1,\"stock\":1}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    @DisplayName("登录失败被限流 -> 429（HTTP 状态与业务码一致）")
    void loginRateLimited_returns429() throws Exception {
        stubClientIp();
        when(userService.login(anyString(), anyString(), anyString()))
                .thenThrow(new BusinessException(429, "登录尝试过于频繁，请 1 分钟后再试"));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"wrong\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(429));
    }

    @Test
    @DisplayName("登录参数校验失败 -> 400（@Valid 生效，不是 500）")
    void loginWithBlankUsername_returns400() throws Exception {
        stubClientIp();

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"\",\"password\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("非法 token -> 401（登录态校验 fail-closed）")
    void invalidToken_returns401() throws Exception {
        when(tokenService.verify("bad-token")).thenReturn(null);

        mockMvc.perform(post("/api/product").header("Authorization", "bad-token")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }
}
