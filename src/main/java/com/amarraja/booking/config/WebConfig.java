package com.amarraja.booking.config;

import com.amarraja.booking.web.support.BookingRateLimitInterceptor;
import com.amarraja.booking.web.support.TokenBucketRateLimiter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final BookingProperties properties;
    private final ObjectMapper objectMapper;

    public WebConfig(BookingProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        BookingProperties.RateLimit rateLimit = properties.rateLimit();
        if (rateLimit.enabled()) {
            TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(rateLimit.capacity(),
                    rateLimit.refillPerMinute(), Clock.systemUTC());
            registry.addInterceptor(new BookingRateLimitInterceptor(limiter, objectMapper))
                    .addPathPatterns("/bookings");
        }
    }
}
