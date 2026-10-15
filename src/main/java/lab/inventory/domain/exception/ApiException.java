package lab.inventory.domain.exception;

import java.util.LinkedHashMap;
import java.util.Map;

import lab.inventory.domain.InventoryKey;

/** 带稳定错误码的业务异常；在事务回调中抛出会回滚整个事务。 */
public class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final Map<String, Object> details;

    public ApiException(ErrorCode code, String message) {
        this(code, message, Map.of());
    }

    public ApiException(ErrorCode code, String message, Map<String, Object> details) {
        super(message);
        this.code = code;
        this.details = details;
    }

    public ErrorCode code() {
        return code;
    }

    public Map<String, Object> details() {
        return details;
    }

    public static ApiException invalidArgument(String message) {
        return new ApiException(ErrorCode.INVALID_ARGUMENT, message);
    }

    public static ApiException forKey(ErrorCode code, String message, InventoryKey key) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("inventoryItemId", key.inventoryItemId());
        details.put("locationId", key.locationId());
        return new ApiException(code, message, details);
    }

    public static ApiException inventoryNotFound(InventoryKey key) {
        return forKey(ErrorCode.INVENTORY_NOT_FOUND, "Inventory dimension is not initialized.", key);
    }

    public static ApiException reservationNotFound() {
        return new ApiException(ErrorCode.RESERVATION_NOT_FOUND, "Reservation not found.");
    }

    public static ApiException busy(String message) {
        return new ApiException(ErrorCode.INVENTORY_BUSY, message);
    }

    public static ApiException invariantViolation(String message, InventoryKey key) {
        return forKey(ErrorCode.INVENTORY_INVARIANT_VIOLATION, message, key);
    }
}
