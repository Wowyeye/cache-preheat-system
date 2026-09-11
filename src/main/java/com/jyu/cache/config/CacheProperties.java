package com.jyu.cache.config;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * 缓存参数配置类
 *
 * 对应 application.yml 中的 cache.* 配置项（全部可通过环境变量覆盖，例如 CACHE_TTL）：
 *   prefix            缓存 key 前缀
 *   ttl               缓存过期时间（秒），基础 TTL，实际写入时加随机值防雪崩
 *   ttlRandomSeconds  防雪崩随机抖动上限（秒）
 *   nullTtl           空值缓存过期时间（秒），缓存穿透防护用
 *   delayDeleteMs     延迟双删的延迟时间（毫秒）
 *   preheatCount      缓存预热时加载的热点商品数量
 *   lockWaitMs        分布式锁获取等待时间（毫秒），超时则降级为直接回源
 *   hotRankMaxSize    热度榜 ZSet 最大保留成员数（防无限增长）
 *   hotRankTtlDays    热度榜 ZSet 整体过期天数（防长期堆积）
 */
@Slf4j
@Data
@Component
@ConfigurationProperties(prefix = "cache")
public class CacheProperties {

    /** 缓存 key 前缀 */
    private String prefix = "cache:product";

    /** 缓存过期时间（秒），30 分钟 = 1800 秒 */
    private Long ttl = 1800L;

    /** 防雪崩：TTL 随机抖动上限（秒） */
    private Integer ttlRandomSeconds = 300;

    /** 空值缓存过期时间（秒） */
    private Long nullTtl = 60L;

    /** 延迟双删：第二次删除延迟时间（毫秒） */
    private Long delayDeleteMs = 1500L;

    /** 缓存预热：预加载的热点商品数量 */
    private Integer preheatCount = 10;

    /** 分布式锁获取等待时间（毫秒） */
    private Long lockWaitMs = 3000L;

    /** 热度榜最大保留成员数 */
    private Integer hotRankMaxSize = 200;

    /** 热度榜过期天数 */
    private Integer hotRankTtlDays = 7;

    @PostConstruct
    public void printConfig() {
        log.info("========================================");
        log.info("  [v3] 缓存配置加载完成");
        log.info("  Key前缀: {} | TTL: {}s(+随机{}s) | 空值TTL: {}s", prefix, ttl, ttlRandomSeconds, nullTtl);
        log.info("  延迟双删间隔: {}ms | 锁等待: {}ms | 榜单容量: {} / {}天",
                delayDeleteMs, lockWaitMs, hotRankMaxSize, hotRankTtlDays);
        log.info("========================================");
    }
}
