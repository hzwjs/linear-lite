package com.linearlite.server.controller;

import com.linearlite.server.common.ApiResponse;
import com.linearlite.server.dto.EmailSettingsResponse;
import com.linearlite.server.dto.UpdateEmailSettingsRequest;
import com.linearlite.server.entity.Project;
import com.linearlite.server.exception.ForbiddenOperationException;
import com.linearlite.server.filter.JwtAuthFilter;
import com.linearlite.server.service.ProjectEmailPreferenceService;
import com.linearlite.server.service.ProjectService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProjectEmailPreferenceControllerTest {

    private ProjectEmailPreferenceService preferenceService;
    private ProjectService projectService;
    private ProjectEmailPreferenceController controller;

    @BeforeEach
    void setUp() {
        preferenceService = mock(ProjectEmailPreferenceService.class);
        projectService = mock(ProjectService.class);
        controller = new ProjectEmailPreferenceController(preferenceService, projectService);
    }

    @Test
    void getEmailSettingsRequiresMembership() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(JwtAuthFilter.REQUEST_ATTR_USER_ID)).thenReturn(7L);
        when(preferenceService.isEnabled(10L, "daily_summary")).thenReturn(false);

        ResponseEntity<ApiResponse<List<EmailSettingsResponse>>> response = controller.list(10L, request);

        verify(projectService).requireProjectMember(10L, 7L);
        assertEquals(false, response.getBody().getData().get(0).getEnabled());
        assertEquals("daily_summary", response.getBody().getData().get(0).getScenarioKey());
    }

    @Test
    void putEmailSettingsUpdatesDailySummary() {
        Project project = new Project();
        project.setId(10L);
        project.setCreatorId(7L);
        when(projectService.loadProject(10L)).thenReturn(project);

        UpdateEmailSettingsRequest.Item item = new UpdateEmailSettingsRequest.Item();
        item.setScenarioKey("daily_summary");
        item.setEnabled(true);
        UpdateEmailSettingsRequest request = new UpdateEmailSettingsRequest();
        request.setItems(List.of(item));

        HttpServletRequest httpRequest = mock(HttpServletRequest.class);
        when(httpRequest.getAttribute(JwtAuthFilter.REQUEST_ATTR_USER_ID)).thenReturn(7L);

        controller.update(10L, request, httpRequest);

        verify(projectService).requireProjectMember(10L, 7L);
        verify(preferenceService).setEnabled(10L, "daily_summary", true);
    }

    @Test
    void putEmailSettingsRejectsNonCreator() {
        Project project = new Project();
        project.setId(10L);
        project.setCreatorId(99L);
        when(projectService.loadProject(10L)).thenReturn(project);

        UpdateEmailSettingsRequest.Item item = new UpdateEmailSettingsRequest.Item();
        item.setScenarioKey("daily_summary");
        item.setEnabled(true);
        UpdateEmailSettingsRequest request = new UpdateEmailSettingsRequest();
        request.setItems(List.of(item));

        HttpServletRequest httpRequest = mock(HttpServletRequest.class);
        when(httpRequest.getAttribute(JwtAuthFilter.REQUEST_ATTR_USER_ID)).thenReturn(7L);

        assertThrows(ForbiddenOperationException.class, () -> controller.update(10L, request, httpRequest));
    }
}
