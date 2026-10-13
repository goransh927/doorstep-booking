package com.amarraja.booking.web;

import com.amarraja.booking.service.ScheduleService;
import com.amarraja.booking.service.ScheduleService.DaySchedule;
import com.amarraja.booking.web.dto.Responses.ScheduleResponse;
import io.swagger.v3.oas.annotations.Operation;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TechnicianController {

    private final ScheduleService scheduleService;
    private final BookingResponseMapper mapper;

    public TechnicianController(ScheduleService scheduleService, BookingResponseMapper mapper) {
        this.scheduleService = scheduleService;
        this.mapper = mapper;
    }

    @Operation(summary = "A technician's live bookings for a day (IST), in start order")
    @GetMapping("/technicians/{id}/schedule")
    public ScheduleResponse schedule(@PathVariable long id,
                                     @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        DaySchedule schedule = scheduleService.schedule(id, date);
        return new ScheduleResponse(BookingResponseMapper.summary(schedule.technician()), schedule.date(),
                mapper.toResponses(schedule.bookings()));
    }
}
