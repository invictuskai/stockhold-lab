package lab.inventory.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

import lab.inventory.domain.InventoryKey;
import lab.inventory.observability.BusinessOperation;
import lab.inventory.observability.BusinessOperationContext;
import lab.inventory.persistence.Codec;
import lab.inventory.persistence.SqlErrors;
import org.junit.jupiter.api.Test;

class CodecAndContextTest {

    @Test
    void uuidRoundTripsThroughSixteenBytes() {
        UUID uuid = UUID.fromString("550e8400-e29b-41d4-a716-446655440010");
        byte[] bytes = Codec.uuidToBytes(uuid);

        assertThat(bytes).hasSize(16);
        assertThat(bytes[0]).isEqualTo((byte) 0x55);
        assertThat(Codec.uuidFromBytes(bytes)).isEqualTo(uuid);
        assertThatThrownBy(() -> Codec.uuidFromBytes(new byte[15])).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void byteOrderIsUnsignedLikeMySqlBinary() {
        byte[] low = Codec.uuidToBytes(UUID.fromString("7fffffff-0000-0000-0000-000000000000"));
        byte[] high = Codec.uuidToBytes(UUID.fromString("80000000-0000-0000-0000-000000000000"));

        assertThat(Codec.compareUnsigned(low, high)).isNegative();
    }

    @Test
    void instantsAreStoredAsUtcWithMicroseconds() {
        Instant instant = Instant.parse("2026-09-23T02:30:00.123456Z");

        assertThat(Codec.toDbTime(instant)).isEqualTo(LocalDateTime.of(2026, 9, 23, 2, 30, 0, 123_456_000));
        assertThat(Codec.toDbTime(null)).isNull();
    }

    @Test
    void inventoryKeysOrderByShopThenItemThenLocation() {
        assertThat(new InventoryKey(1, 2, 3)).isLessThan(new InventoryKey(1, 2, 4));
        assertThat(new InventoryKey(1, 9, 9)).isLessThan(new InventoryKey(2, 1, 1));
        assertThat(new InventoryKey(1, 2, 9)).isLessThan(new InventoryKey(1, 3, 1));
    }

    @Test
    void operationContextRestoresPreviousTagEvenAfterFailure() {
        assertThat(BusinessOperationContext.current()).isEqualTo(BusinessOperation.UNTAGGED);
        try (BusinessOperationContext.Scope outer = BusinessOperationContext.open(BusinessOperation.RESERVE)) {
            assertThat(BusinessOperationContext.tag("SELECT 1")).isEqualTo("/* conn_tag:reserve */ SELECT 1");
            try (BusinessOperationContext.Scope inner = BusinessOperationContext.open(BusinessOperation.REFILL)) {
                assertThat(BusinessOperationContext.current()).isEqualTo(BusinessOperation.REFILL);
                throw new IllegalStateException("boom");
            } catch (IllegalStateException expected) {
                assertThat(BusinessOperationContext.current()).isEqualTo(BusinessOperation.RESERVE);
            }
        }
        assertThat(BusinessOperationContext.current()).isEqualTo(BusinessOperation.UNTAGGED);
    }

    @Test
    void sqlErrorsClassifyByMySqlErrorCode() {
        RuntimeException deadlock = new RuntimeException(new SQLException("Deadlock found", "40001", 1213));
        RuntimeException lockWait = new RuntimeException(new SQLException("Lock wait timeout", "HY000", 1205));
        RuntimeException duplicate = new RuntimeException(new SQLException(
                "Duplicate entry '1-x' for key 'reservations.uq_reservation_payment'", "23000", 1062));

        assertThat(SqlErrors.isTransientLockFailure(deadlock)).isTrue();
        assertThat(SqlErrors.isTransientLockFailure(lockWait)).isTrue();
        assertThat(SqlErrors.isTransientLockFailure(duplicate)).isFalse();
        assertThat(SqlErrors.isDuplicateKey(duplicate, "uq_reservation_payment")).isTrue();
        assertThat(SqlErrors.isDuplicateKey(duplicate, "uq_reservation_idempotency")).isFalse();
        assertThat(SqlErrors.isDuplicateKey(new RuntimeException("no sql"))).isFalse();
    }
}
