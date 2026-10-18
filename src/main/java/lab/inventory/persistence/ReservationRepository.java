package lab.inventory.persistence;

import static lab.inventory.observability.BusinessOperationContext.tag;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import lab.inventory.domain.CanonicalReservationRequest;
import lab.inventory.domain.InventoryKey;
import lab.inventory.domain.Reservation;
import lab.inventory.domain.ReservationStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 预留头与明细。表名可替换，单计数器对照组用相同的幂等与状态机 SQL 访问自己的独立表。
 */
@Repository
public class ReservationRepository {

    public static final String IDEMPOTENCY_INDEX = "uq_reservation_idempotency";
    public static final String PAYMENT_INDEX = "uq_reservation_payment";

    private static final String HEADER_COLUMNS = "shop_id, reservation_id, idempotency_key, request_hash, status,"
            + " ttl_seconds, total_quantity, line_count, payment_reference,"
            + " expires_at, claimed_at, cancelled_at, expired_at, created_at";

    public record ExpiryCandidate(long shopId, UUID reservationId, Instant expiresAt) {
    }

    public record KeyedLine(InventoryKey key, int quantity) {
    }

    private final JdbcTemplate jdbc;
    private final String headers;
    private final String lines;

    @Autowired
    public ReservationRepository(JdbcTemplate jdbc) {
        this(jdbc, "reservations", "reserved_quantities");
    }

    public ReservationRepository(JdbcTemplate jdbc, String headerTable, String lineTable) {
        this.jdbc = jdbc;
        this.headers = headerTable;
        this.lines = lineTable;
    }

    /**
     * 数据库 UTC 微秒时间。claim / expiry 必须在获得头锁之后单独调用，
     * 不能在可能长时间等待的锁定 SELECT 里顺便取 NOW。
     */
    public Instant databaseNow() {
        LocalDateTime now = jdbc.queryForObject(tag("SELECT UTC_TIMESTAMP(6)"), LocalDateTime.class);
        if (now == null) {
            throw new IllegalStateException("UTC_TIMESTAMP(6) returned NULL");
        }
        return now.toInstant(ZoneOffset.UTC);
    }

    public void insertHeader(UUID reservationId, String idempotencyKey, CanonicalReservationRequest request,
            Instant createdAt, Instant expiresAt) {
        jdbc.update(tag("INSERT INTO " + headers
                + " (shop_id, reservation_id, idempotency_key, request_hash, status, ttl_seconds,"
                + "  total_quantity, line_count, expires_at, created_at, updated_at)"
                + " VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?, ?, ?, ?, ?)"),
                request.shopId(), Codec.uuidToBytes(reservationId), idempotencyKey, request.sha256(),
                request.ttlSeconds(), request.totalQuantity(), request.lines().size(),
                Codec.toDbTime(expiresAt), Codec.toDbTime(createdAt), Codec.toDbTime(createdAt));
    }

    public void insertLine(long shopId, UUID reservationId, int lineNo, CanonicalReservationRequest.Line line) {
        jdbc.update(tag("INSERT INTO " + lines
                + " (shop_id, reservation_id, line_no, inventory_item_id, location_id, quantity)"
                + " VALUES (?, ?, ?, ?, ?, ?)"),
                shopId, Codec.uuidToBytes(reservationId), lineNo,
                line.key().inventoryItemId(), line.key().locationId(), line.quantity());
    }

    /** 头行锁是 claim / cancel / expiry 之间的仲裁锁。 */
    public Optional<Reservation.Header> lockHeader(long shopId, UUID reservationId) {
        return queryHeader(" WHERE shop_id = ? AND reservation_id = ? FOR UPDATE",
                shopId, Codec.uuidToBytes(reservationId));
    }

    /**
     * 领取一个到期的 ACTIVE 预留并锁住它的头：别的事务正持有的头（另一个到期工作线程 / 实例，或正在确认、取消）
     * 直接跳过而不等待。只锁一个头，锁序与确认 / 取消相同（头 → 账本）。
     */
    public Optional<Reservation.Header> lockNextDue() {
        return lockDueBatch(1).stream().findFirst();
    }

    /**
     * 一次领取最多 limit 个到期 ACTIVE 预留并锁住它们的头；正被别的事务持有的头直接跳过。
     * 因为跳过而不等待，持有多个头不会参与任何等待环，见总体技术方案 7.8。
     */
    public List<Reservation.Header> lockDueBatch(int limit) {
        return jdbc.query(tag("SELECT " + HEADER_COLUMNS + " FROM " + headers
                + " WHERE status = 'ACTIVE' AND expires_at <= UTC_TIMESTAMP(6)"
                + " ORDER BY expires_at LIMIT ? FOR UPDATE SKIP LOCKED"), ReservationRepository::mapHeader, limit);
    }

    /** 一组预留的全部明细（店铺、维度、数量），供批量释放按维度汇总。 */
    public List<KeyedLine> findLinesOf(List<Reservation.Header> reservations) {
        if (reservations.isEmpty()) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder("SELECT shop_id, inventory_item_id, location_id, quantity FROM ")
                .append(lines).append(" WHERE (shop_id, reservation_id) IN (");
        List<Object> args = new ArrayList<>(reservations.size() * 2);
        for (int i = 0; i < reservations.size(); i++) {
            sql.append(i == 0 ? "(?, ?)" : ", (?, ?)");
            args.add(reservations.get(i).shopId());
            args.add(Codec.uuidToBytes(reservations.get(i).reservationId()));
        }
        sql.append(')');
        return jdbc.query(tag(sql.toString()), (rs, i) -> new KeyedLine(
                new InventoryKey(rs.getLong(1), rs.getLong(2), rs.getLong(3)), rs.getInt(4)), args.toArray());
    }

    /** 最早一个已到期仍为 ACTIVE 的到期时间；没有积压时为空。只读，走 idx_reservation_expiry。 */
    public Optional<Instant> oldestOverdue() {
        return Optional.ofNullable(jdbc.query(tag("SELECT MIN(expires_at) AS expires_at FROM " + headers
                + " WHERE status = 'ACTIVE' AND expires_at <= UTC_TIMESTAMP(6)"),
                rs -> rs.next() && rs.getObject(1) != null ? Codec.instant(rs, "expires_at") : null));
    }

    /** 已到期仍为 ACTIVE 的数量，最多数到 cap。 */
    public long overdueCount(int cap) {
        Long count = jdbc.queryForObject(tag("SELECT COUNT(*) FROM (SELECT 1 FROM " + headers
                + " WHERE status = 'ACTIVE' AND expires_at <= UTC_TIMESTAMP(6) LIMIT ?) due"), Long.class, cap);
        return count == null ? 0 : count;
    }

    public Optional<Reservation.Header> findHeader(long shopId, UUID reservationId) {
        return queryHeader(" WHERE shop_id = ? AND reservation_id = ?", shopId, Codec.uuidToBytes(reservationId));
    }

    public Optional<Reservation.Header> findHeaderByIdempotencyKey(long shopId, String idempotencyKey) {
        return queryHeader(" WHERE shop_id = ? AND idempotency_key = ?", shopId, idempotencyKey);
    }

    private Optional<Reservation.Header> queryHeader(String where, Object... args) {
        List<Reservation.Header> rows = jdbc.query(
                tag("SELECT " + HEADER_COLUMNS + " FROM " + headers + where), ReservationRepository::mapHeader, args);
        return rows.stream().findFirst();
    }

    public List<Reservation.Line> findLines(long shopId, UUID reservationId) {
        return jdbc.query(tag("SELECT line_no, inventory_item_id, location_id, quantity FROM " + lines
                + " WHERE shop_id = ? AND reservation_id = ? ORDER BY line_no"),
                (rs, i) -> new Reservation.Line(rs.getInt(1), rs.getLong(2), rs.getLong(3), rs.getInt(4)),
                shopId, Codec.uuidToBytes(reservationId));
    }

    public Optional<Reservation> find(long shopId, UUID reservationId) {
        return findHeader(shopId, reservationId).map(this::withLines);
    }

    public Optional<Reservation> findByIdempotencyKey(long shopId, String idempotencyKey) {
        return findHeaderByIdempotencyKey(shopId, idempotencyKey).map(this::withLines);
    }

    /** 明细创建后不可变，先读头再读明细不会得到不一致的组合。 */
    private Reservation withLines(Reservation.Header header) {
        return new Reservation(header, findLines(header.shopId(), header.reservationId()));
    }

    public int markClaimed(long shopId, UUID reservationId, String paymentReference, Instant claimedAt) {
        return jdbc.update(tag("UPDATE " + headers
                + " SET status = 'CLAIMED', payment_reference = ?, claimed_at = ?"
                + " WHERE shop_id = ? AND reservation_id = ? AND status = 'ACTIVE'"),
                paymentReference, Codec.toDbTime(claimedAt), shopId, Codec.uuidToBytes(reservationId));
    }

    public int markCancelled(long shopId, UUID reservationId, Instant cancelledAt) {
        return jdbc.update(tag("UPDATE " + headers + " SET status = 'CANCELLED', cancelled_at = ?"
                + " WHERE shop_id = ? AND reservation_id = ? AND status = 'ACTIVE'"),
                Codec.toDbTime(cancelledAt), shopId, Codec.uuidToBytes(reservationId));
    }

    public int markExpired(long shopId, UUID reservationId, Instant expiredAt) {
        return jdbc.update(tag("UPDATE " + headers + " SET status = 'EXPIRED', expired_at = ?"
                + " WHERE shop_id = ? AND reservation_id = ? AND status = 'ACTIVE'"),
                Codec.toDbTime(expiredAt), shopId, Codec.uuidToBytes(reservationId));
    }

    /**
     * 非锁定扫描到期候选，沿 (status, expires_at, shop_id, reservation_id) 索引做 keyset 分页。
     * 候选只是提示；是否释放由持头锁后的状态与时间复查决定。
     */
    public List<ExpiryCandidate> findExpiryCandidates(ExpiryCandidate after, int limit) {
        String base = "SELECT shop_id, reservation_id, expires_at FROM " + headers
                + " WHERE status = 'ACTIVE' AND expires_at <= UTC_TIMESTAMP(6)";
        String order = " ORDER BY expires_at, shop_id, reservation_id LIMIT ?";
        if (after == null) {
            return jdbc.query(tag(base + order), ReservationRepository::mapCandidate, limit);
        }
        LocalDateTime expiresAt = Codec.toDbTime(after.expiresAt());
        return jdbc.query(tag(base
                + " AND (expires_at > ? OR (expires_at = ? AND (shop_id > ?"
                + "      OR (shop_id = ? AND reservation_id > ?))))" + order),
                ReservationRepository::mapCandidate,
                expiresAt, expiresAt, after.shopId(), after.shopId(), Codec.uuidToBytes(after.reservationId()), limit);
    }

    private static ExpiryCandidate mapCandidate(ResultSet rs, int rowNum) throws SQLException {
        return new ExpiryCandidate(rs.getLong(1), Codec.uuidFromBytes(rs.getBytes(2)), Codec.instant(rs, "expires_at"));
    }

    private static Reservation.Header mapHeader(ResultSet rs, int rowNum) throws SQLException {
        return new Reservation.Header(
                rs.getLong("shop_id"),
                Codec.uuidFromBytes(rs.getBytes("reservation_id")),
                rs.getString("idempotency_key"),
                rs.getBytes("request_hash"),
                ReservationStatus.valueOf(rs.getString("status")),
                rs.getInt("ttl_seconds"),
                rs.getInt("total_quantity"),
                rs.getInt("line_count"),
                rs.getString("payment_reference"),
                Codec.instant(rs, "expires_at"),
                Codec.instant(rs, "claimed_at"),
                Codec.instant(rs, "cancelled_at"),
                Codec.instant(rs, "expired_at"),
                Codec.instant(rs, "created_at"));
    }
}
