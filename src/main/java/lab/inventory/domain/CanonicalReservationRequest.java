package lab.inventory.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import lab.inventory.domain.exception.ApiException;
import lab.inventory.domain.exception.ErrorCode;

/**
 * 规范化后的预留请求：重复维度已合并、按 InventoryKey 升序、TTL 已显式默认化。
 * 幂等 hash 只基于这里的内容，不基于原始 JSON。
 */
public record CanonicalReservationRequest(long shopId, int ttlSeconds, List<Line> lines, int totalQuantity) {

    public static final int MAX_INPUT_LINES = 100;
    public static final int MAX_DIMENSIONS = 20;
    public static final int MAX_TOTAL_QUANTITY = 1000;
    public static final int DEFAULT_TTL_SECONDS = 300;
    public static final int MIN_TTL_SECONDS = 5;
    public static final int MAX_TTL_SECONDS = 900;

    public record Line(InventoryKey key, int quantity) {
    }

    /** 未经合并的输入行；数量用 Long 接收以便精确检查溢出。 */
    public record RawLine(Long inventoryItemId, Long locationId, Long quantity) {
    }

    public static CanonicalReservationRequest of(long shopId, Integer ttlSeconds, List<RawLine> rawLines) {
        if (shopId <= 0) {
            throw ApiException.invalidArgument("shopId must be a positive integer.");
        }
        int ttl = ttlSeconds == null ? DEFAULT_TTL_SECONDS : ttlSeconds;
        if (ttl < MIN_TTL_SECONDS || ttl > MAX_TTL_SECONDS) {
            throw ApiException.invalidArgument(
                    "ttlSeconds must be between " + MIN_TTL_SECONDS + " and " + MAX_TTL_SECONDS + ".");
        }
        if (rawLines == null || rawLines.isEmpty()) {
            throw ApiException.invalidArgument("lines must contain at least one entry.");
        }
        if (rawLines.size() > MAX_INPUT_LINES) {
            throw new ApiException(ErrorCode.QUANTITY_LIMIT_EXCEEDED,
                    "A request may contain at most " + MAX_INPUT_LINES + " input lines.");
        }

        Map<InventoryKey, Long> merged = new TreeMap<>();
        for (RawLine raw : rawLines) {
            if (raw == null || raw.inventoryItemId() == null || raw.locationId() == null || raw.quantity() == null) {
                throw ApiException.invalidArgument("Each line requires inventoryItemId, locationId and quantity.");
            }
            if (raw.quantity() <= 0) {
                throw ApiException.invalidArgument("quantity must be a positive integer.");
            }
            InventoryKey key = new InventoryKey(shopId, raw.inventoryItemId(), raw.locationId());
            try {
                merged.merge(key, raw.quantity(), Math::addExact);
            } catch (ArithmeticException overflow) {
                throw new ApiException(ErrorCode.QUANTITY_LIMIT_EXCEEDED, "Merged quantity overflows.");
            }
        }
        if (merged.size() > MAX_DIMENSIONS) {
            throw new ApiException(ErrorCode.QUANTITY_LIMIT_EXCEEDED,
                    "A reservation may span at most " + MAX_DIMENSIONS + " item/location dimensions.");
        }

        long total = 0;
        List<Line> lines = new ArrayList<>(merged.size());
        for (Map.Entry<InventoryKey, Long> entry : merged.entrySet()) {
            long quantity = entry.getValue();
            // 逐项与上限比较，total 最多为 20 * 1000 之前就会被拦截，不会溢出。
            if (quantity > MAX_TOTAL_QUANTITY || (total += quantity) > MAX_TOTAL_QUANTITY) {
                throw ApiException.forKey(ErrorCode.QUANTITY_LIMIT_EXCEEDED,
                        "A reservation may hold at most " + MAX_TOTAL_QUANTITY + " units in total.", entry.getKey());
            }
            lines.add(new Line(entry.getKey(), (int) quantity));
        }
        return new CanonicalReservationRequest(shopId, ttl, List.copyOf(lines), (int) total);
    }

    public byte[] sha256() {
        StringBuilder canonical = new StringBuilder("reserve:v1|shop=").append(shopId)
                .append("|ttl=").append(ttlSeconds);
        for (Line line : lines) {
            canonical.append('|').append(line.key().inventoryItemId())
                    .append(':').append(line.key().locationId())
                    .append(':').append(line.quantity());
        }
        return Hashing.sha256(canonical.toString());
    }

    /** SHA-256 工具；管理操作的规范化 hash 也复用。 */
    public static final class Hashing {
        private Hashing() {
        }

        public static byte[] sha256(String canonical) {
            try {
                return MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 is required", e);
            }
        }
    }
}
