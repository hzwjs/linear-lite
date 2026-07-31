package com.linearlite.server.service;

import com.linearlite.server.dto.ProjectContentSearchResponse;
import com.linearlite.server.exception.ForbiddenOperationException;
import com.linearlite.server.mapper.ProjectMemberMapper;
import com.linearlite.server.mapper.ProjectMemberMapper.ProjectPermissionScope;
import com.linearlite.server.service.ProjectContentSearchIndex.SearchScope;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ProjectContentSearchServiceTest {
    @Test
    void mergesOnlyLimitedIndexCandidatesInTitleBodySemanticOrder() {
        ProjectMemberMapper memberMapper = memberMapper(List.of(new ProjectPermissionScope(7L, "PHX", "Phoenix")));
        ProjectContentSearchIndex index = mock(ProjectContentSearchIndex.class);
        ProjectContentSearchIndex.SearchHit shared = hit("TASK", 1L, "PHX-1", 7L, 100, 0.99);
        ProjectContentSearchIndex.SearchHit body = hit("DOCUMENT", 2L, "2", 7L, 300, 0);
        ProjectContentSearchIndex.SearchHit semantic = hit("TASK", 3L, "PHX-3", 7L, 200, 0.8);
        SearchScope scope = allContentScope(7L);
        when(index.searchTitle(scope, "派单")).thenReturn(List.of(shared));
        when(index.searchBody(scope, "派单")).thenReturn(List.of(body, shared));
        when(index.searchSemantic(scope, "派单")).thenReturn(List.of(semantic, shared));

        List<ProjectContentSearchResponse> result = service(memberMapper, index).search("派单", 9L);

        assertEquals(List.of("PHX-1", "2", "PHX-3"), result.stream()
                .map(ProjectContentSearchResponse::resourceId).toList());
        assertEquals(List.of("PHX", "PHX", "PHX"), result.stream()
                .map(ProjectContentSearchResponse::projectIdentifier).toList());
    }

    @Test
    void sortsSemanticHitsByScoreAndRejectsUnexpectedProjectPayload() {
        ProjectMemberMapper memberMapper = memberMapper(List.of(new ProjectPermissionScope(7L, "PHX", "Phoenix")));
        ProjectContentSearchIndex index = mock(ProjectContentSearchIndex.class);
        SearchScope scope = allContentScope(7L);
        when(index.searchTitle(scope, "派单")).thenReturn(List.of());
        when(index.searchBody(scope, "派单")).thenReturn(List.of());
        when(index.searchSemantic(scope, "派单")).thenReturn(List.of(
                hit("TASK", 1L, "PHX-1", 7L, 300, 0.4),
                hit("TASK", 2L, "PHX-2", 99L, 500, 1.0),
                hit("TASK", 3L, "PHX-3", 7L, 100, 0.9)));

        List<ProjectContentSearchResponse> result = service(memberMapper, index).search("派单", 9L);

        assertEquals(List.of("PHX-3", "PHX-1"), result.stream()
                .map(ProjectContentSearchResponse::resourceId).toList());
    }

    @Test
    void returnsAtMostFiftyResults() {
        ProjectMemberMapper memberMapper = memberMapper(List.of(new ProjectPermissionScope(7L, "PHX", "Phoenix")));
        ProjectContentSearchIndex index = mock(ProjectContentSearchIndex.class);
        List<ProjectContentSearchIndex.SearchHit> hits = new ArrayList<>();
        for (long id = 1; id <= 60; id++) hits.add(hit("TASK", id, "PHX-" + id, 7L, id, 0));
        SearchScope scope = allContentScope(7L);
        when(index.searchTitle(scope, "派单")).thenReturn(hits);
        when(index.searchBody(scope, "派单")).thenReturn(List.of());
        when(index.searchSemantic(scope, "派单")).thenReturn(List.of());

        assertEquals(50, service(memberMapper, index).search("派单", 9L).size());
    }

    @Test
    void doesNotQueryIndexWithoutPermissionAndDoesNotFallbackOnFailure() {
        ProjectMemberMapper emptyMapper = memberMapper(List.of());
        ProjectContentSearchIndex index = mock(ProjectContentSearchIndex.class);
        assertEquals(List.of(), service(emptyMapper, index).search("派单", 9L));
        verifyNoInteractions(index);

        ProjectMemberMapper memberMapper = memberMapper(List.of(new ProjectPermissionScope(7L, "PHX", "Phoenix")));
        when(index.searchTitle(allContentScope(7L), "派单"))
                .thenThrow(new IllegalStateException("Qdrant unavailable"));
        assertThrows(IllegalStateException.class, () -> service(memberMapper, index).search("派单", 9L));
    }

    @Test
    void projectDocumentSearchPushesSingleProjectAndDocumentTypeToEveryChannel() {
        ProjectMemberMapper memberMapper = memberMapper(List.of(
                new ProjectPermissionScope(7L, "PHX", "Phoenix"),
                new ProjectPermissionScope(8L, "OPS", "Operations")));
        ProjectContentSearchIndex index = mock(ProjectContentSearchIndex.class);
        SearchScope scope = new SearchScope(List.of(7L), List.of(ProjectContentType.DOCUMENT));
        ProjectContentSearchIndex.SearchHit document = hit("DOCUMENT", 2L, "2", 7L, 300, 0.8);
        when(index.searchTitle(scope, "派单")).thenReturn(List.of(document));
        when(index.searchBody(scope, "派单")).thenReturn(List.of());
        when(index.searchSemantic(scope, "派单")).thenReturn(List.of());

        List<ProjectContentSearchResponse> result = service(memberMapper, index)
                .searchDocuments(7L, "派单", 9L);

        assertEquals(List.of("2"), result.stream().map(ProjectContentSearchResponse::resourceId).toList());
        verify(index).searchSemantic(scope, "派单");
    }

    @Test
    void projectDocumentSearchRejectsProjectOutsidePermissionSnapshotBeforeQueryingIndex() {
        ProjectMemberMapper memberMapper = memberMapper(
                List.of(new ProjectPermissionScope(8L, "OPS", "Operations")));
        ProjectContentSearchIndex index = mock(ProjectContentSearchIndex.class);

        assertThrows(ForbiddenOperationException.class,
                () -> service(memberMapper, index).searchDocuments(7L, "派单", 9L));
        assertThrows(ForbiddenOperationException.class,
                () -> service(memberMapper, index).searchDocuments(7L, "", 9L));

        verifyNoInteractions(index);
    }

    private static ProjectContentSearchService service(ProjectMemberMapper mapper, ProjectContentSearchIndex index) {
        return new ProjectContentSearchService(mapper, index);
    }

    private static SearchScope allContentScope(Long projectId) {
        return new SearchScope(List.of(projectId),
                List.of(ProjectContentType.TASK, ProjectContentType.DOCUMENT));
    }

    private static ProjectMemberMapper memberMapper(List<ProjectPermissionScope> scopes) {
        ProjectMemberMapper mapper = mock(ProjectMemberMapper.class);
        when(mapper.selectSearchPermissionScopes(9L)).thenReturn(scopes);
        return mapper;
    }

    private static ProjectContentSearchIndex.SearchHit hit(String type, Long numericId, String resourceId,
                                                            Long projectId, long updatedAt, double score) {
        return new ProjectContentSearchIndex.SearchHit(type, numericId, resourceId, projectId,
                "标题 " + numericId, "摘要 " + numericId, updatedAt, score);
    }
}
