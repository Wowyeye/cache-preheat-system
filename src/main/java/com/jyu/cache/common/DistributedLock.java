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
 */
@Slf4j
@Component
public class DistributedLock {

    private final RedissonClient redisson;

    public DistributedLock(RedissonClient redisson) {
        this.redisson = redisson;
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
        RLock lock = null;
        boolean locked = false;
        try {
            // getLock 也在 try 内：Redisson 连接故障时直接走降级，不上抛
            lock = redisson.getLock("lock:" + key);
            // 两参 tryLock：等待 waitMs，租约交给看门狗自动续期
            locked = lock.tryLock(waitMs, TimeUnit.MILLISECONDS);
            if (!locked) {
                log.warn("[分布式锁] key={} 等待 {}ms 未拿到锁，降级执行", key, waitMs);
                return fallback.get();
            }
            return action.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[分布式锁] key={} 获取锁被中断，降级执行", key);
            return fallback.get();
        } catch (Exception e) {
            // Redisson 连接异常等：锁服务本身不可用，不能拖垮主流程
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
}
