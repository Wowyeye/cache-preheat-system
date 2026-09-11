package com.jyu.cache.service;

import com.jyu.cache.common.UserContext;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Token 服务（登录凭证管理）
 *
 * 设计：token 是一个 UUID，Redis 中保存 token -> 用户信息 的映射，带过期时间。
 * - 登录成功：生成 token 存 Redis（TTL 2 小时），返回给前端
 * - 每次请求：拦截器拿 token 查 Redis，命中则续期（滑动过期）并写入 UserContext
 * - 登出/禁用：删除 Redis key，token 立即失效
 *
 * 呼应项目主题：登录态本身就是一种缓存（有 TTL、有命中、可失效）。
 */
@Service
public class TokenService {

    private static final String TOKEN_KEY_PREFIX = "login:token:";

    /** token 有效期：2 小时 */
    private static final Duration TOKEN_TTL = Duration.ofHours(2);

    /** 滑动续期阈值：剩余不足 30 分钟时刷新 */
    private static final long RENEW_THRESHOLD_SECONDS = 30 * 60;

    private final RedisTemplate<String, Object> redisTemplate;

    public TokenService(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /** 登录成功后签发 token：以 Map 存入 Redis（避免 Jackson 对 record 序列化的类型信息问题） */
    public String issue(UserContext.LoginUser user) {
        String token = UUID.randomUUID().toString().replace("-", "");
        Map<String, Object> data = new HashMap<>(4);
        data.put("id", user.id());
        data.put("username", user.username());
        data.put("nickname", user.nickname());
        data.put("role", user.role());
        redisTemplate.opsForValue().set(TOKEN_KEY_PREFIX + token, data, TOKEN_TTL);
        return token;
    }

    /** 校验 token：命中返回用户信息，未命中返回 null；剩余时间不足时滑动续期 */
    @SuppressWarnings("unchecked")
    public UserContext.LoginUser verify(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String key = TOKEN_KEY_PREFIX + token;
        Object cached;
        try {
            cached = redisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            // Redis 故障：拒绝放行（登录态校验是安全路径，宁可拒绝不可放行）
            return null;
        }
        if (!(cached instanceof Map<?, ?> raw)) {
            return null;
        }
        Map<String, Object> data = (Map<String, Object>) raw;
        UserContext.LoginUser user = new UserContext.LoginUser(
                Long.valueOf(String.valueOf(data.get("id"))),
                String.valueOf(data.get("username")),
                String.valueOf(data.get("nickname")),
                String.valueOf(data.get("role"))
        );
        renewIfNeeded(key, data);
        return user;
    }

    /** 登出：删除 token，立即失效 */
    public void invalidate(String token) {
        if (token != null && !token.isBlank()) {
            redisTemplate.delete(TOKEN_KEY_PREFIX + token);
        }
    }

    /** 滑动续期：剩余不足 30 分钟时重置为完整 TTL */
    private void renewIfNeeded(String key, Map<String, Object> data) {
        try {
            Long remain = redisTemplate.getExpire(key);
            if (remain != null && remain >= 0 && remain < RENEW_THRESHOLD_SECONDS) {
                redisTemplate.opsForValue().set(key, data, TOKEN_TTL);
            }
        } catch (Exception ignored) {
            // 续期失败不影响本次请求
        }
    }
}
