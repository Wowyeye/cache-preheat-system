package com.jyu.cache.service;

import com.jyu.cache.common.SafeRedisTemplate;
import com.jyu.cache.config.CacheProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * 缓存失效重试队列（v3.4 新增，一致性升级）
 *
 * 【解决的问题】延迟双删失败时，旧实现只做两件事：记 ERROR 日志 + 累加计数。
 * 也就是说 DB 已经改了、缓存却没删掉，脏数据只能等 TTL（1800 秒）自然过期——
 * "最终一致"靠的是运气 + 长 TTL，这是本项目一致性上最大的一块缺口。
 *
 * 【做法】失败即入队，由定时任务按退避策略重试：
 *   - 进程内队列（ConcurrentHashMap 按 key 去重，同一 key 只保留一条）；
 *   - 退避：2s → 4s → 8s → 16s → 32s → 60s，最多 maxAttempts 次；
 *   - **熔断打开时不消耗重试次数**：Redis 整体不可用不是这个 key 的错，
 *     等它恢复再重试，避免故障期把队列里的任务全部"重试耗尽"；
 *   - 重试成功 / 耗尽 都出指标（`cache.evict.retry`），耗尽会打 ERROR 日志并计入
 *     `/api/product/cache/stats` 的缺口统计。
 *
 * 【为什么用进程内队列而不是 Redis/DB 队列】
 *   失效失败往往正是"Redis 不可用"，这时往 Redis 里塞任务同样会失败（先有鸡还是先有蛋）；
 *   而写路径就发生在某个实例上，本实例内存里记着就够了。代价是实例被 kill -9 会丢队列
 *   （此时仍有 TTL 兜底），这一点在 README 的已知限制里写明。
 */
@Slf4j
@Service
public class EvictRetryQueue {

    /** 单轮最多处理多少条，避免一次 drain 扫太久 */
    private static final int DRAIN_BATCH = 200;

    /** 熔断打开时的重新排队间隔（不计入重试次数） */
    private static final long RESCHEDULE_WHEN_CIRCUIT_OPEN_MS = 5_000L;

    /** 退避上限 */
    private static final long MAX_BACKOFF_MS = 60_000L;

    private final SafeRedisTemplate safeRedis;
    private final CacheProperties cacheProperties;
    private final LongSupplier clock;

    /** key -> 待重试条目（按 key 去重；并发写路径可能同时提交同一个 key） */
    private final Map<String, PendingEvict> pending = new ConcurrentHashMap<>();

    private final AtomicLong retrySuccess = new AtomicLong();
    private final AtomicLong exhausted = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();

    private final Counter retrySuccessCounter;
    private final Counter exhaustedCounter;
    private final Counter droppedCounter;

    /** Spring 注入用（类里还有一个给测试的可控时钟构造器，所以要显式标注 @Autowired） */
    @org.springframework.beans.factory.annotation.Autowired
    public EvictRetryQueue(SafeRedisTemplate safeRedis, CacheProperties cacheProperties, MeterRegistry registry) {
        this(safeRedis, cacheProperties, registry, System::currentTimeMillis);
    }

    /** 供测试注入可控时钟（多构造器时 Spring 用上面那个 @Autowired 的） */
    EvictRetryQueue(SafeRedisTemplate safeRedis, CacheProperties cacheProperties,
                    MeterRegistry registry, LongSupplier clock) {
        this.safeRedis = safeRedis;
        this.cacheProperties = cacheProperties;
        this.clock = clock;

        this.retrySuccessCounter = Counter.builder("cache.evict.retry")
                .tag("result", "success").description("缓存失效重试成功次数").register(registry);
        this.exhaustedCounter = Counter.builder("cache.evict.retry")
                .tag("result", "exhausted").description("缓存失效重试耗尽（放弃，靠 TTL 兜底）次数").register(registry);
        this.droppedCounter = Counter.builder("cache.evict.retry")
                .tag("result", "dropped").description("队列满而丢弃的失效任务数").register(registry);
        Gauge.builder("cache.evict.retry.pending", this, EvictRetryQueue::pendingCount)
                .description("待重试的缓存失效任务数").register(registry);
    }

    /** 记录一次失效失败，交给重试队列 */
    public void submit(String key, String reason) {
        PendingEvict existing = pending.get(key);
        if (existing != null) {
            existing.reason = reason;   // 同一 key 已在队列里：只更新原因，不重复排队
            return;
        }
        if (pending.size() >= cacheProperties.getEvictRetryMaxEntries()) {
            long n = dropped.incrementAndGet();
            droppedCounter.increment();
            log.error("[失效重试] 队列已满（{} 条），丢弃 key={} 的失效任务（累计丢弃 {} 次，靠 TTL 兜底）",
                    cacheProperties.getEvictRetryMaxEntries(), key, n);
            return;
        }
        PendingEvict item = new PendingEvict();
        item.key = key;
        item.reason = reason;
        item.attempts = 0;
        item.nextAttemptAt = clock.getAsLong();   // 首次立即重试（多半是瞬时抖动）
        pending.put(key, item);
        log.warn("[失效重试] key={} 入队（原因：{}，当前待重试 {} 条）", key, reason, pending.size());
    }

    /** 定时重试（每个实例处理自己进程内的队列，因此这里不需要分布式互斥） */
    @Scheduled(fixedDelayString = "${cache.evict-retry-interval-ms:5000}")
    public void drain() {
        if (pending.isEmpty()) {
            return;
        }
        long now = clock.getAsLong();
        int handled = 0;
        for (PendingEvict item : pending.values()) {
            if (handled >= DRAIN_BATCH) {
                break;
            }
            if (now < item.nextAttemptAt) {
                continue;
            }
            if (safeRedis.isCircuitOpen()) {
                // Redis 整体不可用：不消耗重试次数，等它恢复
                item.nextAttemptAt = now + RESCHEDULE_WHEN_CIRCUIT_OPEN_MS;
                log.debug("[失效重试] Redis 熔断中，key={} 延后重试", item.key);
                continue;
            }
            handled++;
            if (safeRedis.deleteQuietly(item.key)) {
                pending.remove(item.key);
                retrySuccess.incrementAndGet();
                retrySuccessCounter.increment();
                log.info("[失效重试] key={} 第 {} 次重试成功（原失败原因：{}）",
                        item.key, item.attempts + 1, item.reason);
            } else {
                item.attempts++;
                if (item.attempts >= cacheProperties.getEvictRetryMaxAttempts()) {
                    pending.remove(item.key);
                    long n = exhausted.incrementAndGet();
                    exhaustedCounter.increment();
                    log.error("[失效重试] key={} 重试 {} 次仍失败，放弃（累计放弃 {} 次，脏数据将由 TTL 收敛；原因：{}）",
                            item.key, item.attempts, n, item.reason);
                } else {
                    item.nextAttemptAt = now + backoffMs(item.attempts);
                    log.warn("[失效重试] key={} 第 {} 次重试失败，{}ms 后再试",
                            item.key, item.attempts, backoffMs(item.attempts));
                }
            }
        }
    }

    /** 指数退避：2s, 4s, 8s, 16s, 32s, 60s（封顶） */
    private long backoffMs(int attempts) {
        long candidate = 2_000L << Math.min(attempts - 1, 5);
        return Math.min(candidate, MAX_BACKOFF_MS);
    }

    public int pendingCount() {
        return pending.size();
    }

    public long getRetrySuccess() {
        return retrySuccess.get();
    }

    public long getExhausted() {
        return exhausted.get();
    }

    public long getDropped() {
        return dropped.get();
    }

    /** 待重试条目 */
    private static final class PendingEvict {
        private String key;
        private String reason;
        private int attempts;
        private volatile long nextAttemptAt;
    }
}
