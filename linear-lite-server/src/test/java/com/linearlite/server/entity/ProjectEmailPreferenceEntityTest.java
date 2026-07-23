package com.linearlite.server.entity;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ProjectEmailPreferenceEntityTest {

    @Test
    void preferenceHoldsProjectScenarioAndEnabledFlag() {
        ProjectEmailPreference preference = new ProjectEmailPreference();
        preference.setId(1L);
        preference.setProjectId(10L);
        preference.setScenarioKey("daily_summary");
        preference.setEnabled(false);
        preference.setCreatedAt(LocalDateTime.now());
        preference.setUpdatedAt(LocalDateTime.now());

        assertEquals(10L, preference.getProjectId());
        assertEquals("daily_summary", preference.getScenarioKey());
        assertEquals(false, preference.getEnabled());
        assertNotNull(preference.getCreatedAt());
        assertNotNull(preference.getUpdatedAt());
    }

    @Test
    void dispatchHoldsBusinessDateAndStatus() {
        ProjectEmailDispatch dispatch = new ProjectEmailDispatch();
        dispatch.setId(2L);
        dispatch.setProjectId(10L);
        dispatch.setScenarioKey("daily_summary");
        dispatch.setBusinessDate(LocalDate.of(2026, 7, 24));
        dispatch.setRecipientUserId(7L);
        dispatch.setStatus("pending");
        dispatch.setSubject("今日汇总");
        dispatch.setTaskCount(3);
        dispatch.setSentAt(null);
        dispatch.setCreatedAt(LocalDateTime.now());
        dispatch.setUpdatedAt(LocalDateTime.now());

        assertEquals(LocalDate.of(2026, 7, 24), dispatch.getBusinessDate());
        assertEquals("pending", dispatch.getStatus());
        assertEquals(3, dispatch.getTaskCount());
    }
}
