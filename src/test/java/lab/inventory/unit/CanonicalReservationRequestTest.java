package lab.inventory.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import lab.inventory.domain.CanonicalReservationRequest;
import lab.inventory.domain.CanonicalReservationRequest.RawLine;
import lab.inventory.domain.exception.ApiException;
import lab.inventory.domain.exception.ErrorCode;
import org.junit.jupiter.api.Test;

class CanonicalReservationRequestTest {

    @Test
    void mergesDuplicateDimensionsAndSortsByKey() {
        CanonicalReservationRequest request = CanonicalReservationRequest.of(1001, 300, List.of(
                new RawLine(2002L, 3001L, 2L),
                new RawLine(2001L, 3002L, 1L),
                new RawLine(2001L, 3001L, 3L),
                new RawLine(2002L, 3001L, 4L)));

        assertThat(request.lines()).extracting(l -> l.key().inventoryItemId(), l -> l.key().locationId(),
                CanonicalReservationRequest.Line::quantity)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(2001L, 3001L, 3),
                        org.assertj.core.groups.Tuple.tuple(2001L, 3002L, 1),
                        org.assertj.core.groups.Tuple.tuple(2002L, 3001L, 6));
        assertThat(request.totalQuantity()).isEqualTo(10);
    }

    @Test
    void hashIgnoresLineOrderSplitLinesAndImplicitDefaultTtl() {
        byte[] reference = CanonicalReservationRequest.of(1001, 300, List.of(
                new RawLine(2001L, 3001L, 3L), new RawLine(2002L, 3001L, 2L))).sha256();

        byte[] reordered = CanonicalReservationRequest.of(1001, 300, List.of(
                new RawLine(2002L, 3001L, 2L), new RawLine(2001L, 3001L, 3L))).sha256();
        byte[] split = CanonicalReservationRequest.of(1001, 300, List.of(
                new RawLine(2001L, 3001L, 1L), new RawLine(2002L, 3001L, 2L), new RawLine(2001L, 3001L, 2L)))
                .sha256();
        byte[] defaultTtl = CanonicalReservationRequest.of(1001, null, List.of(
                new RawLine(2001L, 3001L, 3L), new RawLine(2002L, 3001L, 2L))).sha256();

        assertThat(reference).hasSize(32).isEqualTo(reordered).isEqualTo(split).isEqualTo(defaultTtl);
    }

    @Test
    void hashDiffersWhenShopTtlOrQuantityDiffers() {
        List<RawLine> lines = List.of(new RawLine(2001L, 3001L, 3L));
        byte[] reference = CanonicalReservationRequest.of(1001, 300, lines).sha256();

        assertThat(CanonicalReservationRequest.of(1002, 300, lines).sha256()).isNotEqualTo(reference);
        assertThat(CanonicalReservationRequest.of(1001, 301, lines).sha256()).isNotEqualTo(reference);
        assertThat(CanonicalReservationRequest.of(1001, 300, List.of(new RawLine(2001L, 3001L, 4L))).sha256())
                .isNotEqualTo(reference);
    }

    @Test
    void splitLinesCannotBypassTheTotalLimit() {
        assertCode(ErrorCode.QUANTITY_LIMIT_EXCEEDED, () -> CanonicalReservationRequest.of(1001, null, List.of(
                new RawLine(2001L, 3001L, 600L), new RawLine(2001L, 3001L, 401L))));
        assertCode(ErrorCode.QUANTITY_LIMIT_EXCEEDED, () -> CanonicalReservationRequest.of(1001, null, List.of(
                new RawLine(2001L, 3001L, 600L), new RawLine(2002L, 3001L, 401L))));
        assertThat(CanonicalReservationRequest.of(1001, null, List.of(
                new RawLine(2001L, 3001L, 600L), new RawLine(2002L, 3001L, 400L))).totalQuantity()).isEqualTo(1000);
    }

    @Test
    void mergeOverflowIsRejectedInsteadOfWrapping() {
        assertCode(ErrorCode.QUANTITY_LIMIT_EXCEEDED, () -> CanonicalReservationRequest.of(1001, null, List.of(
                new RawLine(2001L, 3001L, Long.MAX_VALUE), new RawLine(2001L, 3001L, 1L))));
    }

    @Test
    void rejectsTooManyLinesAndDimensions() {
        List<RawLine> manyLines = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            manyLines.add(new RawLine(2001L, 3001L, 1L));
        }
        assertCode(ErrorCode.QUANTITY_LIMIT_EXCEEDED, () -> CanonicalReservationRequest.of(1001, null, manyLines));

        List<RawLine> manyDimensions = new ArrayList<>();
        for (long i = 1; i <= 21; i++) {
            manyDimensions.add(new RawLine(i, 3001L, 1L));
        }
        assertCode(ErrorCode.QUANTITY_LIMIT_EXCEEDED,
                () -> CanonicalReservationRequest.of(1001, null, manyDimensions));
    }

    @Test
    void rejectsInvalidArguments() {
        List<RawLine> ok = List.of(new RawLine(2001L, 3001L, 1L));
        assertCode(ErrorCode.INVALID_ARGUMENT, () -> CanonicalReservationRequest.of(1001, 4, ok));
        assertCode(ErrorCode.INVALID_ARGUMENT, () -> CanonicalReservationRequest.of(1001, 901, ok));
        assertCode(ErrorCode.INVALID_ARGUMENT, () -> CanonicalReservationRequest.of(0, null, ok));
        assertCode(ErrorCode.INVALID_ARGUMENT, () -> CanonicalReservationRequest.of(1001, null, List.of()));
        assertCode(ErrorCode.INVALID_ARGUMENT, () -> CanonicalReservationRequest.of(1001, null, null));
        assertCode(ErrorCode.INVALID_ARGUMENT,
                () -> CanonicalReservationRequest.of(1001, null, List.of(new RawLine(2001L, 3001L, 0L))));
        assertCode(ErrorCode.INVALID_ARGUMENT,
                () -> CanonicalReservationRequest.of(1001, null, List.of(new RawLine(2001L, null, 1L))));
        assertCode(ErrorCode.INVALID_ARGUMENT,
                () -> CanonicalReservationRequest.of(1001, null, List.of(new RawLine(-1L, 3001L, 1L))));
    }

    private static void assertCode(ErrorCode expected, Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(expected));
    }
}
