package com.jyu.cache.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 延迟双删专用线程池（v3 新增）
 *
 * v2 问题：延迟双删用 new Thread() 裸起线程——无池化、无上限、无监控，
 *          高并发写场景下每次写操作都创建新线程，线程数失控会把 JVM 打爆。
 *
 * v3 方案：有界队列 + 固定核心线程数的专用池：
 *   - 核心 2 线程、最大 4 线程：双删任务本身只是"睡 1.5s + DEL"，轻量；
 *   - 队列容量 1000：突发写流量先排队，不无限创建线程；
 *   - 队列满时 CallerRunsPolicy：让提交线程自己执行，天然背压（反压到上游，防止丢删）；
 *   - 命名线程 + 拒绝计数：日志里能直接看到 pool-delay-delete-1，方便排查。
 */
@Configuration
public class DelayDeleteExecutorConfig {

    @Bean("delayDeleteExecutor")
    public ThreadPoolExecutor delayDeleteExecutor() {
        return new ThreadPoolExecutor(
                2,
                4,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(1000),
                new java.util.concurrent.ThreadFactory() {
                    private final AtomicInteger seq = new AtomicInteger(1);

                    @Override
                    public Thread newThread(Runnable r) {
                        Thread t = new Thread(r, "pool-delay-delete-" + seq.getAndIncrement());
                        t.setDaemon(false);
                        return t;
                    }
                },
                new ThreadPoolExecutor.CallerRunsPolicy());
    }
}
