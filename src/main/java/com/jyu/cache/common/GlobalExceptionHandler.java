package com.jyu.cache.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理器（v3 接口契约修复）
 *
 * v2 问题：所有错误（含 401/403/404/500）都以 HTTP 200 + body.code 返回，
 *          与拦截器的真 401 不一致，客户端无法统一依赖状态码。
 * v3 修复：HTTP 状态码与业务码对齐——
 *          业务异常按 code 映射真实 HTTP 状态（400/401/403/404/409/429），
 *          未知异常统一 500；响应体仍保持 Result 结构，前端兼容。
 *
 * 约束：仅 code 在合法 HTTP 状态码范围内才透传，防止自定义 code 造成怪异状态码。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 业务异常：消息可直接展示给用户，HTTP 状态码与 code 对齐 */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusiness(BusinessException e) {
        log.warn("[业务异常] code={} message={}", e.getCode(), e.getMessage());
        return ResponseEntity.status(resolveHttpStatus(e.getCode()))
                .body(Result.fail(e.getCode(), e.getMessage()));
    }

    /** 参数校验失败（@Valid @NotBlank 等）：400 + 第一条校验消息 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(f -> f.getDefaultMessage())
                .orElse("参数不合法");
        return ResponseEntity.badRequest().body(Result.fail(400, msg));
    }

    /** 静态资源/路径不存在：404 */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Result<Void>> handleNoResource(NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Result.fail(404, "资源不存在"));
    }

    /** 兜底异常：技术故障，只记日志，前端只收到通用提示 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleUnknown(Exception e) {
        log.error("[系统异常] {}", e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.fail(500, "系统繁忙，请稍后重试"));
    }

    /** 业务码 -> HTTP 状态码：仅放行常用段，其余归入 400 */
    private HttpStatus resolveHttpStatus(Integer code) {
        if (code == null) {
            return HttpStatus.BAD_REQUEST;
        }
        return switch (code) {
            case 400 -> HttpStatus.BAD_REQUEST;
            case 401 -> HttpStatus.UNAUTHORIZED;
            case 403 -> HttpStatus.FORBIDDEN;
            case 404 -> HttpStatus.NOT_FOUND;
            case 409 -> HttpStatus.CONFLICT;
            case 429 -> HttpStatus.TOO_MANY_REQUESTS;
            default -> HttpStatus.BAD_REQUEST;
        };
    }
}
