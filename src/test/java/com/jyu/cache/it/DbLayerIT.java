package com.jyu.cache.it;

import com.jyu.cache.common.BusinessException;
import com.jyu.cache.entity.Order;
import com.jyu.cache.entity.OrderItem;
import com.jyu.cache.entity.Product;
import com.jyu.cache.mapper.OrderMapper;
import com.jyu.cache.mapper.ProductMapper;
import com.jyu.cache.service.OrderService;
import com.jyu.cache.service.ProductService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 数据库层集成测试（v3.2 新增）
 *
 * 【为什么单独有这个类】RedisLayerIT 只覆盖 Redis 层；Mapper/XML/Flyway/事务边界这些
 * "最容易写错、单测又 mock 掉"的地方一直没有真实数据库验证。这里用 Testcontainers 起
 * **真实 MySQL 8 + 真实 Redis 7**，并加载完整 Spring 上下文（Flyway 会自动建表+灌种子），
 * 覆盖：
 *   1. Flyway 版本化迁移在空库上真的跑通（V1 建表 + V2 种子 20 条商品）
 *   2. 防超卖的 SQL 原子扣减（WHERE stock >= n，0 行即失败）
 *   3. 状态机式条件更新（WHERE status = 期望值）真的只影响匹配的行
 *   4. 下单/取消全链路：订单+明细落库、库存扣减与回补
 *   5. **并发双取消只回补一次**（TOCTOU 回归测试：这是 v3.1 修掉的核心缺陷）
 *   6. Cache Aside 在真实 Redis 上的命中行为
 *
 * 无 Docker 环境：@Testcontainers(disabledWithoutDocker = true) 会把整类标记为 skipped（如实可见）。
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("数据库层集成测试（真实 MySQL + Redis + Flyway）")
class DbLayerIT {

    private static final String DB_NAME = "cache_db_v3";
    private static final String DB_PASS = "ittest";
    private static final int REDIS_PORT = 6379;

    /**
     * 用 root 连 MySQL：Flyway 需要读 performance_schema（判断 foreign_key_checks 等会话变量），
     * Testcontainers 默认创建的普通用户没有该权限，会报
     * "Unable to determine value for 'foreign_key_checks' variable"。
     */
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName(DB_NAME)
            .withUsername("root")
            .withPassword(DB_PASS);

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(REDIS_PORT);

    @DynamicPropertySource
    static void wireContainers(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(REDIS_PORT));
        registry.add("spring.data.redis.password", () -> "");
        registry.add("spring.data.redis.database", () -> 0);
        // 定时任务拉开间隔，避免测试期间后台预热/超时扫描干扰断言
        registry.add("cache.auto-preheat-interval-ms", () -> 3_600_000L);
        registry.add("app.order.timeout-minutes", () -> 30);
    }

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ProductMapper productMapper;
    @Autowired private OrderMapper orderMapper;
    @Autowired private ProductService productService;
    @Autowired private OrderService orderService;

    // ==================== 1. Flyway ====================

    @Test
    @DisplayName("Flyway：空库上跑完 V1+V2，schema 版本为 2、种子商品 20 条")
    void flyway_migratesEmptyDatabase() {
        List<Map<String, Object>> history = jdbcTemplate.queryForList(
                "SELECT version, success FROM flyway_schema_history ORDER BY installed_rank");

        assertEquals(2, history.size(), "应有两个迁移：V1 建表、V2 种子");
        assertEquals("1", String.valueOf(history.get(0).get("version")));
        assertEquals("2", String.valueOf(history.get(1).get("version")));
        assertTrue(history.stream().allMatch(row -> Boolean.TRUE.equals(row.get("success"))
                || "1".equals(String.valueOf(row.get("success")))), "迁移都应成功");
        assertEquals(20, productMapper.countAll(), "V2 种子应插入 20 条商品");
    }

    // ==================== 2. 原子扣减防超卖 ====================

    @Test
    @DisplayName("防超卖：SQL 原子扣减，库存不足时影响 0 行且库存不变")
    void decreaseStock_isAtomic() {
        int stock = productMapper.selectById(1L).getStock();

        assertEquals(0, productMapper.decreaseStock(1L, stock + 1), "超过库存应扣减失败（0 行）");
        assertEquals(stock, productMapper.selectById(1L).getStock(), "失败时库存必须不变");

        assertEquals(1, productMapper.decreaseStock(1L, 3), "库存充足应扣减成功");
        assertEquals(stock - 3, productMapper.selectById(1L).getStock());
    }

    // ==================== 3. 条件式状态更新 ====================

    @Test
    @DisplayName("状态机：updateStatusIf 只更新状态匹配的行（并发安全的基石）")
    void updateStatusIf_isConditional() {
        Order order = newOrder(1L, "条件更新测试");

        // 期望状态不符 -> 0 行，状态不变
        assertEquals(0, orderMapper.updateStatusIf(order.getId(), Order.STATUS_CANCELLED, "不该生效",
                Order.STATUS_PAID, null, java.time.LocalDateTime.now()));
        assertEquals(Order.STATUS_PENDING_PAYMENT, orderMapper.selectById(order.getId()).getStatus());

        // 期望状态相符 -> 1 行
        assertEquals(1, orderMapper.updateStatusIf(order.getId(), Order.STATUS_CANCELLED, "超时取消",
                Order.STATUS_PENDING_PAYMENT, null, java.time.LocalDateTime.now()));
        assertEquals(Order.STATUS_CANCELLED, orderMapper.selectById(order.getId()).getStatus());
        // 再改一次（此时状态已不是待支付）-> 仍然 0 行
        assertEquals(0, orderMapper.updateStatusIf(order.getId(), Order.STATUS_CANCELLED, "重复取消",
                Order.STATUS_PENDING_PAYMENT, null, java.time.LocalDateTime.now()));
    }

    // ==================== 4. 下单 / 取消全链路 ====================

    @Test
    @DisplayName("下单：订单+明细落库、库存扣减；取消：状态翻转且库存精确回补")
    void createThenCancel_restoresStockExactly() {
        int stockBefore = productMapper.selectById(1L).getStock();

        Order order = orderService.createOrder(1L, List.of(Map.of("productId", 1, "quantity", 2)), "集成测试下单");

        assertNotNull(order.getId());
        assertEquals(Order.STATUS_PENDING_PAYMENT, order.getStatus());
        assertEquals(stockBefore - 2, productMapper.selectById(1L).getStock(), "下单应扣减库存");
        List<OrderItem> items = orderMapper.selectItemsByOrderId(order.getId());
        assertEquals(1, items.size());
        assertEquals(2, items.get(0).getQuantity());
        assertEquals(1L, items.get(0).getProductId());

        Order cancelled = orderService.cancel(1L, order.getId());

        assertEquals(Order.STATUS_CANCELLED, cancelled.getStatus());
        assertEquals(stockBefore, productMapper.selectById(1L).getStock(), "取消应精确回补库存");
    }

    // ==================== 5. 并发双取消只回补一次（TOCTOU 回归） ====================

    @Test
    @DisplayName("并发双取消：只有一个成功，另一个 409，库存只回补一次（v3.1 修复的回归测试）")
    void concurrentCancel_restoresStockExactlyOnce() throws Exception {
        int stockBefore = productMapper.selectById(2L).getStock();
        Order order = orderService.createOrder(1L, List.of(Map.of("productId", 2, "quantity", 1)), "并发取消测试");
        assertEquals(stockBefore - 1, productMapper.selectById(2L).getStock());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch startGate = new CountDownLatch(1);
        Callable<String> cancelTask = () -> {
            startGate.await(10, TimeUnit.SECONDS);
            try {
                orderService.cancel(1L, order.getId());
                return "OK";
            } catch (BusinessException e) {
                return "CONFLICT-" + e.getCode();
            }
        };
        try {
            Future<String> first = pool.submit(cancelTask);
            Future<String> second = pool.submit(cancelTask);
            startGate.countDown();
            List<String> results = List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));

            assertEquals(1, results.stream().filter("OK"::equals).count(),
                    "应当只有一个取消成功，实际结果：" + results);
            assertEquals(1, results.stream().filter(r -> r.startsWith("CONFLICT-409")).count(),
                    "另一个应当是 409 状态冲突，实际结果：" + results);
        } finally {
            pool.shutdownNow();
        }

        assertEquals(stockBefore, productMapper.selectById(2L).getStock(),
                "并发下库存必须精确回补一次（旧的'先查后无条件改'会导致虚增）");
        assertEquals(Order.STATUS_CANCELLED, orderMapper.selectById(order.getId()).getStatus());
    }

    // ==================== 6. Cache Aside（真实 Redis + 真实 DB） ====================

    @Test
    @DisplayName("Cache Aside：回源写缓存后，二次查询命中缓存且不查库")
    void cacheAside_secondReadHitsCache() {
        productService.clearCache(3L);

        Product first = productService.getById(3L);
        assertNotNull(first);
        long viewAfterFirst = productMapper.selectById(3L).getViewCount();

        Product second = productService.getById(3L);

        assertEquals(first.getId(), second.getId());
        assertEquals(viewAfterFirst, productMapper.selectById(3L).getViewCount(),
                "缓存命中时不应再累加浏览量（浏览量只在回源时累加）");
    }

    @Test
    @DisplayName("穿透防护：查不存在的 ID 写空值标记，第二次不再打库")
    void nullMarker_preventsSecondDbHit() {
        long missingId = 999_999L;
        productService.clearCache(missingId);

        assertNull(productService.getById(missingId), "不存在的商品应返回 null");
        assertNull(productService.getById(missingId), "第二次命中空值标记，仍返回 null");
    }

    // ==================== 工具 ====================

    /** 直接插一条待支付订单（不经过 Service，避免依赖商品库存） */
    private Order newOrder(Long userId, String remark) {
        Order order = new Order();
        order.setOrderNo("IT" + System.nanoTime());
        order.setUserId(userId);
        order.setTotalAmount(new java.math.BigDecimal("1.00"));
        order.setStatus(Order.STATUS_PENDING_PAYMENT);
        order.setRemark(remark);
        orderMapper.insert(order);
        assertNotNull(order.getId(), "insert 应回填自增 ID");
        return order;
    }
}
