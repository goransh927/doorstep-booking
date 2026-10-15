package com.amarraja.booking.web.error;

import java.time.Instant;
import java.util.List;

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
