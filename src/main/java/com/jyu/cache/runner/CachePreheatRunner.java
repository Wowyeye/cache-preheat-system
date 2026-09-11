package com.jyu.cache.runner;

import com.jyu.cache.service.ProductService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 缓存预热启动器
 *
 * v3：预热失败（如 Redis 未就绪）不再只是打日志——短暂等待重试，
 *      兼容 compose 场景下 Redis 健康检查刚通过但连接池尚未完全可用的窗口。
 */
@Slf4j
@Component
public class CachePreheatRunner implements ApplicationRunner {

    private final ProductService productService;

    public CachePreheatRunner(ProductService productService) {
        this.productService = productService;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("========================================");
        log.info("  检测到项目启动，开始执行缓存预热...");
        log.info("========================================");

        int maxRetries = 3;
        for (int i = 1; i <= maxRetries; i++) {
            try {
                int count = productService.preheatCache();
                log.info("========================================");
                log.info("  缓存预热完成！共加载 {} 条热点商品到 Redis", count);
                log.info("  现在用户访问这些商品时将直接命中缓存");
                log.info("========================================");
                return;
            } catch (Exception e) {
                log.warn("缓存预热第 {} 次尝试失败：{}", i, e.getMessage());
                if (i < maxRetries) {
                    try {
                        Thread.sleep(3000L * i);   // 3s / 6s 递增退避
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
        log.error("缓存预热连续 {} 次失败，请检查 Redis 是否启动、数据库连接是否正常", maxRetries);
    }
}
