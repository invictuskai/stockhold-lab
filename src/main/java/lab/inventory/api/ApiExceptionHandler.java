package lab.inventory.api;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import lab.inventory.api.dto.Dtos.ErrorResponse;
import lab.inventory.domain.exception.ApiException;
import lab.inventory.domain.exception.ErrorCode;
import lab.inventory.persistence.SqlErrors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** 统一错误格式。响应不泄漏 SQL、凭据或栈追踪；细节只进受控日志。 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ErrorResponse> handleApi(ApiException e, HttpServletRequest request) {
        if (e.code().httpStatus() >= 500 && e.code().httpStatus() != 503) {
            log.error("Request failed with {}", e.code(), e);
        }
        return respond(e.code(), e.code().httpStatus(), e.getMessage(), e.details(), request);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ErrorResponse> handleUnreadable(Exception e, HttpServletRequest request) {
        return respond(ErrorCode.INVALID_ARGUMENT, 400, "Request is malformed or has an invalid field type.",
                Map.of(), request);
    }

    @ExceptionHandler({CannotGetJdbcConnectionException.class, CannotCreateTransactionException.class,
            DataAccessResourceFailureException.class, QueryTimeoutException.class,
            TransactionTimedOutException.class})
    ResponseEntity<ErrorResponse> handleDatabaseUnavailable(Exception e, HttpServletRequest request) {
        // 数据库断开、连接池耗尽或超时：503，绝不映射成库存不足。
        log.warn("Database unavailable: {}", e.toString());
        return respond(ErrorCode.DATABASE_UNAVAILABLE, 503,
                "Database is temporarily unavailable; retry with the same idempotency key.", Map.of(), request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> handleOther(Exception e, HttpServletRequest request) {
        if (e instanceof org.springframework.web.ErrorResponse standard) {
            // Spring MVC 自身的 4xx（缺少参数、路径不存在、方法不支持等）保留其状态码。
            int status = standard.getStatusCode().value();
            if (status < 500) {
                return respond(ErrorCode.INVALID_ARGUMENT, status, "Request is not valid for this endpoint.",
                        Map.of(), request);
            }
        }
        if (SqlErrors.isTransientLockFailure(e)) {
            return respond(ErrorCode.INVENTORY_BUSY, 503,
                    "Inventory is temporarily contended; retry with the same idempotency key.", Map.of(), request);
        }
        log.error("Unhandled error", e);
        return respond(ErrorCode.INTERNAL_ERROR, 500, "Internal error; query the resource state before retrying.",
                Map.of(), request);
    }

    private static ResponseEntity<ErrorResponse> respond(ErrorCode code, int status, String message,
            Map<String, Object> details, HttpServletRequest request) {
        Object requestId = request.getAttribute(WebConfig.REQUEST_ID_ATTRIBUTE);
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status);
        if (status == 503) {
            builder.header(HttpHeaders.RETRY_AFTER, "1");
        }
        return builder.body(new ErrorResponse(code.name(), message, code.retryable(),
                requestId == null ? null : requestId.toString(), details));
    }
}
