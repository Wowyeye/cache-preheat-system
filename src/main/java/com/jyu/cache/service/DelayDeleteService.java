package com.jyu.cache.service;

import com.jyu.cache.config.CacheProperties;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 延迟双删服务（v3 从 ProductServiceImpl 抽出，线程池化）
 *
 * v2 三个问题：
 *   1. new Thread() 裸起线程：无池化无上限，高并发下线程失控；
 *   2. 只删一次缓存里的"值"，失败无感知；
 *   3. 散落在业务代码里，无法统一观测。
 *
 * v3 方案：
 *   1. 提交到专用有界线程池（见 DelayDeleteExecutorConfig）；
 *   2. 第二次删除失败记 ERROR 日志 + 失败计数（暴露"最终一致"的缺口，供监控告警）；
 *   3. 删除动作本身加异常保护，任何 Redis 抖动不影响主流程。
 *
 * 为什么仍保留"延迟双删"而不是上 Canal/MQ：
 *   本系统单写源（同一应用写库），双删 + 读时短暂不一致窗口（<2s）在演示与中小规模
 *   场景下足够；Canal 属于引入 binlog 订阅的重型方案，README 的"演进路线"章节有说明。
 */
@Slf4j
@Service
public class DelayDeleteService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final ThreadPoolExecutor delayDeleteExecutor;
    private final CacheProperties cacheProperties;

    /** 双删第二次删除失败次数（暴露一致性缺口，运维巡检指标） */
    private final java.util.concurrent.atomic.AtomicLong secondDeleteFailures = new java.util.concurrent.atomic.AtomicLong();

    public DelayDeleteService(RedisTemplate<String, Object> redisTemplate,
                              ThreadPoolExecutor delayDeleteExecutor,
                              CacheProperties cacheProperties) {
        this.redisTemplate = redisTemplate;
        this.delayDeleteExecutor = delayDeleteExecutor;
        this.cacheProperties = cacheProperties;
    }

    /**
     * 延迟双删：写库完成后调用
     *   1. 立即删除缓存（第一次）
     *   2. 延迟 delay-delete-ms 后再删一次（第二次，清掉读请求写回的旧值）
     */
    public void evictWithDelay(String key) {
        // 第一次删除：同步执行，失败也不阻断（记日志）
        try {
            redisTemplate.delete(key);
            log.info("[延迟双删] key={} 第一次删除完成", key);
        } catch (Exception e) {
            log.error("[延迟双删] key={} 第一次删除失败：{}", key, e.getMessage());
        }

        // 第二次删除：异步延迟执行（池化，不再裸起线程）
        delayDeleteExecutor.execute(() -> {
            try {
                Thread.sleep(cacheProperties.getDelayDeleteMs());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("[延迟双删] key={} 延迟等待被中断", key);
                return;
            }
            try {
                redisTemplate.delete(key);
                log.info("[延迟双删] key={} 第二次删除完成（延迟 {}ms）", key, cacheProperties.getDelayDeleteMs());
            } catch (Exception e) {
                long fails = secondDeleteFailures.incrementAndGet();
                log.error("[延迟双删] key={} 第二次删除失败（累计失败 {} 次），存在短暂脏缓存风险，TTL 兜底：{}",
                        key, fails, e.getMessage());
            }
        });
    }

    /** 供监控接口读取：双删失败累计 */
    public long getSecondDeleteFailures() {
        return secondDeleteFailures.get();
    }

    /** 优雅停机：等待池内双删任务执行完，避免停机丢删 */
    @PreDestroy
    public void shutdown() {
        delayDeleteExecutor.shutdown();
        try {
            if (!delayDeleteExecutor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)) {
                log.warn("[延迟双删] 停机等待超时，剩余任务由 CallerRuns 兜底或放弃");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
