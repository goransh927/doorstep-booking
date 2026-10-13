package com.amarraja.booking.web.support;

import com.amarraja.booking.web.error.ApiError;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.HandlerInterceptor;

/** Limits POST /bookings per client IP and answers 429 with the standard error body. */
public class BookingRateLimitInterceptor implements HandlerInterceptor {

    private final TokenBucketRateLimiter limiter;
    private final ObjectMapper objectMapper;

    public BookingRateLimitInterceptor(TokenBucketRateLimiter limiter, ObjectMapper objectMapper) {
        this.limiter = limiter;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (!"POST".equals(request.getMethod()) || !"/bookings".equals(request.getRequestURI())) {
            return true;
        }
        if (limiter.tryAcquire(request.getRemoteAddr())) {
            return true;
        }
        ApiError body = new ApiError(Instant.now(), HttpStatus.TOO_MANY_REQUESTS.value(), "RATE_LIMITED",
                "Too many booking requests; retry later", request.getRequestURI(),
                MDC.get(RequestIdFilter.MDC_KEY), null);
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(limiter.retryAfterSeconds()));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), body);
        return false;
    }
}
