package lab.inventory.domain.exception;

import java.util.List;

import lab.inventory.domain.CanonicalReservationRequest;

/**
 * SKIP LOCKED 领取到的单位不足。不等于售罄：只表示本次事务必须整体回滚，
 * 由事务外的编排决定补充、重试或判定真实不足。
 */
public class PoolNotReadyException extends RuntimeException {

    private final transient List<CanonicalReservationRequest.Line> lackingLines;

    public PoolNotReadyException(List<CanonicalReservationRequest.Line> lackingLines) {
        super("Unit pool could not satisfy " + lackingLines.size() + " dimension(s)", null, false, false);
        this.lackingLines = List.copyOf(lackingLines);
    }

    public List<CanonicalReservationRequest.Line> lackingLines() {
        return lackingLines;
    }
}
