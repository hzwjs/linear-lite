package com.linearlite.server.service;

import com.linearlite.server.dto.ProjectContentSearchResponse;
import com.linearlite.server.entity.ProjectMember;
import com.linearlite.server.entity.SearchableProjectContent;
import com.linearlite.server.mapper.ProjectContentSearchMapper;
import com.linearlite.server.mapper.ProjectMemberMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProjectContentSearchServiceTest {
    @Test
    void ranksTaskAndDocumentTitleBodyThenSemanticHits() {
        ProjectMemberMapper memberMapper = memberMapper(7L);
        ProjectContentSearchMapper contentMapper = mock(ProjectContentSearchMapper.class);
        ProjectContentSemanticSearchService semantic = mock(ProjectContentSemanticSearchService.class);
        SearchableProjectContent titleTask = content("TASK", 1L, "ENG-1", "搜索原则", "其他");
        SearchableProjectContent bodyDocument = content("DOCUMENT", 2L, "2", "架构", "搜索文档");
        SearchableProjectContent vectorTask = content("TASK", 3L, "ENG-3", "其他", "向量命中");
        when(contentMapper.selectAccessibleContents(List.of(7L)))
                .thenReturn(List.of(titleTask, bodyDocument, vectorTask));
        when(semantic.visibleText(titleTask)).thenReturn("其他");
        when(semantic.visibleText(bodyDocument)).thenReturn("搜索文档");
        when(semantic.visibleText(vectorTask)).thenReturn("向量命中");
        when(semantic.search(List.of(7L), "搜索")).thenReturn(List.of(
                new ProjectContentSemanticSearchService.SemanticContentHit("TASK", "ENG-3")));

        List<ProjectContentSearchResponse> result = service(memberMapper, contentMapper, semantic).search("搜索", 9L);

        assertEquals(List.of("ENG-1", "2", "ENG-3"), result.stream()
                .map(ProjectContentSearchResponse::resourceId).toList());
        assertEquals(List.of("task", "document", "task"), result.stream()
                .map(ProjectContentSearchResponse::contentType).toList());
        assertEquals(List.of("PHX", "PHX", "PHX"), result.stream()
                .map(ProjectContentSearchResponse::projectIdentifier).toList());
    }

    @Test
    void rejectsSemanticHitsOutsideAccessibleProjectContents() {
        ProjectMemberMapper memberMapper = memberMapper(7L);
        ProjectContentSearchMapper contentMapper = mock(ProjectContentSearchMapper.class);
        ProjectContentSemanticSearchService semantic = mock(ProjectContentSemanticSearchService.class);
        SearchableProjectContent visible = content("TASK", 1L, "ENG-1", "其他", "正文");
        when(contentMapper.selectAccessibleContents(List.of(7L))).thenReturn(List.of(visible));
        when(semantic.visibleText(visible)).thenReturn("正文");
        when(semantic.search(List.of(7L), "秘密")).thenReturn(List.of(
                new ProjectContentSemanticSearchService.SemanticContentHit("DOCUMENT", "99")));

        assertEquals(List.of(), service(memberMapper, contentMapper, semantic).search("秘密", 9L));
    }

    @Test
    void doesNotReturnLiteralFallbackWhenSemanticSearchFails() {
        ProjectMemberMapper memberMapper = memberMapper(7L);
        ProjectContentSearchMapper contentMapper = mock(ProjectContentSearchMapper.class);
        ProjectContentSemanticSearchService semantic = mock(ProjectContentSemanticSearchService.class);
        SearchableProjectContent literal = content("TASK", 1L, "ENG-1", "搜索", "正文");
        when(contentMapper.selectAccessibleContents(List.of(7L))).thenReturn(List.of(literal));
        when(semantic.visibleText(literal)).thenReturn("正文");
        when(semantic.search(List.of(7L), "搜索")).thenThrow(new IllegalStateException("Qdrant unavailable"));

        assertThrows(IllegalStateException.class,
                () -> service(memberMapper, contentMapper, semantic).search("搜索", 9L));
    }

    private static ProjectContentSearchService service(ProjectMemberMapper memberMapper,
                                                       ProjectContentSearchMapper contentMapper,
                                                       ProjectContentSemanticSearchService semantic) {
        return new ProjectContentSearchService(memberMapper, contentMapper, semantic);
    }

    private static ProjectMemberMapper memberMapper(Long projectId) {
        ProjectMember member = new ProjectMember();
        member.setProjectId(projectId);
        ProjectMemberMapper mapper = mock(ProjectMemberMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of(member));
        return mapper;
    }

    private static SearchableProjectContent content(String type, Long numericId, String resourceId,
                                                    String title, String source) {
        SearchableProjectContent content = new SearchableProjectContent();
        content.setContentType(type);
        content.setNumericId(numericId);
        content.setResourceId(resourceId);
        content.setProjectId(7L);
        content.setProjectIdentifier("PHX");
        content.setProjectName("Phoenix");
        content.setTitle(title);
        content.setSourceContent(source);
        return content;
    }
}
