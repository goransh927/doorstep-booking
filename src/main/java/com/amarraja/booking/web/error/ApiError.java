package com.amarraja.booking.web.error;

import java.time.Instant;
import java.util.List;

/**
 * The one error body every endpoint returns.
 *
 * @param code    stable, machine-readable reason (e.g. NO_AVAILABILITY); clients branch on this
 * @param details per-field problems for validation errors, otherwise absent
 */
public record ApiError(
        Instant timestamp,
        int status,
        String code,
        String message,
        String path,
        String requestId,
        List<FieldProblem> details) {

    public record FieldProblem(String field, String message) {
    }
}
