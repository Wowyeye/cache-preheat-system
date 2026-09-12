package com.jyu.cache.it;

import com.jyu.cache.common.SafeRedisTemplate;
import com.jyu.cache.config.CacheProperties;
import com.jyu.cache.config.RedisConfig;
import com.jyu.cache.entity.Product;
import com.jyu.cache.mapper.ProductMapper;
import com.jyu.cache.service.HotSpotService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Redis 层集成测试（Testcontainers 真实 Redis 7）
 *
 * 【它到底测什么 / 不测什么】——v3.1 明确边界，不再让名字暗示不属于它的覆盖：
 *   测：生产 RedisConfig 的序列化配置（类型白名单 + JavaTimeModule）读写往返、
 *       空值标记这类"字符串哨兵值"的真实行为、SCAN 替代 KEYS 的真实扫描、
 *       ZSet 热度记账与移除。
 *   不测：数据库层（Mapper/XML/Flyway）、Spring 上下文装配、Cache Aside 与延迟双删的
 *       全链路（那些需要 MySQL 容器，见 README"测试矩阵"的说明）。
 *
 * 无 Docker 环境：@Testcontainers(disabledWithoutDocker = true) 会把整类标记为
 * **跳过（skipped）**——报告里看得见，而不是以前那种 "Tests run: 0" 的静默消失。
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("Redis 层集成测试（真实 Redis + 生产序列化配置）")
class RedisLayerIT {

    /** Redis 7 容器（与生产 docker-compose 同版本，带密码） */
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379)
            .withCommand("redis-server", "--requirepass", "itpass", "--appendonly", "no")
            .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1));

    static LettuceConnectionFactory connectionFactory;
    static RedisTemplate<String, Object> redisTemplate;
    static SafeRedisTemplate safeRedis;
    static CacheProperties cacheProperties;

    @BeforeAll
    static void init() {
        RedisStandaloneConfiguration cfg = new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379));
        cfg.setPassword("itpass");
        connectionFactory = new LettuceConnectionFactory(cfg);
        connectionFactory.afterPropertiesSet();

        // 关键：直接用生产 RedisConfig 构建模板，验的就是线上那套序列化配置，
        // 而不是测试里另搭一套（旧版本就栽在这里：测的配置和生产的不是一回事）
        redisTemplate = new RedisConfig().redisTemplate(connectionFactory);
        // 熔断阈值放宽：IT 里 Redis 一直健康，不希望偶发失败触发熔断干扰断言
        safeRedis = new SafeRedisTemplate(redisTemplate,
                new com.jyu.cache.common.RedisCircuitBreaker(10, 1000L, System::currentTimeMillis));

        cacheProperties = new CacheProperties();
        cacheProperties.setPrefix("cache:product");
        cacheProperties.setTtl(1800L);
        cacheProperties.setTtlRandomSeconds(300);
        cacheProperties.setPreheatCount(10);
        cacheProperties.setHotRankMaxSize(200);
        cacheProperties.setHotRankTtlDays(7);
    }

    @AfterAll
    static void teardown() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @BeforeEach
    void clean() {
        RedisConnection connection = connectionFactory.getConnection();
        try {
            connection.serverCommands().flushAll();
        } finally {
            connection.close();
        }
    }

    // ==================== 序列化（生产配置） ====================

    @Test
    @DisplayName("生产序列化配置：Product（含 LocalDateTime）写入后能原样读回")
    void productionSerializer_roundTripsProduct() {
        Product product = new Product(1L, "iPhone 15 Pro Max", 1L, new BigDecimal("9999.00"),
                500, "描述", 1, 99985L, LocalDateTime.now(), LocalDateTime.now());

        redisTemplate.opsForValue().set("cache:product:1", product, 1800, java.util.concurrent.TimeUnit.SECONDS);
        Object cached = redisTemplate.opsForValue().get("cache:product:1");

        assertNotNull(cached, "缓存应存在");
        assertTrue(cached instanceof Product, "生产配置应能还原为 Product，实际=" + cached.getClass());
        Product read = (Product) cached;
        assertEquals("iPhone 15 Pro Max", read.getName());
        assertEquals(0, new BigDecimal("9999.00").compareTo(read.getPrice()));
        assertNotNull(read.getCreateTime(), "JavaTimeModule 未生效时这里会是 null");
    }

    @Test
    @DisplayName("穿透防护的哨兵值：空值标记写入后能按字符串读回（不是被包成对象）")
    void nullMarker_roundTripsAsString() {
        redisTemplate.opsForValue().set("cache:product:999999", "NULL_VALUE_MARKER", 60,
                java.util.concurrent.TimeUnit.SECONDS);

        Object marker = redisTemplate.opsForValue().get("cache:product:999999");

        assertEquals("NULL_VALUE_MARKER", marker, "读回值必须与 ProductServiceImpl.NULL_VALUE 常量一致");
    }

    // ==================== SCAN 替代 KEYS ====================

    @Test
    @DisplayName("SCAN：游标扫描能找出全部商品缓存 key（不阻塞 Redis 主线程）")
    void scanKeys_findsAllProductKeys() {
        for (long id = 1; id <= 5; id++) {
            redisTemplate.opsForValue().set("cache:product:" + id, "v" + id, 60, java.util.concurrent.TimeUnit.SECONDS);
        }
        redisTemplate.opsForValue().set("login:token:abc", "t", 60, java.util.concurrent.TimeUnit.SECONDS);

        List<String> keys = safeRedis.scanKeys("cache:product:*");

        assertEquals(5, keys.size(), "应只匹配 cache:product: 前缀，实际=" + keys);
    }

    // ==================== ZSet 热度榜 ====================

    @Test
    @DisplayName("热度榜：ZINCRBY 累加分数，删除商品后 ZREM 移除成员")
    void hotspotZSet_accumulatesAndRemoves() {
        HotSpotService hotSpotService = new HotSpotService(
                Mockito.mock(ProductMapper.class),   // recordAccess/removeHot 不查库，仅避免 null
                redisTemplate, safeRedis, cacheProperties);

        hotSpotService.recordAccess(1L);
        hotSpotService.recordAccess(1L);
        hotSpotService.recordAccess(2L);

        assertEquals(2.0, redisTemplate.opsForZSet().score("cache:hotspot:rank", "1"));
        assertEquals(1.0, redisTemplate.opsForZSet().score("cache:hotspot:rank", "2"));

        hotSpotService.removeHot(1L);
        assertNull(redisTemplate.opsForZSet().score("cache:hotspot:rank", "1"));
    }
}
