package com.jyu.cache.controller;

import com.jyu.cache.common.BusinessException;
import com.jyu.cache.common.Result;
import com.jyu.cache.common.UserContext;
import com.jyu.cache.service.HotSpotService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;
import java.util.List;
import java.util.Map;

/**
 * 热点识别与监控 Controller
 *
 * 接口列表：
 *   GET    /api/hotspot/rank        实时热点排行榜（ZSet 分数降序）
 *   GET    /api/hotspot/auto-stats  自动预热运行状态（Redis 存储，多实例共享）
 *   POST   /api/hotspot/preheat     手动触发一次热点预热（ADMIN）
 */
@Tag(name = "热点", description = "ZSet 热度榜、自动预热状态与手动预热")
@RestController
@RequestMapping("/api/hotspot")
public class HotSpotController {

    @Resource
    private HotSpotService hotSpotService;

    @Operation(summary = "实时热点榜单（游客可读）", description = "ZSet 分数降序取前 topN，默认 10 条")
    @GetMapping("/rank")
    public Result<List<Map<String, Object>>> rank(@RequestParam(defaultValue = "10") int topN) {
        return Result.success(hotSpotService.getHotRank(topN));
    }

    @Operation(summary = "自动预热运行状态（游客可读）", description = "状态存于 Redis，多实例共享")
    @GetMapping("/auto-stats")
    public Result<Map<String, Object>> autoStats() {
        return Result.success(hotSpotService.getAutoPreheatStats());
    }

    @Operation(summary = "手动触发热点预热（ADMIN）", description = "未登录 401，非管理员 403；返回本轮加载条数")
    @SecurityRequirement(name = "token")
    @PostMapping("/preheat")
    public Result<Void> manualPreheat() {
        UserContext.requireAdmin();
        int count = hotSpotService.manualPreheat();
        return Result.success("热点预热完成，本轮加载 " + count + " 条商品", null);
    }
}
