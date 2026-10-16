package lab.inventory.migration;

import lab.inventory.domain.exception.ApiException;
import lab.inventory.domain.exception.ErrorCode;

/** 一次操作在某个系统上的结果类别；影子比对只比较类别，不比较 ID、时间等系统各自生成的字段。 */
public record Outcome(Category category, String status) {

    public enum Category {
        CREATED, REPLAYED, OK, INSUFFICIENT, CONFLICT, PAYMENT_CONFLICT, NOT_FOUND, EXPIRED, INVALID_STATE, BUSY,
        INVALID, ERROR,
        /** 只出现在到期清理中：候选在 Redis 时间下尚未到期。 */
        NOT_DUE;

        public boolean succeeded() {
            return this == CREATED || this == REPLAYED || this == OK;
        }
    }

    public static Outcome of(String category, String status) {
        return new Outcome(Category.valueOf(category), status);
    }

    public static Outcome failure(Throwable e) {
        if (!(e instanceof ApiException api)) {
            return new Outcome(Category.ERROR, e.getClass().getSimpleName());
        }
        Category category = switch (api.code()) {
            case INSUFFICIENT_STOCK -> Category.INSUFFICIENT;
            case IDEMPOTENCY_CONFLICT -> Category.CONFLICT;
            case PAYMENT_REFERENCE_CONFLICT -> Category.PAYMENT_CONFLICT;
            case INVENTORY_NOT_FOUND, RESERVATION_NOT_FOUND -> Category.NOT_FOUND;
            case RESERVATION_EXPIRED -> Category.EXPIRED;
            case INVALID_RESERVATION_STATE -> Category.INVALID_STATE;
            case INVENTORY_BUSY, TOO_MANY_REQUESTS, DATABASE_UNAVAILABLE -> Category.BUSY;
            case INVALID_ARGUMENT, QUANTITY_LIMIT_EXCEEDED -> Category.INVALID;
            default -> Category.ERROR;
        };
        return new Outcome(category, api.code().name());
    }

    /** 旧 Redis 系统作为事实来源时，把失败类别映射成与主接口相同的错误码。 */
    public ApiException toException(boolean reservationLevel) {
        return switch (category) {
            case INSUFFICIENT -> new ApiException(ErrorCode.INSUFFICIENT_STOCK, "Not enough reservable stock.");
            case CONFLICT -> new ApiException(ErrorCode.IDEMPOTENCY_CONFLICT,
                    "Idempotency key was already used with a different request.");
            case PAYMENT_CONFLICT -> new ApiException(ErrorCode.PAYMENT_REFERENCE_CONFLICT,
                    "Payment reference conflicts with another claim.");
            case NOT_FOUND -> reservationLevel ? ApiException.reservationNotFound()
                    : new ApiException(ErrorCode.INVENTORY_NOT_FOUND, "Inventory dimension is not initialized.");
            case EXPIRED -> new ApiException(ErrorCode.RESERVATION_EXPIRED, "Reservation has expired.");
            case INVALID_STATE -> new ApiException(ErrorCode.INVALID_RESERVATION_STATE,
                    "Reservation is in a terminal state: " + status + ".");
            case BUSY -> ApiException.busy("Inventory is temporarily contended; retry the request.");
            case INVALID -> ApiException.invalidArgument("Invalid request.");
            default -> new ApiException(ErrorCode.INTERNAL_ERROR, "Unexpected failure in the primary system.");
        };
    }
}
