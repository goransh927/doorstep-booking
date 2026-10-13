package com.amarraja.booking.domain;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Set;

/**
 * A technician works a single daily shift in local (IST) time. Shifts never cross midnight.
 */
public record Technician(long id, String name, long zoneId, Set<Skill> skills,
                         LocalTime shiftStart, LocalTime shiftEnd) {

    public Technician {
        skills = Set.copyOf(skills);
    }

    public boolean canPerform(ServiceType service) {
        return skills.contains(service.requiredSkill());
    }

    public boolean servesZone(long zoneId) {
        return this.zoneId == zoneId;
    }

    /** True if [start, end) lies fully inside this technician's shift on that day. */
    public boolean shiftCovers(LocalDateTime start, LocalDateTime end) {
        return start.toLocalDate().equals(end.toLocalDate())
                && !start.toLocalTime().isBefore(shiftStart)
                && !end.toLocalTime().isAfter(shiftEnd);
    }
}
