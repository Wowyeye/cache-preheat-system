package com.jyu.cache.runner;

import com.jyu.cache.service.ProductService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * 缓存预热启动器
 */
@Slf4j
@Component
public class CachePreheatRunner implements ApplicationRunner {

    @Resource
    private ProductService productService;

    @Override
    public void run(ApplicationArguments args) {
        log.info("========================================");
        log.info("  检测到项目启动，开始执行缓存预热...");
        log.info("========================================");

        try {
            int count = productService.preheatCache();
            log.info("========================================");
            log.info("  缓存预热完成！共加载 {} 条热点商品到 Redis", count);
            log.info("  现在用户访问这些商品时将直接命中缓存");
            log.info("========================================");
        } catch (Exception e) {
            log.error("缓存预热失败！请检查 Redis 是否启动、数据库连接是否正常", e);
        }
    }
}
