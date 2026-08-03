package com.linearlite.server.controller;

import com.linearlite.server.dto.ProjectContentSearchResponse;
import com.linearlite.server.dto.ProjectDocumentTreeNode;
import com.linearlite.server.filter.JwtAuthFilter;
import com.linearlite.server.service.ProjectContentSearchService;
import com.linearlite.server.service.ProjectDocumentCommandService;
import com.linearlite.server.service.ProjectDocumentQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ProjectDocumentControllerTest {
    @Test
    void listsGlobalDocumentFavoritesForCurrentUser() throws Exception {
        ProjectDocumentQueryService queryService = mock(ProjectDocumentQueryService.class);
        ProjectDocumentController controller = new ProjectDocumentController(
                queryService, mock(ProjectDocumentCommandService.class), mock(ProjectContentSearchService.class));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        ProjectDocumentTreeNode favorite = new ProjectDocumentTreeNode(
                12L, 7L, null, "Guide", 0, 1L, true, java.time.LocalDateTime.parse("2026-07-29T08:00:00"));
        when(queryService.listFavorites(9L)).thenReturn(List.of(favorite));

        mockMvc.perform(get("/api/project-documents/favorites")
                        .requestAttr(JwtAuthFilter.REQUEST_ATTR_USER_ID, 9L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(12))
                .andExpect(jsonPath("$.data[0].projectId").value(7))
                .andExpect(jsonPath("$.data[0].favorited").value(true));

        verify(queryService).listFavorites(9L);
    }

    @Test
    void searchesDocumentsWithinPathProjectAndCurrentUser() throws Exception {
        ProjectContentSearchService searchService = mock(ProjectContentSearchService.class);
        ProjectDocumentController controller = new ProjectDocumentController(
                mock(ProjectDocumentQueryService.class), mock(ProjectDocumentCommandService.class), searchService);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        ProjectContentSearchResponse hit = new ProjectContentSearchResponse(
                "document", "12", 7L, "PHX", "Phoenix", "派单说明", "命中摘要");
        when(searchService.searchDocuments(7L, "派单", 9L)).thenReturn(List.of(hit));

        mockMvc.perform(get("/api/projects/7/documents/search")
                        .param("query", "派单")
                        .requestAttr(JwtAuthFilter.REQUEST_ATTR_USER_ID, 9L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].contentType").value("document"))
                .andExpect(jsonPath("$.data[0].resourceId").value("12"));

        verify(searchService).searchDocuments(7L, "派单", 9L);
    }
}
