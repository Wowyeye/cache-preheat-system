package com.jyu.cache.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.redisson.spring.data.connection.RedissonConnectionFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis 配置类（v3 安全加固 / v3.1 修正白名单形同虚设的问题）
 *
 * v2 问题：activateDefaultTyping(LaissezFaireSubTypeValidator) 允许任意子类型反序列化，
 *          若 Redis 数据被恶意写入，存在反序列化 RCE 风险。
 *
 * v3 初版"修复"其实没生效：白名单里带了 allowIfBaseType(Object.class)，
 *   而 Jackson 的多态校验是"命中任一规则即放行"，缓存值的声明基类型恰好就是 Object，
 *   于是这条规则把整个白名单open了——实测 java.io.File 可以正常反序列化（v3.1 已用
 *   RedisSerializationTest 把它固化成回归测试）。
 *
 * v3.1 正确做法：只按"子类型名前缀"白名单放行，且必须覆盖本项目真实写入 Redis 的类型：
 *   com.jyu.cache.  -> 实体（Product 等）
 *   java.lang.      -> String / Integer / Boolean（哨兵值、计数值）
 *   java.util.      -> HashMap（登录态、统计）
 *   java.time.      -> LocalDateTime（create_time/update_time）
 *   java.math.      -> BigDecimal（price）
 *   ⚠️ 给实体新增字段时若用到其他类型的字段，需要把对应包前缀加进来，
 *      否则读缓存会抛 SerializationException（RedisSerializationTest 会先一步失败）。
 *
 * 说明：缓存值仍是"带类型信息的 JSON"（@class），不是纯字符串——
 *      旧注释曾宣称"统一包装成 String 并顺带解决 member 带引号问题"，与实际不符，
 *      已更正；ZSet member 的引号由 HotSpotService 侧做兼容解析。
 */
@Slf4j
@Configuration
public class RedisConfig {

    /**
     * 构建 Redis 专用的 ObjectMapper（独立于 Spring MVC 的 ObjectMapper，避免污染 HTTP JSON 序列化）。
     * 抽成 static 方法是为了让单测能直接验证"白名单是否真的拦得住"。
     */
    public static ObjectMapper redisObjectMapper() {
        ObjectMapper om = new ObjectMapper();
        om.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
        om.activateDefaultTyping(
                BasicPolymorphicTypeValidator.builder()
                        .allowIfSubType("com.jyu.cache.")
                        .allowIfSubType("java.lang.")
                        .allowIfSubType("java.util.")
                        .allowIfSubType("java.time.")
                        .allowIfSubType("java.math.")
                        .build(),
                ObjectMapper.DefaultTyping.NON_FINAL,
                JsonTypeInfo.As.PROPERTY);
        om.registerModule(new JavaTimeModule());
        return om;
    }

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);

        // --- Key：String 序列化 ---
        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);

        // --- Value：JSON + 显式类型白名单 ---
        GenericJackson2JsonRedisSerializer jsonSerializer =
                new GenericJackson2JsonRedisSerializer(redisObjectMapper());
        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);

        template.afterPropertiesSet();
        return template;
    }

    /**
     * RedissonClient（v3.2 改为显式装配）
     *
     * 【为什么要自己建这个 Bean】Redisson starter 会直接把 spring.data.redis.password 原样用上，
     * 而 application.yml 里写的是 `${REDIS_PASSWORD:}`（未设置时解析成**空字符串**）。
     * 空字符串不是 null —— Redisson 会真的发一条 `AUTH `，被未启用密码的 Redis 以
     * `ERR AUTH <password> called without any password configured` 拒绝，
     * 应用直接启动失败（DbLayerIT 实测复现）。
     * 这里的规则：**空串/空白 = 没有密码**，同时也把 Redisson 自己的超时/重试收紧，
     * 让 Redis 故障期的首个失败请求更快触发熔断（见 RedisCircuitBreaker）。
     */
    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient(org.springframework.core.env.Environment env) {
        String host = env.getProperty("spring.data.redis.host", "127.0.0.1");
        int port = env.getProperty("spring.data.redis.port", Integer.class, 6379);
        int database = env.getProperty("spring.data.redis.database", Integer.class, 0);
        String password = env.getProperty("spring.data.redis.password");

        org.redisson.config.Config config = new org.redisson.config.Config();
        config.useSingleServer()
                .setAddress("redis://" + host + ":" + port)
                .setDatabase(database)
                .setConnectTimeout(2000)
                .setTimeout(2000)
                .setRetryAttempts(1)
                .setRetryInterval(500);
        if (password != null && !password.isBlank()) {
            config.useSingleServer().setPassword(password);
        } else {
            log.info("[Redis] 未配置密码（spring.data.redis.password 为空），Redisson 按无密码连接");
        }
        return org.redisson.Redisson.create(config);
    }

    /**
     * 用 Redisson 的连接工厂替换 Lettuce：
     * 让 RedisTemplate 与 Redisson 共用同一套连接配置与连接池。
     */
    @Bean
    public RedisConnectionFactory redisConnectionFactory(RedissonClient redisson) {
        return new RedissonConnectionFactory(redisson);
    }
}
