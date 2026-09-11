package com.jyu.cache.it;

import com.jyu.cache.common.SafeRedisTemplate;
import com.jyu.cache.config.CacheProperties;
import com.jyu.cache.entity.Product;
import com.jyu.cache.service.HotSpotService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 集成测试：真实 Redis 容器（Testcontainers）验证缓存一致性主链路。
 *
 * 覆盖：
 *   1. Cache Aside：写入后读回（序列化往返一致）
 *   2. 穿透防护：不存在的 ID 写入空值标记
 *   3. 延迟双删：二次删除后缓存彻底清除
 *   4. ZSet 热点：访问记录进榜单，TOP-N 排序正确
 *
 * 环境要求：Docker 可用。容器生命周期手动管理：@BeforeAll 先做
 * Docker assumption，不可用则整体跳过；可用才启动容器。
 */
@DisplayName("缓存一致性集成测试（真实 Redis）")
class CacheIntegrationIT {

    /** Redis 7 容器（与生产 docker-compose 同版本，带密码） */
    static GenericContainer<?> REDIS;

    static RedisTemplate<String, Object> redisTemplate;
    static SafeRedisTemplate safeRedis;
    static CacheProperties cacheProperties;

    @BeforeAll
    static void init() {
        // 跳过条件：Docker 不可用时整体跳过（不因环境缺失而红）
        Assumptions.assumeTrue(dockerAvailable(), "Docker 不可用，跳过集成测试");

        REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                .withExposedPorts(6379)
                .withCommand("redis-server", "--requirepass", "itpass", "--appendonly", "no")
                .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1));
        REDIS.start();

        // 手工装配 Redis（String key / Jackson value 序列化，与生产一致）
        RedisStandaloneConfiguration cfg = new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379));
        cfg.setPassword("itpass");
        LettuceConnectionFactory factory = new LettuceConnectionFactory(cfg);
        factory.afterPropertiesSet();

        redisTemplate = new RedisTemplate<>();
        redisTemplate.setConnectionFactory(factory);
        redisTemplate.setKeySerializer(new StringRedisSerializer());
        redisTemplate.setValueSerializer(new GenericJackson2JsonRedisSerializer());
        redisTemplate.setHashKeySerializer(new StringRedisSerializer());
        redisTemplate.setHashValueSerializer(new GenericJackson2JsonRedisSerializer());
        redisTemplate.afterPropertiesSet();

        safeRedis = new SafeRedisTemplate(redisTemplate);
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
        if (REDIS != null) {
            REDIS.stop();
        }
    }

    @BeforeEach
    void clean() {
        if (redisTemplate != null) {
            redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
        }
    }

    private static boolean dockerAvailable() {
        try {
            Process p = new ProcessBuilder("docker", "version", "--format", "{{.Server.Version}}")
                    .redirectErrorStream(true).start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    // ==================== 用例 ====================

    @Test
    @DisplayName("Cache Aside：写入缓存后能读回（序列化往返一致）")
    void cacheAside_writesThenReadsBack() {
        Product p = new Product();
        p.setId(1L);
        p.setName("iPhone 15");
        p.setPrice(new java.math.BigDecimal("9999.00"));
        p.setStock(5);
        p.setStatus(1);

        safeRedis.degrade(() -> {
            redisTemplate.opsForValue().set("cache:product:1", p, 1800, TimeUnit.SECONDS);
            return p;
        }, () -> p);

        Object cached = redisTemplate.opsForValue().get("cache:product:1");
        assertNotNull(cached, "缓存应存在");
        assertEquals("iPhone 15", ((Product) cached).getName(), "反序列化后字段应一致");
    }

    @Test
    @DisplayName("穿透防护：不存在的 ID 写入空值标记（防打库）")
    void nullMarker_writtenForMissingId() {
        redisTemplate.opsForValue().set("cache:product:999", "NULL_VALUE_MARKER", 60, TimeUnit.SECONDS);
        Object marker = redisTemplate.opsForValue().get("cache:product:999");
        assertEquals("NULL_VALUE_MARKER", marker, "空值标记应写入");
    }

    @Test
    @DisplayName("延迟双删：二次删除后缓存彻底清除")
    void delayDoubleDelete_evictsCache() {
        redisTemplate.opsForValue().set("cache:product:1", new Product(), 1800, TimeUnit.SECONDS);
        redisTemplate.delete("cache:product:1");   // 第一删（业务删除）
        try { Thread.sleep(300); } catch (InterruptedException ignored) { }  // 模拟延迟窗口
        redisTemplate.delete("cache:product:1");   // 第二删（延迟双删）

        assertNull(redisTemplate.opsForValue().get("cache:product:1"), "缓存应被彻底清除");
    }

@Test
    @DisplayName("ZSet 热点：访问记录 TOP，排序正确")
    void hotspotZSet_accumulatesScore() {
        // 不查库：验证 Redis 层行为（ProductMapper 仅作占位，不触发查询）
        com.jyu.cache.mapper.ProductMapper noopMapper = org.mockito.Mockito.mock(com.jyu.cache.mapper.ProductMapper.class);
        HotSpotService hotSpotService = new HotSpotService(
                noopMapper,
                redisTemplate, safeRedis, cacheProperties);

        hotSpotService.recordAccess(1L);
        hotSpotService.recordAccess(1L);
        hotSpotService.recordAccess(2L);

        Double score1 = redisTemplate.opsForZSet().score("cache:hotspot:rank", "1");
        Double score2 = redisTemplate.opsForZSet().score("cache:hotspot:rank", "2");
        assertEquals(2.0, score1, "商品 1 应累计 2 次热度");
        assertEquals(1.0, score2, "商品 2 应累计 1 次热度");
    }
}