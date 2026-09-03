package com.jyu.cache.config;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

/**
 * 缓存参数配置类
 *
 * 对应 application.yml 中的 cache.* 配置项：
 *   prefix          缓存 key 前缀
 *   ttl             缓存过期时间（秒），基础 TTL，实际写入时加随机值防雪崩
 *   nullTtl         空值缓存过期时间（秒），缓存穿透防护用
 *   delayDeleteMs   延迟双删的延迟时间（毫秒）
 *   preheatCount    缓存预热时加载的热点商品数量
 */
@Slf4j
@Data
@Component
@ConfigurationProperties(prefix = "cache")
public class CacheProperties {

    /** 缓存 key 前缀，所有 Redis key 都会以此为前缀，方便区分 */
    private String prefix = "cache:product";

    /** 缓存过期时间（秒），30 分钟 = 1800 秒 */
    private Long ttl = 1800L;

    /** 缓存穿透防护：空值缓存过期时间（秒），60 秒 */
    private Long nullTtl = 60L;

    /** 延迟双删：第二次删除延迟时间（毫秒），1.5 秒 */
    private Long delayDeleteMs = 1500L;

    /** 缓存预热：启动时预加载的热点商品数量（按 view_count 降序取前 N 条） */
    private Integer preheatCount = 10;

    /** 启动时打印缓存配置，方便确认参数已正确加载 */
    @PostConstruct
    public void printConfig() {
        log.info("========================================");
        log.info("  缓存配置加载完成");
        log.info("  Key 前缀: {}", prefix);
        log.info("  缓存TTL: {}秒", ttl);
        log.info("  空值TTL: {}秒", nullTtl);
        log.info("  延迟双删间隔: {}毫秒", delayDeleteMs);
        log.info("  预热数量: {}条", preheatCount);
        log.info("========================================");
    }
}
