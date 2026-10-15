package lab.inventory.persistence;

import java.nio.ByteBuffer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.UUID;

/** UUID <-> BINARY(16)、UTC Instant <-> DATETIME(6) 的编解码。 */
public final class Codec {

    private Codec() {
    }

    public static byte[] uuidToBytes(UUID uuid) {
        return ByteBuffer.allocate(16)
                .putLong(uuid.getMostSignificantBits())
                .putLong(uuid.getLeastSignificantBits())
                .array();
    }

    public static UUID uuidFromBytes(byte[] bytes) {
        if (bytes == null || bytes.length != 16) {
            throw new IllegalArgumentException("UUID requires exactly 16 bytes");
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    /** 与 MySQL BINARY 列相同的无符号字节序，用于批量插入前按主键排序。 */
    public static int compareUnsigned(byte[] left, byte[] right) {
        return Arrays.compareUnsigned(left, right);
    }

    /** 会话时区固定为 UTC，DATETIME(6) 按 LocalDateTime 读写，不经过 JVM 默认时区。 */
    public static LocalDateTime toDbTime(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    public static Instant instant(ResultSet rs, String column) throws SQLException {
        LocalDateTime value = rs.getObject(column, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
}
