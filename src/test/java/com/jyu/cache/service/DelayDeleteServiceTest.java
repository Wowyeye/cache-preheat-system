package com.jyu.cache.service;

import com.jyu.cache.common.SafeRedisTemplate;
import com.jyu.cache.config.CacheProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DelayDeleteService 单元测试（v3.1 新增）
 *
 * 覆盖两个一致性关键点：
 *   1. 事务内调用时，第一次删除必须等到 afterCommit 才执行（旧实现是提交前就删，
 *      并发读会在"已删缓存、尚未提交"的窗口里读旧值回写，脏缓存可能活到 TTL）；
 *   2. 事务回滚时完全不删（本来就没提交）；
 *   3. 第二次删除是延迟调度执行（等待期间不占工作线程）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("延迟双删（事务提交后失效）单元测试")
class DelayDeleteServiceTest {

    private static final String KEY = "cache:product:1";

    @Mock private SafeRedisTemplate safeRedis;

    private ScheduledExecutorService executor;
    private DelayDeleteService delayDeleteService;

    @BeforeEach
    void setUp() {
        CacheProperties properties = new CacheProperties();
        properties.setDelayDeleteMs(40L);

        executor = Executors.newScheduledThreadPool(2);
        delayDeleteService = new DelayDeleteService(safeRedis, executor, properties);
        // 默认删除成功
        lenient().when(safeRedis.deleteQuietly(anyString())).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("非事务场景：立即删一次 + 延迟后再删一次")
    void evictWithDelay_deletesNowAndAgainAfterDelay() {
        delayDeleteService.evictWithDelay(KEY);

        verify(safeRedis).deleteQuietly(KEY);   // 第一次：同步
        await().atMost(Duration.ofSeconds(3))
                .untilAsserted(() -> verify(safeRedis, times(2)).deleteQuietly(KEY));   // 第二次：延迟
    }

    @Test
    @DisplayName("事务场景：提交前绝不删缓存，afterCommit 才执行第一次删除")
    void evictWithDelay_inTransaction_defersUntilAfterCommit() {
        TransactionSynchronizationManager.initSynchronization();
        delayDeleteService.evictWithDelay(KEY);

        verify(safeRedis, never()).deleteQuietly(anyString());   // 关键：提交前不删

        TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit);

        verify(safeRedis).deleteQuietly(KEY);
        await().atMost(Duration.ofSeconds(3))
                .untilAsserted(() -> verify(safeRedis, times(2)).deleteQuietly(KEY));
    }

    @Test
    @DisplayName("事务回滚：一次都不删（数据没提交，缓存本来就不该失效）")
    void evictWithDelay_onRollback_neverDeletes() {
        TransactionSynchronizationManager.initSynchronization();
        delayDeleteService.evictWithDelay(KEY);

        TransactionSynchronizationManager.getSynchronizations().forEach(
                synchronization -> synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        verify(safeRedis, never()).deleteQuietly(anyString());
    }

    @Test
    @DisplayName("删除失败会被记账（一致性缺口可观测，而不是只写日志）")
    void deleteFailures_areCounted() {
        when(safeRedis.deleteQuietly(KEY)).thenReturn(false);   // 两次都失败（Redis 异常或熔断）

        delayDeleteService.evictWithDelay(KEY);

        assertEquals(1, delayDeleteService.getFirstDeleteFailures());
        await().atMost(Duration.ofSeconds(3))
                .untilAsserted(() -> assertEquals(1, delayDeleteService.getSecondDeleteFailures()));
    }

    @Test
    @DisplayName("第二次删除成功时不记账")
    void secondDeleteSuccess_keepsCounterZero() {
        delayDeleteService.evictWithDelay(KEY);

        await().atMost(Duration.ofSeconds(3))
                .untilAsserted(() -> verify(safeRedis, times(2)).deleteQuietly(KEY));
        assertEquals(0, delayDeleteService.getFirstDeleteFailures());
        assertEquals(0, delayDeleteService.getSecondDeleteFailures());
    }
}
