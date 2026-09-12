package com.jyu.cache.common;

import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Redisson 分布式锁工具（v3 新增）
 *
 * v2 问题：击穿防护用 JVM 本地 synchronized，多实例部署时每个 JVM 一把锁，
 *          防护完全失效（N 个实例 = N 个线程同时回源）。
 *
 * v3 方案：Redisson RLock，锁存在 Redis 里，全集群只有一把：
 *   - tryLock(waitTime) 未指定 leaseTime → 启用看门狗（watchdog），
 *     默认 30 秒租约并每 10 秒自动续期，业务没执行完锁不会过期；
 *   - 拿不到锁（超时/Redis 故障）→ 优雅降级执行 fallback，不阻塞用户请求。
 *
 * v3.2：接入本地熔断。Redis 故障时 Redisson 也要等自己的连接重试（实测是 28.8s 里的大头），
 *       熔断打开期间直接走 fallback，不再尝试加锁。
 */
@Slf4j
@Component
public class DistributedLock {

    private final RedissonClient redisson;
    private final RedisCircuitBreaker breaker;

    public DistributedLock(RedissonClient redisson, RedisCircuitBreaker breaker) {
        this.redisson = redisson;
        this.breaker = breaker;
    }

    /**
     * 带分布式锁执行：拿锁成功 -> 执行 action；失败/异常 -> 执行 fallback
     *
     * @param key      锁的 key（自动加 lock: 前缀，与其他业务 key 区分）
     * @param waitMs   最多等待拿锁的毫秒数（超时走 fallback，不让用户干等）
     * @param action   拿到锁后执行的动作（如：双重检查缓存 -> 查库 -> 写缓存）
     * @param fallback 未拿到锁 / Redis 故障时的降级动作（如：直接查库，不写缓存）
     */
    public <T> T executeWithLock(String key, long waitMs, Supplier<T> action, Supplier<T> fallback) {
        // 熔断打开：Redis 明显不可用，直接降级（否则 Redisson 会等连接重试，把请求拖到几十秒）
        if (breaker.isOpen()) {
            log.warn("[分布式锁] Redis 熔断中，key={} 直接走降级", key);
            return fallback.get();
        }
        RLock lock = null;
        boolean locked = false;
        try {
            // getLock 也在 try 内：Redisson 连接故障时直接走降级，不上抛
            lock = redisson.getLock("lock:" + key);
            // 两参 tryLock：等待 waitMs，租约交给看门狗自动续期
            locked = lock.tryLock(waitMs, TimeUnit.MILLISECONDS);
            if (!locked) {
                // 排队等待超时属正常竞争，不算 Redis 故障，不记入熔断
                log.warn("[分布式锁] key={} 等待 {}ms 未拿到锁，降级执行", key, waitMs);
                return fallback.get();
            }
            breaker.recordSuccess();
            return action.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[分布式锁] key={} 获取锁被中断，降级执行", key);
            return fallback.get();
        } catch (Exception e) {
            // Redisson 连接异常等：锁服务本身不可用，不能拖垮主流程；同时反馈给熔断器
            breaker.recordFailure("分布式锁 " + key + " -> " + e.getMessage());
            log.warn("[分布式锁] key={} 获取锁失败（{}），降级执行", key, e.getMessage());
            return fallback.get();
        } finally {
            if (locked) {
                try {
                    if (lock.isHeldByCurrentThread()) {
                        lock.unlock();
                    }
                } catch (Exception e) {
                    log.warn("[分布式锁] key={} 释放锁失败：{}", key, e.getMessage());
                }
            }
        }
    }

    /**
     * 定时任务的"多实例互斥执行"（v3.3 新增）
     *
     * 【为什么需要】`@Scheduled` 在**每个实例**上都会触发。多实例部署时：
     *   - 自动预热被 N 个实例重复执行：重复查库、重复裁剪榜单，`autoPreheatRounds` 被重复累加；
     *   - 订单超时扫描也是 N 份重复劳动（正确性靠条件 UPDATE 兜住，但纯属浪费）。
     *
     * 【关键设计：任务跑完"不释放锁"，而是让租约自然到期】
     * 一开始的实现是任务结束就 unlock，结果实测两个实例各自 60 秒定时器相位不同：
     * A 跑完立刻放锁 -> B 的定时器 10 秒后触发，又抢到这把空锁 -> 一轮被跑了两次
     * （实测 130 秒里轮次 +5 而不是 +2）。所以这里采用 ShedLock 的思路：
     *   - 抢到锁的实例**持有租约到 leaseMs 结束**，租约时长≈调度间隔，
     *     从而"同一个调度周期内"只可能有一个实例执行；
     *   - 实例崩溃时不主动释放也没关系：显式 leaseTime 会到期自动解锁，
     *     下一轮（间隔 60s > 租约 50s）自然有人能抢到，不会把任务永久锁死。
     *
     * @param key      任务名（内部拼成 lock:scheduled:&lt;key&gt;）
     * @param leaseMs  租约时长（毫秒）：**应≈调度间隔**（如 60s 调度配 50s 租约），
     *                 略小于间隔以保证下一轮能抢到，又足够覆盖本轮
     * @param failOpen Redis 不可用/熔断时是否仍执行：
     *                 false = 跳过本轮（如缓存预热，下一轮再来即可）
     *                 true  = 照常执行（如订单超时取消，宁可多扫也不能漏扫）
     * @return 本轮是否真的执行了任务
     */
    public boolean tryExecuteOnce(String key, long leaseMs, boolean failOpen, Runnable task) {
        if (breaker.isOpen()) {
            if (failOpen) {
                log.warn("[分布式锁] Redis 熔断中，定时任务 {} 按 fail-open 直接执行", key);
                task.run();
                return true;
            }
            log.warn("[分布式锁] Redis 熔断中，定时任务 {} 跳过本轮（fail-closed，避免多实例重复执行）", key);
            return false;
        }
        RLock lock;
        boolean locked;
        try {
            lock = redisson.getLock("lock:scheduled:" + key);
            // waitTime=0：不等待，抢不到就跳过本轮；显式 leaseTime：不用看门狗，崩溃后租约自动到期
            locked = lock.tryLock(0L, leaseMs, TimeUnit.MILLISECONDS);
            breaker.recordSuccess();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return runTaskOnLockFailure(key, failOpen, task);
        } catch (Exception e) {
            breaker.recordFailure("定时任务抢占 " + key + " -> " + e.getMessage());
            log.warn("[分布式锁] 定时任务 {} 抢占失败：{}", key, e.getMessage());
            return runTaskOnLockFailure(key, failOpen, task);
        }
        if (!locked) {
            log.info("[分布式锁] 定时任务 {} 本轮已由其他实例执行，跳过（租约 {}ms）", key, leaseMs);
            return false;
        }
        // 注意：这里**故意不 unlock** —— 持有租约到本轮结束，防止相位不同的其它实例重复执行。
        // 租约到期由 Redis 自动释放，无需（也不能）手动清理。
        log.info("[分布式锁] 定时任务 {} 抢到本轮执行权（租约 {}ms，不主动释放）", key, leaseMs);
        task.run();
        return true;
    }

    /** 抢锁本身失败（异常/中断）时的分支：由 failOpen 决定跑还是跳 */
    private boolean runTaskOnLockFailure(String key, boolean failOpen, Runnable task) {
        if (failOpen) {
            log.warn("[分布式锁] 定时任务 {} 抢占失败但 fail-open=true，仍然执行", key);
            task.run();
            return true;
        }
        log.warn("[分布式锁] 定时任务 {} 抢占失败且 fail-closed，跳过本轮", key);
        return false;
    }
}
