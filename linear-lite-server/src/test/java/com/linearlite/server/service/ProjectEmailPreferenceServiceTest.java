package com.linearlite.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linearlite.server.entity.ProjectEmailPreference;
import com.linearlite.server.mapper.ProjectEmailPreferenceMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectEmailPreferenceServiceTest {

    @Mock
    private ProjectEmailPreferenceMapper mapper;

    private ProjectEmailPreferenceService service;

    @BeforeEach
    void setUp() {
        service = new ProjectEmailPreferenceService(mapper);
    }

    @Test
    void initializeForProjectInsertsDailySummaryDisabled() {
        service.initializeForProject(10L);

        ArgumentCaptor<ProjectEmailPreference> captor = ArgumentCaptor.forClass(ProjectEmailPreference.class);
        verify(mapper).insert(captor.capture());
        ProjectEmailPreference saved = captor.getValue();
        assertEquals(10L, saved.getProjectId());
        assertEquals("daily_summary", saved.getScenarioKey());
        assertEquals(false, saved.getEnabled());
    }

    @Test
    void isEnabledReturnsFalseWhenMissing() {
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        boolean result = service.isEnabled(10L, "daily_summary");

        assertFalse(result);
    }

    @Test
    void isEnabledReturnsStoredFlag() {
        ProjectEmailPreference preference = new ProjectEmailPreference();
        preference.setEnabled(true);
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(preference);

        boolean result = service.isEnabled(10L, "daily_summary");

        assertTrue(result);
    }

    @Test
    void setEnabledUpdatesExistingRecord() {
        ProjectEmailPreference existing = new ProjectEmailPreference();
        existing.setId(5L);
        existing.setEnabled(false);
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);

        service.setEnabled(10L, "daily_summary", true);

        assertEquals(true, existing.getEnabled());
        verify(mapper).updateById(existing);
    }

    @Test
    void listEnabledProjectIdsReturnsProjectIdsForEnabledScenario() {
        ProjectEmailPreference a = new ProjectEmailPreference();
        a.setProjectId(1L);
        a.setEnabled(true);
        ProjectEmailPreference b = new ProjectEmailPreference();
        b.setProjectId(2L);
        b.setEnabled(true);
        when(mapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(a, b));

        List<Long> ids = service.listEnabledProjectIds("daily_summary");

        assertEquals(List.of(1L, 2L), ids);
    }
}
