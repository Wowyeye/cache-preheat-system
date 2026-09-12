package com.jyu.cache.config;

import com.jyu.cache.entity.Product;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Redis 序列化配置单元测试（v3.1 新增）
 *
 * 存在的意义：v3 初版宣称"用显式类型白名单替代 LaissezFaire 验证器"，
 * 但实际上 allowIfBaseType(Object.class) 把白名单整体 open 了（实测 java.io.File 可正常反序列化），
 * 也就是说那句安全声明是空的。这里用**生产同一份 ObjectMapper** 把两件事都钉死：
 *   1. 项目真实写入 Redis 的类型必须能正常往返（否则缓存直接不可用）；
 *   2. 白名单外的类型必须被拒绝（否则"安全加固"又变回一句注释）。
 */
@DisplayName("Redis 序列化（类型白名单）单元测试")
class RedisSerializationTest {

    private final GenericJackson2JsonRedisSerializer serializer =
            new GenericJackson2JsonRedisSerializer(RedisConfig.redisObjectMapper());

    @SuppressWarnings("unchecked")
    private <T> T roundTrip(T value) {
        byte[] bytes = serializer.serialize(value);
        return (T) serializer.deserialize(bytes);
    }

    @Test
    @DisplayName("实体 Product（含 BigDecimal / LocalDateTime）往返一致")
    void product_roundTrips() {
        Product product = new Product(1L, "iPhone 15", 1L, new BigDecimal("9999.00"), 5, "描述", 1, 99L,
                LocalDateTime.now(), LocalDateTime.now());

        Product read = roundTrip(product);

        assertInstanceOf(Product.class, read);
        assertEquals("iPhone 15", read.getName());
        assertEquals(0, new BigDecimal("9999.00").compareTo(read.getPrice()));
        assertNotNull(read.getCreateTime(), "JavaTimeModule 未生效时会是 null");
    }

    @Test
    @DisplayName("空值标记哨兵：字符串往返一致（穿透防护依赖它）")
    void nullMarker_roundTrips() {
        assertEquals("NULL_VALUE_MARKER", roundTrip("NULL_VALUE_MARKER"));
    }

    @Test
    @DisplayName("登录态/统计用的 Map 往返一致")
    void map_roundTrips() {
        Map<String, Object> data = new HashMap<>();
        data.put("id", 1L);
        data.put("role", "ADMIN");

        Map<String, Object> read = roundTrip(data);

        assertEquals("ADMIN", read.get("role"));
        assertEquals(1L, Long.valueOf(String.valueOf(read.get("id"))));
    }

    @Test
    @DisplayName("安全回归：白名单外的类型必须被拒绝（v3 初版这里是放行的）")
    void nonWhitelistedType_isRejected() {
        byte[] crafted = "[\"java.io.File\",\"C:/Windows/win.ini\"]".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        Exception ex = assertThrows(Exception.class, () -> serializer.deserialize(crafted),
                "白名单外的类型不应被反序列化，否则类型白名单形同虚设");
        assertTrue(String.valueOf(ex.getMessage()).contains("denied") || ex instanceof IllegalStateException,
                "期望被 PolymorphicTypeValidator 拒绝，实际：" + ex);
    }
}
