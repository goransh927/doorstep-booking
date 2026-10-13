package com.amarraja.booking.web.support;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;

/**
 * Accepts ISO-8601 date-times with an offset ({@code 2026-10-14T10:00:00+05:30}) or without one
 * ({@code 2026-10-14T10:00}); the latter is read as IST, the single time zone of the service.
 */
public class IstDateTimeDeserializer extends StdDeserializer<OffsetDateTime> {

    public static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    public IstDateTimeDeserializer() {
        super(OffsetDateTime.class);
    }

    @Override
    public OffsetDateTime deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        String text = parser.getValueAsString();
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(text.trim());
        } catch (DateTimeParseException withoutOffset) {
            try {
                return LocalDateTime.parse(text.trim()).atZone(IST).toOffsetDateTime();
            } catch (DateTimeParseException e) {
                return (OffsetDateTime) context.handleWeirdStringValue(OffsetDateTime.class, text,
                        "expected ISO-8601 date-time, e.g. 2026-10-14T10:00:00+05:30");
            }
        }
    }
}
