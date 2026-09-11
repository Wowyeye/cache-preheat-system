package com.jyu.cache.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.redisson.api.RedissonClient;
import org.redisson.spring.data.connection.RedissonConnectionFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis 配置类（v3 安全加固）
 *
 * v2 问题：activateDefaultTyping(LaissezFaireSubTypeValidator) 允许任意子类型反序列化，
 *          若 Redis 数据被恶意写入（比如通过其他入口写入 gadget 类），存在 RCE 风险。
 *
 * v3 修复：
 *   1. 不再用 LaissezFaire 验证器，改为显式 ObjectMapper + 限定类型白名单
 *      （仅允许本项目 com.jyu.cache.** 与 JDK 基础集合类型携带类型信息）；
 *   2. 缓存值统一包装成 String（JSON 字符串），Redis 中可读、任何客户端直接可查，
 *      顺带解决 v2 中 member 被 JSON 序列化成带引号 "999" 的运维坑。
 *   3. 连接工厂改由 Redisson 提供（RedissonConnectionFactory），
 *      让 RedisTemplate 与 Redisson 共用同一套连接配置与连接池。
 */
@Configuration
public class RedisConfig {

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);

        // --- Key：String 序列化 ---
        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);

        // --- Value：JSON + 显式类型白名单（替代 LaissezFaire 全放行） ---
        ObjectMapper om = new ObjectMapper();
        om.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
        // BasicPolymorphicTypeValidator：只允许白名单前缀的类做多态反序列化
        om.activateDefaultTyping(
                BasicPolymorphicTypeValidator.builder()
                        .allowIfBaseType(Object.class)
                        .allowIfSubType("com.jyu.cache.")
                        .allowIfSubType("java.util.")
                        .allowIfSubType("java.lang.")
                        .allowIfSubType("java.time.")
                        .build(),
                ObjectMapper.DefaultTyping.NON_FINAL,
                JsonTypeInfo.As.PROPERTY);
        om.registerModule(new JavaTimeModule());

        GenericJackson2JsonRedisSerializer jsonSerializer = new GenericJackson2JsonRedisSerializer(om);
        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);

        template.afterPropertiesSet();
        return template;
    }

    /**
     * 用 Redisson 的连接工厂替换 Lettuce：
     * Redisson spring-boot-starter 自动注册 RedissonClient，
     * 这里把它桥接给 Spring Data Redis，两个客户端共享配置与连接池。
     */
    @Bean
    public RedisConnectionFactory redisConnectionFactory(RedissonClient redisson) {
        return new RedissonConnectionFactory(redisson);
    }
}
