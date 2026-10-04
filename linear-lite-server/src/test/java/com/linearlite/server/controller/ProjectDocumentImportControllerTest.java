package com.linearlite.server.controller;

import com.linearlite.server.exception.GlobalExceptionHandler;
import com.linearlite.server.filter.JwtAuthFilter;
import com.linearlite.server.service.MarkdownDocumentImportService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ProjectDocumentImportControllerTest {
    @Test void passesMultipartFileParentAndAuthenticatedUserToImporter() throws Exception {
        var imports = mock(MarkdownDocumentImportService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new ProjectDocumentImportController(imports)).build();
        var file = new MockMultipartFile("file", "guide.md", "text/markdown", "# Guide".getBytes());
        mvc.perform(multipart("/api/projects/3/documents/import-markdown").file(file).file(new MockMultipartFile("imagePaths", "paths.json", "application/json", "[]".getBytes()))
                .param("parentDocumentId", "8").requestAttr(JwtAuthFilter.REQUEST_ATTR_USER_ID, 7L))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));
        verify(imports).importFile(3L, 8L, file, List.of(), List.of(), 7L);
    }

    @Test void returnsRequiredImagePathsWithoutCreatingDocument() throws Exception {
        var imports = mock(MarkdownDocumentImportService.class);
        var file = new MockMultipartFile("file", "guide.md", "text/markdown", "![图](image.png)".getBytes());
        when(imports.inspect(3L, file, 7L)).thenReturn(new MarkdownDocumentImportService.Preview(List.of("image.png")));
        var mvc = MockMvcBuilders.standaloneSetup(new ProjectDocumentImportController(imports)).build();
        mvc.perform(multipart("/api/projects/3/documents/import-markdown/inspect").file(file)
                .requestAttr(JwtAuthFilter.REQUEST_ATTR_USER_ID, 7L))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.imagePaths[0]").value("image.png"));
    }

    @Test void mapsRootImportValidationErrorToBadRequest() throws Exception {
        var imports = mock(MarkdownDocumentImportService.class);
        var file = new MockMultipartFile("file", "guide.txt", "text/plain", "text".getBytes());
        when(imports.importFile(3L, null, file, List.of(), List.of(), 7L)).thenThrow(new IllegalArgumentException("请选择 .md 文件"));
        var mvc = MockMvcBuilders.standaloneSetup(new ProjectDocumentImportController(imports))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(multipart("/api/projects/3/documents/import-markdown").file(file).file(new MockMultipartFile("imagePaths", "paths.json", "application/json", "[]".getBytes()))
                .requestAttr(JwtAuthFilter.REQUEST_ATTR_USER_ID, 7L))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("请选择 .md 文件"));
    }
}
