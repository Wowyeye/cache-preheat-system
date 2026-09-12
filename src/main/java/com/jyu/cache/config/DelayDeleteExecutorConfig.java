package com.jyu.cache.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 延迟双删专用调度器（v3 新增 / v3.1 修正）
 *
 * v2 问题：延迟双删用 new Thread() 裸起线程——无池化、无上限、无监控，
 *          高并发写场景下每次写操作都创建新线程，线程数失控会把 JVM 打爆。
 *
 * v3 方案：专用线程池。v3.1 把“ThreadPoolExecutor + 任务内 sleep”改成“延迟调度”：
 *   旧写法有 2 个核心线程、任务里 Thread.sleep(1500ms)，吞吐上限只有 ~1.3 个任务/秒；
 *   队列满之后 CallerRunsPolicy 会让**提交任务的业务线程**（持有数据库连接、行锁的
 *   请求线程）去 sleep 1.5 秒，容易连锁拖垮连接池。
 *   现在用 ScheduledThreadPoolExecutor.schedule(delay)：
 *     - 等待期间不占用任何工作线程（线程只在真正执行 DEL 时被使用）；
 *     - 延迟队列无界，不存在“队列满反压到业务线程”；
 *     - removeOnCancelPolicy 让取消的任务不再占坑；
 *     - 线程命名 pool-delay-delete-N，日志里可辨识。
 */
@Configuration
public class DelayDeleteExecutorConfig {

    @Bean("delayDeleteExecutor")
    public ScheduledExecutorService delayDeleteExecutor() {
        ThreadFactory threadFactory = new ThreadFactory() {
            private final AtomicInteger seq = new AtomicInteger(1);

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "pool-delay-delete-" + seq.getAndIncrement());
                t.setDaemon(false);
                return t;
            }
        };

        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(2, threadFactory);
        // 取消的任务立即出队，避免延迟队列里堆积已失效的删除动作
        executor.setRemoveOnCancelPolicy(true);
        // 停机时仍执行已排队的延迟任务（DelayDeleteService@PreDestroy 会等待 10s），避免停机丢删
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(true);
        return executor;
    }
}
