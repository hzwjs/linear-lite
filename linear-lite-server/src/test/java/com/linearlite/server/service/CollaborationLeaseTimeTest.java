package com.linearlite.server.service;

import com.linearlite.server.dto.CollaborationLeaseTime;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.TimeZone;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class CollaborationLeaseTimeTest {
    @Test void localStorageLeaseIsExposedAsAnAbsoluteInstant() {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
            assertEquals(Instant.parse("2026-11-10T02:00:00Z"),
                    CollaborationLeaseTime.toInstant(LocalDateTime.parse("2026-11-10T10:00:00")));
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            assertEquals(Instant.parse("2026-11-10T02:00:00Z"),
                    CollaborationLeaseTime.toInstant(LocalDateTime.parse("2026-11-10T10:00:00")));
        } finally { TimeZone.setDefault(original); }
    }
}
