package com.amarraja.booking.config;

import com.amarraja.booking.domain.SchedulingPolicy;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AppConfig {

    @Bean
    public Clock clock(BookingProperties properties) {
        return Clock.system(properties.timeZone());
    }

    @Bean
    public SchedulingPolicy schedulingPolicy(BookingProperties p) {
        return new SchedulingPolicy(p.timeZone(), p.slotGranularity(), p.travelBuffer(),
                p.minLeadTime(), p.maxHorizon(), p.cancellationCutoff());
    }
}
