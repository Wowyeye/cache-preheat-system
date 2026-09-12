package com.jyu.cache.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
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
 *
 * 【v3 修正】客户端错误不再一律 500：
 *   早期实现缺少 405 / 400 的专门处理，POST 打到只支持 GET 的接口、
 *   ?page=abc 这类参数类型错误都会掉进兜底 Exception -> 500，
 *   既误导调用方，也会把真正的服务端故障淹没在 ERROR 日志里。
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

    /** 请求方法不支持（如 POST 打到只读接口）：405 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Result<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        log.warn("[方法不支持] method={} message={}", e.getMethod(), e.getMessage());
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(Result.fail(405, "请求方法不被支持：" + e.getMethod()));
    }

    /** 请求参数类型/格式错误（如 page=abc）：400 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Result<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        log.warn("[参数类型错误] name={} value={}", e.getName(), e.getValue());
        return ResponseEntity.badRequest()
                .body(Result.fail(400, "参数 " + e.getName() + " 格式不正确"));
    }

    /** 缺少必填请求参数：400 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Result<Void>> handleMissingParam(MissingServletRequestParameterException e) {
        log.warn("[缺少参数] name={}", e.getParameterName());
        return ResponseEntity.badRequest()
                .body(Result.fail(400, "缺少必填参数：" + e.getParameterName()));
    }

    /** 请求体无法解析（JSON 语法错误/类型不匹配）：400 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<Void>> handleUnreadableBody(HttpMessageNotReadableException e) {
        log.warn("[请求体不可读] {}", e.getMessage());
        return ResponseEntity.badRequest().body(Result.fail(400, "请求体格式不正确"));
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
            case 405 -> HttpStatus.METHOD_NOT_ALLOWED;
            case 409 -> HttpStatus.CONFLICT;
            case 429 -> HttpStatus.TOO_MANY_REQUESTS;
            case 503 -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.BAD_REQUEST;
        };
    }
}
