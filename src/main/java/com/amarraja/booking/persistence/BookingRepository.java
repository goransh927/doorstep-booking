package com.amarraja.booking.persistence;

import com.amarraja.booking.domain.Booking;
import com.amarraja.booking.domain.BookingStatus;
import com.amarraja.booking.domain.Occupancy;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class BookingRepository {

    private static final String SELECT_BOOKING = """
            SELECT id, customer_phone, service_id, pincode, technician_id, start_time, end_time,
                   blocked_until, status, idempotency_key, request_fingerprint, created_at,
                   updated_at, cancelled_at
            FROM booking
            """;

    private final JdbcClient jdbc;

    public BookingRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Booking insert(Booking b) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("""
                        INSERT INTO booking (customer_phone, service_id, pincode, technician_id, start_time,
                                             end_time, blocked_until, status, idempotency_key,
                                             request_fingerprint, created_at, updated_at)
                        VALUES (:phone, :serviceId, :pincode, :technicianId, :start, :end, :blockedUntil,
                                :status, :idempotencyKey, :fingerprint, :createdAt, :updatedAt)
                        """)
                .param("phone", b.customerPhone())
                .param("serviceId", b.serviceId())
                .param("pincode", b.pincode())
                .param("technicianId", b.technicianId())
                .param("start", ts(b.startTime()))
                .param("end", ts(b.endTime()))
                .param("blockedUntil", ts(b.blockedUntil()))
                .param("status", b.status().name())
                .param("idempotencyKey", b.idempotencyKey())
                .param("fingerprint", b.requestFingerprint())
                .param("createdAt", ts(b.createdAt()))
                .param("updatedAt", ts(b.updatedAt()))
                .update(keys, "id");
        long id = keys.getKeyAs(Number.class).longValue();
        return findById(id).orElseThrow();
    }

    public Optional<Booking> findById(long id) {
        return jdbc.sql(SELECT_BOOKING + "WHERE id = ?")
                .param(id).query(BookingRepository::map).optional();
    }

    public Optional<Booking> findByIdForUpdate(long id) {
        return jdbc.sql(SELECT_BOOKING + "WHERE id = ? FOR UPDATE")
                .param(id).query(BookingRepository::map).optional();
    }

    public Optional<Booking> findByIdempotencyKey(String key) {
        return jdbc.sql(SELECT_BOOKING + "WHERE idempotency_key = ?")
                .param(key).query(BookingRepository::map).optional();
    }

    /** Live bookings of the given technicians whose blocked range touches [from, to). */
    public List<Occupancy> findOccupancies(Collection<Long> technicianIds, Instant from, Instant to) {
        if (technicianIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        SELECT technician_id, start_time, blocked_until FROM booking
                        WHERE technician_id IN (:ids) AND status <> 'CANCELLED'
                          AND start_time < :to AND blocked_until > :from
                        """)
                .param("ids", technicianIds)
                .param("from", ts(from))
                .param("to", ts(to))
                .query((rs, n) -> new Occupancy(rs.getLong("technician_id"),
                        instant(rs, "start_time"), instant(rs, "blocked_until")))
                .list();
    }

    public boolean hasOverlap(long technicianId, Instant start, Instant blockedUntil) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM booking
                                       WHERE technician_id = :tech AND status <> 'CANCELLED'
                                         AND start_time < :blockedUntil AND blocked_until > :start)
                        """)
                .param("tech", technicianId)
                .param("start", ts(start))
                .param("blockedUntil", ts(blockedUntil))
                .query(Boolean.class)
                .single();
    }

    /** Live bookings of one technician starting in [from, to), earliest first. */
    public List<Booking> findLiveForTechnician(long technicianId, Instant from, Instant to) {
        return jdbc.sql(SELECT_BOOKING + """
                        WHERE technician_id = :tech AND status <> 'CANCELLED'
                          AND start_time >= :from AND start_time < :to
                        ORDER BY start_time
                        """)
                .param("tech", technicianId)
                .param("from", ts(from))
                .param("to", ts(to))
                .query(BookingRepository::map)
                .list();
    }

    public List<Booking> findByPhone(String phone, BookingStatus status, int limit, long offset) {
        return jdbc.sql(SELECT_BOOKING + """
                        WHERE customer_phone = :phone
                          AND (CAST(:status AS VARCHAR) IS NULL OR status = :status)
                        ORDER BY start_time DESC, id DESC
                        LIMIT :limit OFFSET :offset
                        """)
                .param("phone", phone)
                .param("status", status == null ? null : status.name())
                .param("limit", limit)
                .param("offset", offset)
                .query(BookingRepository::map)
                .list();
    }

    public long countByPhone(String phone, BookingStatus status) {
        return jdbc.sql("""
                        SELECT count(*) FROM booking
                        WHERE customer_phone = :phone
                          AND (CAST(:status AS VARCHAR) IS NULL OR status = :status)
                        """)
                .param("phone", phone)
                .param("status", status == null ? null : status.name())
                .query(Long.class)
                .single();
    }

    public void markCancelled(long id, Instant now) {
        jdbc.sql("UPDATE booking SET status = 'CANCELLED', cancelled_at = :now, updated_at = :now WHERE id = :id")
                .param("now", ts(now))
                .param("id", id)
                .update();
    }

    private static Booking map(ResultSet rs, int rowNum) throws SQLException {
        return new Booking(
                rs.getLong("id"),
                rs.getString("customer_phone"),
                rs.getLong("service_id"),
                rs.getString("pincode"),
                rs.getLong("technician_id"),
                instant(rs, "start_time"),
                instant(rs, "end_time"),
                instant(rs, "blocked_until"),
                BookingStatus.valueOf(rs.getString("status")),
                rs.getString("idempotency_key"),
                rs.getString("request_fingerprint"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"),
                instant(rs, "cancelled_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    /** pgjdbc binds OffsetDateTime to timestamptz natively; Instant it does not. */
    static OffsetDateTime ts(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
