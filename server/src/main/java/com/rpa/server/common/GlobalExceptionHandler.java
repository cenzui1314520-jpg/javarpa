package com.rpa.server.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.UUID;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<R<Void>> handleApi(ApiException e) {
        return ResponseEntity.status(httpStatusOf(e.getCode())).body(R.fail(e.getCode(), e.getMessage()));
    }

    private static HttpStatus httpStatusOf(int code) {
        return switch (code) {
            case 400, 1 -> HttpStatus.BAD_REQUEST;
            case 401 -> HttpStatus.UNAUTHORIZED;
            case 403 -> HttpStatus.FORBIDDEN;
            case 404 -> HttpStatus.NOT_FOUND;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<R<Void>> handleValid(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .findFirst().orElse("参数错误");
        return ResponseEntity.badRequest().body(R.fail(400, msg));
    }

    @ExceptionHandler({MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class, NumberFormatException.class})
    public ResponseEntity<R<Void>> handleBadRequest(Exception e) {
        return ResponseEntity.badRequest().body(R.fail(400, "请求参数缺失或格式错误"));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<R<Void>> handleUpload(MaxUploadSizeExceededException e) {
        return ResponseEntity.badRequest().body(R.fail(400, "上传文件过大"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<R<Void>> handleOther(Exception e) {
        String errorId = UUID.randomUUID().toString().substring(0, 8);
        log.error("unhandled error [{}]", errorId, e);
        return ResponseEntity.internalServerError()
                .body(R.fail(500, "服务器内部错误，请联系管理员（错误码 " + errorId + "）"));
    }
}
