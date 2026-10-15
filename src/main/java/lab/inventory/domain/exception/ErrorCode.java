package lab.inventory.domain.exception;

/** 接口契约第 12 节的错误码；客户端以 code 为准，不解析 message。 */
public enum ErrorCode {
    INVALID_ARGUMENT(400, false),
    ADMIN_UNAUTHORIZED(401, false),
    FORBIDDEN(403, false),
    INVENTORY_NOT_FOUND(404, false),
    RESERVATION_NOT_FOUND(404, false),
    INVENTORY_ALREADY_EXISTS(409, false),
    IDEMPOTENCY_CONFLICT(409, false),
    PAYMENT_REFERENCE_CONFLICT(409, false),
    INVALID_RESERVATION_STATE(409, false),
    RESERVATION_EXPIRED(409, false),
    STOCK_ALLOCATED(409, false),
    INSUFFICIENT_STOCK(409, false),
    QUANTITY_LIMIT_EXCEEDED(422, false),
    TOO_MANY_REQUESTS(429, true),
    INVENTORY_BUSY(503, true),
    DATABASE_UNAVAILABLE(503, true),
    INVENTORY_INVARIANT_VIOLATION(500, false),
    INTERNAL_ERROR(500, false);

    private final int httpStatus;
    private final boolean retryable;

    ErrorCode(int httpStatus, boolean retryable) {
        this.httpStatus = httpStatus;
        this.retryable = retryable;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public boolean retryable() {
        return retryable;
    }
}
