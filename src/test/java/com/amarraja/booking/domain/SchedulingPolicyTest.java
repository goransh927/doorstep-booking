package com.amarraja.booking.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SchedulingPolicyTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final long ZONE_A = 1;
    private static final long ZONE_B = 2;

    private static final ServiceType REPLACEMENT = new ServiceType(1, "Battery replacement", 60, Skill.BATTERY_FIT);
    private static final ServiceType JUMPSTART = new ServiceType(2, "Jump-start", 30, Skill.JUMPSTART);

    private final SchedulingPolicy policy = new SchedulingPolicy(IST, Duration.ofMinutes(30),
            Duration.ofMinutes(30), Duration.ofHours(2), Duration.ofDays(7), Duration.ofHours(1));

    private static Instant at(String localDateTime) {
        return LocalDateTime.parse(localDateTime).atZone(IST).toInstant();
    }

    private static Technician tech(long id, long zoneId, Skill... skills) {
        return new Technician(id, "Tech " + id, zoneId, Set.of(skills), LocalTime.of(8, 0), LocalTime.of(20, 0));
    }

    /** An existing booking for the technician over [start, start + minutes), plus the 30-min buffer. */
    private static Occupancy booked(long technicianId, String start, int minutes) {
        Instant s = at(start);
        return new Occupancy(technicianId, s, s.plus(Duration.ofMinutes(minutes + 30L)));
    }

    @Nested
    class OverlapAndBuffer {

        private final Technician ravi = tech(1, ZONE_A, Skill.BATTERY_FIT, Skill.JUMPSTART);
        // Existing job 10:00-11:00 blocks the technician until 11:30.
        private final List<Occupancy> existing = List.of(booked(1, "2030-01-08T10:00", 60));

        @ParameterizedTest(name = "{0} for {1} min -> free={2}")
        @CsvSource({
                "2030-01-08T10:00, 60, false",  // same slot
                "2030-01-08T10:30, 30, false",  // inside the existing job
                "2030-01-08T11:00, 30, false",  // inside the travel buffer after the job
                "2030-01-08T11:30, 60, true",   // exactly when the buffer ends
                "2030-01-08T09:00, 60, false",  // ends 10:00, but its own buffer runs to 10:30
                "2030-01-08T09:00, 30, true",   // ends 09:30, buffer to 10:00 just touches the job
                "2030-01-08T08:30, 30, true",
        })
        void respectsExistingJobsAndTravelBuffer(String start, int minutes, boolean expectedFree) {
            Instant s = at(start);
            assertThat(policy.isFree(ravi, s, s.plus(Duration.ofMinutes(minutes)), existing))
                    .isEqualTo(expectedFree);
        }

        @Test
        void bookingsOfOtherTechniciansDoNotMatter() {
            Technician other = tech(2, ZONE_A, Skill.BATTERY_FIT);
            Instant s = at("2030-01-08T10:00");
            assertThat(policy.isFree(other, s, s.plus(Duration.ofMinutes(60)), List.of())).isTrue();
        }
    }

    @Nested
    class ShiftEdges {

        private final Technician ravi = tech(1, ZONE_A, Skill.BATTERY_FIT);

        @ParameterizedTest(name = "{0} for {1} min -> fits={2}")
        @CsvSource({
                "2030-01-08T08:00, 60, true",   // starts exactly at shift start
                "2030-01-08T07:30, 60, false",  // starts before shift
                "2030-01-08T19:00, 60, true",   // ends exactly at shift end
                "2030-01-08T19:30, 60, false",  // runs past shift end
                "2030-01-08T19:30, 30, true",
                "2030-01-08T20:00, 30, false",
        })
        void jobMustSitInsideShift(String start, int minutes, boolean fits) {
            Instant s = at(start);
            assertThat(policy.isFree(ravi, s, s.plus(Duration.ofMinutes(minutes)), List.of())).isEqualTo(fits);
        }

        @Test
        void bufferAfterTheLastJobMayRunPastShiftEnd() {
            Instant s = at("2030-01-08T19:00");
            assertThat(policy.isFree(ravi, s, s.plus(Duration.ofMinutes(60)), List.of())).isTrue();
        }
    }

    @Nested
    class Qualification {

        @Test
        void requiresMatchingSkill() {
            Technician jumpstartOnly = tech(1, ZONE_A, Skill.JUMPSTART);
            assertThat(policy.isQualified(jumpstartOnly, JUMPSTART, ZONE_A)).isTrue();
            assertThat(policy.isQualified(jumpstartOnly, REPLACEMENT, ZONE_A)).isFalse();
        }

        @Test
        void requiresSameZone() {
            Technician zoneB = tech(1, ZONE_B, Skill.BATTERY_FIT);
            assertThat(policy.isQualified(zoneB, REPLACEMENT, ZONE_A)).isFalse();
            assertThat(policy.isQualified(zoneB, REPLACEMENT, ZONE_B)).isTrue();
        }

        @Test
        void rankingDropsUnqualifiedTechnicians() {
            List<Technician> all = List.of(
                    tech(1, ZONE_B, Skill.BATTERY_FIT),   // wrong zone
                    tech(2, ZONE_A, Skill.JUMPSTART),     // wrong skill
                    tech(3, ZONE_A, Skill.BATTERY_FIT));
            assertThat(policy.rankFreeTechnicians(REPLACEMENT, ZONE_A, at("2030-01-08T10:00"), all, Map.of()))
                    .extracting(Technician::id).containsExactly(3L);
        }
    }

    @Nested
    class Ranking {

        @Test
        void prefersFewestBookingsThatDayThenLowestId() {
            List<Technician> techs = List.of(
                    tech(1, ZONE_A, Skill.BATTERY_FIT),
                    tech(2, ZONE_A, Skill.BATTERY_FIT),
                    tech(3, ZONE_A, Skill.BATTERY_FIT),
                    tech(4, ZONE_A, Skill.BATTERY_FIT));
            Map<Long, List<Occupancy>> occupancies = Map.of(
                    1L, List.of(booked(1, "2030-01-08T08:00", 60), booked(1, "2030-01-08T15:00", 60)),
                    2L, List.of(booked(2, "2030-01-08T08:00", 60)),
                    4L, List.of(booked(4, "2030-01-08T16:00", 60)));

            List<Technician> ranked =
                    policy.rankFreeTechnicians(REPLACEMENT, ZONE_A, at("2030-01-08T12:00"), techs, occupancies);

            assertThat(ranked).extracting(Technician::id).containsExactly(3L, 2L, 4L, 1L);
        }

        @Test
        void excludesBusyTechnicians() {
            List<Technician> techs = List.of(tech(1, ZONE_A, Skill.BATTERY_FIT), tech(2, ZONE_A, Skill.BATTERY_FIT));
            Map<Long, List<Occupancy>> occupancies = Map.of(1L, List.of(booked(1, "2030-01-08T12:00", 60)));

            assertThat(policy.rankFreeTechnicians(REPLACEMENT, ZONE_A, at("2030-01-08T12:30"), techs, occupancies))
                    .extracting(Technician::id).containsExactly(2L);
        }

        @Test
        void onlyCountsBookingsOnTheSameDay() {
            List<Technician> techs = List.of(tech(1, ZONE_A, Skill.BATTERY_FIT), tech(2, ZONE_A, Skill.BATTERY_FIT));
            Map<Long, List<Occupancy>> occupancies = Map.of(
                    1L, List.of(booked(1, "2030-01-07T10:00", 60), booked(1, "2030-01-09T10:00", 60)),
                    2L, List.of(booked(2, "2030-01-08T08:00", 30)));

            assertThat(policy.rankFreeTechnicians(REPLACEMENT, ZONE_A, at("2030-01-08T12:00"), techs, occupancies))
                    .extracting(Technician::id).containsExactly(1L, 2L);
        }
    }

    @Nested
    class BookingWindow {

        private final Instant now = at("2030-01-08T06:00");

        @Test
        void earliestStartIsExactlyTwoHoursAhead() {
            Instant start = at("2030-01-08T08:00");
            assertThat(policy.isWithinBookingWindow(start, start.plus(Duration.ofMinutes(30)), now)).isTrue();
            Instant tooSoon = at("2030-01-08T07:59");
            assertThat(policy.isWithinBookingWindow(tooSoon, tooSoon.plus(Duration.ofMinutes(30)), now)).isFalse();
        }

        @Test
        void wholeBookingMustEndWithinSevenDays() {
            Instant lastStart = at("2030-01-15T05:30");
            assertThat(policy.isWithinBookingWindow(lastStart, lastStart.plus(Duration.ofMinutes(30)), now)).isTrue();
            Instant spillsOver = at("2030-01-15T05:30");
            assertThat(policy.isWithinBookingWindow(spillsOver, spillsOver.plus(Duration.ofMinutes(60)), now)).isFalse();
        }
    }

    @Nested
    class SlotAlignment {

        @ParameterizedTest
        @CsvSource({"2030-01-08T10:00, true", "2030-01-08T10:30, true", "2030-01-08T10:15, false",
                "2030-01-08T10:30:01, false"})
        void startsOnHalfHours(String start, boolean aligned) {
            assertThat(policy.isAlignedToSlot(at(start))).isEqualTo(aligned);
        }

        @Test
        void candidateStartsCoverTheShiftForTheServiceDuration() {
            List<Technician> techs = List.of(tech(1, ZONE_A, Skill.BATTERY_FIT));
            List<Instant> starts = policy.candidateStarts(LocalDate.of(2030, 1, 8), REPLACEMENT, techs);

            assertThat(starts).hasSize(23)
                    .startsWith(at("2030-01-08T08:00"))
                    .endsWith(at("2030-01-08T19:00"));
        }

        @Test
        void noTechniciansMeansNoCandidates() {
            assertThat(policy.candidateStarts(LocalDate.of(2030, 1, 8), REPLACEMENT, List.of())).isEmpty();
        }
    }

    @Nested
    class Cancellation {

        private final Instant start = at("2030-01-08T12:00");

        @Test
        void allowedUpToExactlyOneHourBefore() {
            assertThat(policy.canCancel(start, at("2030-01-08T11:00"))).isTrue();
        }

        @Test
        void refusedInsideTheLastHour() {
            assertThat(policy.canCancel(start, at("2030-01-08T11:01"))).isFalse();
        }
    }
}
