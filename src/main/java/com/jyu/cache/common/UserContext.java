package com.jyu.cache.common;

/**
 * 当前登录用户上下文
 *
 * 拦截器完成 token 校验后，把用户信息放入 ThreadLocal；
 * Controller / Service 通过 getUser() 获取"我是谁"，避免层层传参。
 *
 * 命名约定：ThreadLocal 变量必须成对调用 set/remove，防止线程池复用导致串号。
 */
public class UserContext {

    /** 当前登录用户（拦截器写入，请求结束移除） */
    private static final ThreadLocal<LoginUser> CURRENT = new ThreadLocal<>();

    public static void set(LoginUser user) {
        CURRENT.set(user);
    }

    public static LoginUser getUser() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }

    /**
     * 【v3 修正】管理员校验的统一样子：未登录 401、已登录但非管理员 403
     *
     * 早期实现把"未登录"和"非管理员"一律判成 403，客户端无法区分
     * "该去登录"还是"该换账号"，与拦截器在写请求上返回的 401 也不一致。
     */
    public static void requireAdmin() {
        LoginUser user = getUser();
        if (user == null) {
            throw new BusinessException(401, "未登录或登录已过期，请重新登录");
        }
        if (!user.isAdmin()) {
            throw new BusinessException(403, "需要管理员权限");
        }
    }

    /** 登录用户信息（从 token 还原，不含密码等敏感字段） */
    public record LoginUser(Long id, String username, String nickname, String role) {
        public boolean isAdmin() {
            return "ADMIN".equals(role);
        }
    }
}
