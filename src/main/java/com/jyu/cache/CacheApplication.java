package com.jyu.cache;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 热点数据缓存预热与缓存一致性保障系统 - 启动类（v3 工程化版）
 *
 * v2 核心能力全部保留：
 * 1. 缓存预热：启动时自动加载热点商品
 * 2. Cache Aside 旁路缓存
 * 3. 延迟双删（线程池化）
 * 4. 缓存穿透防护（空值缓存）
 * 5. 缓存击穿防护（v3 升级为 Redisson 分布式锁）
 * 6. 缓存雪崩防护（TTL 随机抖动）
 * 7. 自动热点识别（ZSet + 防污染）
 * 8. 缓存监控大盘（v3 指标入 Redis）
 *
 * v3 工程化新增：
 * 9. Flyway 数据库版本化迁移
 * 10. Redis 降级（缓存故障自动直查 DB）
 * 11. SCAN 替代 KEYS
 * 12. 登录限流 + 待支付订单超时自动取消
 * 13. Actuator 健康检查 + 日志文件切割
 * 14. Docker/compose 一键部署
 */
@SpringBootApplication
@MapperScan("com.jyu.cache.mapper")
@EnableScheduling
public class CacheApplication {

    public static void main(String[] args) {
        SpringApplication.run(CacheApplication.class, args);
    }
}
