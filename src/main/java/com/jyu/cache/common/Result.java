package com.jyu.cache.common;

import lombok.Data;

/**
 * 统一响应结果类
 */
@Data
public class Result<T> {

    /** 状态码 */
    private Integer code;

    /** 提示消息 */
    private String message;

    /** 实际数据 */
    private T data;

    private Result(Integer code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    public static <T> Result<T> success(T data) {
        return new Result<>(200, "成功", data);
    }

    public static <T> Result<T> success(String message, T data) {
        return new Result<>(200, message, data);
    }

    public static <T> Result<T> success() {
        return new Result<>(200, "成功", null);
    }

    public static <T> Result<T> fail(String message) {
        return new Result<>(500, message, null);
    }

    public static <T> Result<T> fail(Integer code, String message) {
        return new Result<>(code, message, null);
    }
}
