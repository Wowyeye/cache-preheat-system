package com.jyu.cache.common;

/**
 * 业务异常类
 *
 * 用于在 Service 层表达"业务规则不满足"（如库存不足、状态机非法流转），
 * 由全局异常处理器统一捕获并转换为 Result 响应。
 *
 * v3：code 同时作为 HTTP 响应状态码返回（接口契约自洽：客户端可以统一依赖
 * res.ok / res.status 判断，不必再解析 body.code）。
 */
public class BusinessException extends RuntimeException {

    /** HTTP 风格状态码：400=请求参数错，401=未登录，403=无权限，404=资源不存在，409=状态冲突，429=请求过频 */
    private final Integer code;

    public BusinessException(Integer code, String message) {
        super(message);
        this.code = code;
    }

    public BusinessException(String message) {
        this(400, message);
    }

    public Integer getCode() {
        return code;
    }
}
