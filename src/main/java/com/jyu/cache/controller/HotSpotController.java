package com.jyu.cache.controller;

import com.jyu.cache.common.BusinessException;
import com.jyu.cache.common.Result;
import com.jyu.cache.common.UserContext;
import com.jyu.cache.service.HotSpotService;
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
@RestController
@RequestMapping("/api/hotspot")
public class HotSpotController {

    @Resource
    private HotSpotService hotSpotService;

    @GetMapping("/rank")
    public Result<List<Map<String, Object>>> rank(@RequestParam(defaultValue = "10") int topN) {
        return Result.success(hotSpotService.getHotRank(topN));
    }

    @GetMapping("/auto-stats")
    public Result<Map<String, Object>> autoStats() {
        return Result.success(hotSpotService.getAutoPreheatStats());
    }

    @PostMapping("/preheat")
    public Result<Void> manualPreheat() {
        requireAdmin();
        int count = hotSpotService.manualPreheat();
        return Result.success("热点预热完成，本轮加载 " + count + " 条商品", null);
    }

    private void requireAdmin() {
        UserContext.LoginUser user = UserContext.getUser();
        if (user == null || !user.isAdmin()) {
            throw new BusinessException(403, "需要管理员权限");
        }
    }
}
