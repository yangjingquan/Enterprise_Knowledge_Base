package com.kb.config;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * 统一异常处理：把业务中抛出的 ResponseStatusException（401 / 403 / 404 等）
 * 转成 {"error": "原因"} 格式，前端统一用 error 字段弹出提示。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handle(ResponseStatusException e) {
        String reason = e.getReason() == null ? "请求失败" : e.getReason();
        return ResponseEntity.status(e.getStatusCode()).body(Map.of("error", reason));
    }

    /** 上传文件超过 spring.servlet.multipart 的大小上限（50MB） */
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, String>> tooLarge(org.springframework.web.multipart.MaxUploadSizeExceededException e) {
        return ResponseEntity.status(413).body(Map.of("error", "文件超过 50MB 上限，请拆分或删减后再上传"));
    }
}
