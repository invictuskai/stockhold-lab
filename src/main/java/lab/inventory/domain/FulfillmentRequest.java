package lab.inventory.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import lab.inventory.domain.CanonicalReservationRequest.RawLine;
import lab.inventory.domain.exception.ApiException;

/**
 * 客户端的预留意图（文章：“只从能履约的地点预留”）。一行可以指定固定地点，也可以给出按优先级排列的候选地点；
 * 候选行整行从第一个能满足它的地点预留，不在多个地点之间拆分。
 *
 * <p>只有固定地点时与原有请求完全相同（同一个 hash）。含候选时，hash 覆盖候选列表与顺序：
 * 重放同一意图得到同一个预留；换了候选或顺序则是幂等冲突。
 */
public final class FulfillmentRequest {

    public static final int MAX_CANDIDATES = 5;

    /** 合并后的候选行：同一商品、同一候选列表的输入行数量相加。 */
    public record FlexibleLine(long inventoryItemId, List<Long> candidateLocationIds, long quantity) {
    }

    private final long shopId;
    private final Integer ttlSeconds;
    private final List<RawLine> fixedLines;
    private final List<FlexibleLine> flexibleLines;
    private final byte[] intentHash;

    private FulfillmentRequest(long shopId, Integer ttlSeconds, List<RawLine> fixedLines,
            List<FlexibleLine> flexibleLines, byte[] intentHash) {
        this.shopId = shopId;
        this.ttlSeconds = ttlSeconds;
        this.fixedLines = fixedLines;
        this.flexibleLines = flexibleLines;
        this.intentHash = intentHash;
    }

    public static FulfillmentRequest of(long shopId, Integer ttlSeconds, List<RawLine> rawLines) {
        if (rawLines == null || rawLines.isEmpty()) {
            throw ApiException.invalidArgument("lines must contain at least one entry.");
        }
        List<RawLine> fixed = new ArrayList<>();
        Map<String, FlexibleLine> flexible = new LinkedHashMap<>();
        for (RawLine raw : rawLines) {
            if (raw == null || !raw.flexible()) {
                fixed.add(raw);
                continue;
            }
            if (raw.locationId() != null) {
                throw ApiException.invalidArgument("A line must use either locationId or locationIds, not both.");
            }
            if (raw.inventoryItemId() == null || raw.quantity() == null) {
                throw ApiException.invalidArgument("Each line requires inventoryItemId and quantity.");
            }
            if (raw.quantity() <= 0) {
                throw ApiException.invalidArgument("quantity must be a positive integer.");
            }
            List<Long> candidates = new ArrayList<>(new LinkedHashSet<>(raw.locationIds()));
            if (candidates.isEmpty() || candidates.size() > MAX_CANDIDATES
                    || candidates.stream().anyMatch(id -> id == null || id <= 0)) {
                throw ApiException.invalidArgument("locationIds must hold 1.." + MAX_CANDIDATES
                        + " distinct positive location IDs in priority order.");
            }
            String mergeKey = raw.inventoryItemId() + ":" + candidates;
            FlexibleLine previous = flexible.get(mergeKey);
            long quantity;
            try {
                quantity = previous == null ? raw.quantity() : Math.addExact(previous.quantity(), raw.quantity());
            } catch (ArithmeticException overflow) {
                throw new ApiException(lab.inventory.domain.exception.ErrorCode.QUANTITY_LIMIT_EXCEEDED,
                        "Merged quantity overflows.");
            }
            flexible.put(mergeKey, new FlexibleLine(raw.inventoryItemId(), List.copyOf(candidates), quantity));
        }
        List<FlexibleLine> flexibleLines = flexible.values().stream()
                .sorted(Comparator.comparingLong(FlexibleLine::inventoryItemId)
                        .thenComparing(l -> l.candidateLocationIds().toString()))
                .toList();
        FulfillmentRequest intent = new FulfillmentRequest(shopId, ttlSeconds, List.copyOf(fixed), flexibleLines,
                null);
        // 用首选候选解析一次，得到与固定请求相同的参数校验（数量上限、维度数、TTL 等）。
        CanonicalReservationRequest firstChoice = intent.resolveUnhashed(new int[flexibleLines.size()]);
        if (flexibleLines.isEmpty()) {
            return new FulfillmentRequest(shopId, ttlSeconds, intent.fixedLines, flexibleLines, null);
        }
        return new FulfillmentRequest(shopId, ttlSeconds, intent.fixedLines, flexibleLines,
                intent.canonicalHash(firstChoice));
    }

    public boolean flexible() {
        return !flexibleLines.isEmpty();
    }

    public List<FlexibleLine> flexibleLines() {
        return flexibleLines;
    }

    /** choice[i] 是第 i 个候选行选中的候选下标；返回可直接预留的具体请求。 */
    public CanonicalReservationRequest resolve(int[] choice) {
        CanonicalReservationRequest concrete = resolveUnhashed(choice);
        return intentHash == null ? concrete : concrete.withIntentHash(intentHash);
    }

    public InventoryKey chosenKey(int line, int[] choice) {
        FlexibleLine flexibleLine = flexibleLines.get(line);
        return new InventoryKey(shopId, flexibleLine.inventoryItemId(),
                flexibleLine.candidateLocationIds().get(choice[line]));
    }

    private CanonicalReservationRequest resolveUnhashed(int[] choice) {
        List<RawLine> concrete = new ArrayList<>(fixedLines);
        for (int i = 0; i < flexibleLines.size(); i++) {
            FlexibleLine line = flexibleLines.get(i);
            concrete.add(new RawLine(line.inventoryItemId(), line.candidateLocationIds().get(choice[i]),
                    line.quantity()));
        }
        return CanonicalReservationRequest.of(shopId, ttlSeconds, concrete);
    }

    /** 固定部分沿用已规范化的明细，候选部分按商品与候选列表排序；前缀区分于只有固定地点的 v1 请求。 */
    private byte[] canonicalHash(CanonicalReservationRequest firstChoice) {
        StringBuilder canonical = new StringBuilder("reserve:v2|shop=").append(shopId)
                .append("|ttl=").append(firstChoice.ttlSeconds()).append("|fixed=");
        CanonicalReservationRequest fixedOnly = fixedLines.isEmpty() ? null
                : CanonicalReservationRequest.of(shopId, ttlSeconds, fixedLines);
        if (fixedOnly != null) {
            canonical.append(fixedOnly.lines().stream()
                    .map(l -> l.key().inventoryItemId() + ":" + l.key().locationId() + ":" + l.quantity())
                    .collect(Collectors.joining(",")));
        }
        canonical.append("|flexible=");
        canonical.append(flexibleLines.stream()
                .map(l -> l.inventoryItemId() + ":" + l.candidateLocationIds() + ":" + l.quantity())
                .collect(Collectors.joining(",")));
        return CanonicalReservationRequest.Hashing.sha256(canonical.toString());
    }
}
