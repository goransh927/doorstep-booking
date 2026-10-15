package com.amarraja.booking.persistence;

import com.amarraja.booking.domain.ServiceType;
import com.amarraja.booking.domain.Skill;
import com.amarraja.booking.domain.Technician;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ReferenceDataRepository {

    private final JdbcClient jdbc;

    public ReferenceDataRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ServiceType> findService(long id) {
        return jdbc.sql("SELECT id, name, duration_minutes, required_skill FROM service_type WHERE id = ?")
                .param(id)
                .query((rs, n) -> new ServiceType(rs.getLong("id"), rs.getString("name"),
                        rs.getInt("duration_minutes"), Skill.valueOf(rs.getString("required_skill"))))
                .optional();
    }

    public Optional<Long> findZoneIdByPincode(String pincode) {
        return jdbc.sql("SELECT zone_id FROM zone_pincode WHERE pincode = ?")
                .param(pincode)
                .query(Long.class)
                .optional();
    }

    public Optional<Technician> findTechnician(long id) {
        return loadTechnicians("WHERE t.id = ?", id).stream().findFirst();
    }

    public List<Technician> findTechniciansInZoneWithSkill(long zoneId, Skill skill) {
        return loadTechnicians("""
                WHERE t.zone_id = ?
                  AND EXISTS (SELECT 1 FROM technician_skill s
                              WHERE s.technician_id = t.id AND s.skill = ?)
                """, zoneId, skill.name());
    }

    /**
     * Takes a row lock on the technician. Every booking insert for a technician goes through
     * this lock, so "check overlap, then insert" for one technician runs one request at a time.
     */
    public void lockTechnician(long technicianId) {
        jdbc.sql("SELECT id FROM technician WHERE id = ? FOR UPDATE")
                .param(technicianId)
                .query(Long.class)
                .single();
    }

    private List<Technician> loadTechnicians(String where, Object... params) {
        List<TechnicianRow> rows = jdbc.sql("""
                        SELECT t.id, t.name, t.zone_id, t.shift_start, t.shift_end, s.skill
                        FROM technician t
                        LEFT JOIN technician_skill s ON s.technician_id = t.id
                        """ + where + " ORDER BY t.id")
                .params(params)
                .query((rs, n) -> new TechnicianRow(rs.getLong("id"), rs.getString("name"),
                        rs.getLong("zone_id"), rs.getObject("shift_start", LocalTime.class),
                        rs.getObject("shift_end", LocalTime.class), rs.getString("skill")))
                .list();

        Map<Long, List<TechnicianRow>> byTechnician = rows.stream()
                .collect(Collectors.groupingBy(TechnicianRow::id, LinkedHashMap::new, Collectors.toList()));
        List<Technician> technicians = new ArrayList<>();
        byTechnician.forEach((id, techRows) -> {
            TechnicianRow first = techRows.get(0);
            Set<Skill> skills = techRows.stream()
                    .map(TechnicianRow::skill)
                    .filter(Objects::nonNull)
                    .map(Skill::valueOf)
                    .collect(Collectors.toSet());
            technicians.add(new Technician(id, first.name(), first.zoneId(), skills,
                    first.shiftStart(), first.shiftEnd()));
        });
        return technicians;
    }

    private record TechnicianRow(long id, String name, long zoneId, LocalTime shiftStart,
                                 LocalTime shiftEnd, String skill) {
    }
}
