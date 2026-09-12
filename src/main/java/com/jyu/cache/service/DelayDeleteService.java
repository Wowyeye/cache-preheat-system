package com.jyu.cache.service;

import com.jyu.cache.common.SafeRedisTemplate;
import com.jyu.cache.config.CacheProperties;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 延迟双删服务（v3 抽出版 / v3.1 修正时序）
 *
 * v2 三个问题：
 *   1. new Thread() 裸起线程：无池化无上限，高并发下线程失控；
 *   2. 只删一次缓存里的“值”，失败无感知；
 *   3. 散落在业务代码里，无法统一观测。
 *
 * v3.1 修正（关键一致性修复）：
 *   - **失效动作后置到事务提交之后**（TransactionSynchronization.afterCommit）：
 *     旧实现在 @Transactional 的写路径里立刻删缓存，删除发生在 DB 提交之前——
 *     并发读线程会在“已删缓存、尚未提交”的窗口里读到旧值并回写缓存，
 *     而第二次删除又是固定 1.5s 定时、与提交时刻无关，于是脏缓存可能活到 TTL 到期。
 *     现在两次删除都相对“提交时刻”计时，窗口语义才成立；事务回滚时则完全不删（本就没提交）。
 *   - **第二次删除改为延迟调度**（ScheduledExecutorService.schedule）：
 *     不再用“工作线程 sleep 1.5s”，也就没有队列满 CallerRuns 把业务线程拖住的风险。
 *   - 第一次删除失败也计入指标，不再只记日志。
 *
 * 为什么仍保留“延迟双删”而不是上 Canal/MQ：
 *   本系统单写源（同一应用写库），双删 + 读时短暂不一致窗口（<2s）在演示与中小规模
 *   场景下足够；Canal 属于引入 binlog 订阅的重型方案。真正的兜底仍是缓存 TTL。
 */
@Slf4j
@Service
public class DelayDeleteService {

    private final SafeRedisTemplate safeRedis;
    private final ScheduledExecutorService delayDeleteExecutor;
    private final CacheProperties cacheProperties;

    /** 第一次删除失败次数 */
    private final AtomicLong firstDeleteFailures = new AtomicLong();

    /** 第二次删除失败次数（暴露一致性缺口，运维巡检指标） */
    private final AtomicLong secondDeleteFailures = new AtomicLong();

    public DelayDeleteService(SafeRedisTemplate safeRedis,
                              ScheduledExecutorService delayDeleteExecutor,
                              CacheProperties cacheProperties) {
        this.safeRedis = safeRedis;
        this.delayDeleteExecutor = delayDeleteExecutor;
        this.cacheProperties = cacheProperties;
    }

    /**
     * 延迟双删：写库完成后调用
     *   1. 立即删除缓存（第一次）——若当前在事务中，则等提交成功后再执行；
     *   2. 再延迟 delay-delete-ms 删除一次（第二次，清掉读请求在窗口内写回的旧值）。
     */
    public void evictWithDelay(String key) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    evictNow(key);
                    scheduleSecondDelete(key);
                }
            });
            log.debug("[延迟双删] key={} 已注册为事务提交后执行", key);
            return;
        }
        evictNow(key);
        scheduleSecondDelete(key);
    }

    /** 第一次删除：同步执行，失败只记账不阻断主流程（走 SafeRedis：带降级 + 熔断短路） */
    private void evictNow(String key) {
        if (safeRedis.deleteQuietly(key)) {
            log.info("[延迟双删] key={} 第一次删除完成", key);
        } else {
            long fails = firstDeleteFailures.incrementAndGet();
            log.error("[延迟双删] key={} 第一次删除未成功（累计 {} 次；Redis 熔断或异常），存在脏缓存风险，TTL 兜底",
                    key, fails);
        }
    }

    /** 第二次删除：延迟调度执行（等待期间不占用工作线程） */
    private void scheduleSecondDelete(String key) {
        long delayMs = cacheProperties.getDelayDeleteMs();
        try {
            delayDeleteExecutor.schedule(() -> {
                if (safeRedis.deleteQuietly(key)) {
                    log.info("[延迟双删] key={} 第二次删除完成（延迟 {}ms）", key, delayMs);
                } else {
                    long fails = secondDeleteFailures.incrementAndGet();
                    log.error("[延迟双删] key={} 第二次删除未成功（累计失败 {} 次；Redis 熔断或异常），存在短暂脏缓存风险，TTL 兜底",
                            key, fails);
                }
            }, delayMs, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            // 停机中：第二次删除排不进去，记一次失败，靠 TTL 兜底
            secondDeleteFailures.incrementAndGet();
            log.error("[延迟双删] key={} 第二次删除未能排入调度（{}），依赖 TTL 兜底", key, e.getMessage());
        }
    }

    /** 供监控接口读取：第一次删除失败累计 */
    public long getFirstDeleteFailures() {
        return firstDeleteFailures.get();
    }

    /** 供监控接口读取：第二次删除失败累计 */
    public long getSecondDeleteFailures() {
        return secondDeleteFailures.get();
    }

    /** 优雅停机：等待池内双删任务执行完，避免停机丢删 */
    @PreDestroy
    public void shutdown() {
        delayDeleteExecutor.shutdown();
        try {
            if (!delayDeleteExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                log.warn("[延迟双删] 停机等待超时，剩余延迟删除任务未执行（TTL 兜底）");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
