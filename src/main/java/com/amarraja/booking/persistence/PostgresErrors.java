package com.amarraja.booking.persistence;

import java.sql.SQLException;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;

/** Classifies constraint violations by SQLSTATE and constraint name, never by message text. */
public final class PostgresErrors {

    private static final String UNIQUE_VIOLATION = "23505";
    private static final String EXCLUSION_VIOLATION = "23P01";

    static final String IDEMPOTENCY_KEY_CONSTRAINT = "booking_idempotency_key_uq";
    static final String NO_OVERLAP_CONSTRAINT = "booking_no_overlap";

    private PostgresErrors() {
    }

    public static boolean isOverlapViolation(Throwable error) {
        return violates(error, EXCLUSION_VIOLATION, NO_OVERLAP_CONSTRAINT);
    }

    public static boolean isIdempotencyKeyViolation(Throwable error) {
        return violates(error, UNIQUE_VIOLATION, IDEMPOTENCY_KEY_CONSTRAINT);
    }

    private static boolean violates(Throwable error, String sqlState, String constraint) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sqlState.equals(sql.getSQLState())) {
                return constraint.equals(constraintName(sql));
            }
        }
        return false;
    }

    private static String constraintName(SQLException e) {
        if (e instanceof PSQLException psql) {
            ServerErrorMessage message = psql.getServerErrorMessage();
            return message == null ? null : message.getConstraint();
        }
        return null;
    }
}
