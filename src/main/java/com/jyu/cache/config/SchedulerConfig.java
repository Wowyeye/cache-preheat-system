package com.jyu.cache.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 定时任务线程池配置
 *
 * v2 问题：@Scheduled 默认单线程，所有定时任务（自动预热、订单超时取消）串行，
 *          一个任务卡住会拖死全部调度。
 * v3 修复：显式配置调度线程池（池大小、线程名、关闭策略），任务相互隔离。
 */
@Configuration
public class SchedulerConfig {

    @Bean
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("sched-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        return scheduler;
    }
}
