package com.linearlite.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linearlite.server.dto.CreateProjectDocumentRequest;
import com.linearlite.server.dto.MoveProjectDocumentRequest;
import com.linearlite.server.dto.ProjectDocumentResponse;
import com.linearlite.server.dto.UpdateProjectDocumentRequest;
import com.linearlite.server.entity.ProjectDocument;
import com.linearlite.server.entity.ProjectDocumentRevision;
import com.linearlite.server.exception.DocumentVersionConflictException;
import com.linearlite.server.mapper.ProjectDocumentMapper;
import com.linearlite.server.mapper.ProjectDocumentRevisionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectDocumentCommandServiceTest {
    @Mock private ProjectDocumentMapper documentMapper;
    @Mock private ProjectDocumentRevisionMapper revisionMapper;
    @Mock private ProjectAccessGuard accessGuard;
    @Mock private ApplicationEventPublisher eventPublisher;

    private ProjectDocumentCommandService service;

    @BeforeEach
    void setUp() {
        service = new ProjectDocumentCommandService(
                documentMapper, revisionMapper, accessGuard, new ObjectMapper(), eventPublisher);
    }

    @Test
    void createPersistsFixedEmptyBlockNoteDocumentAndInitialRevision() {
        doAnswer(invocation -> {
            ProjectDocument document = invocation.getArgument(0);
            document.setId(11L);
            return 1;
        }).when(documentMapper).insert(any(ProjectDocument.class));
        when(documentMapper.selectList(any())).thenReturn(List.of());
        when(documentMapper.selectById(11L)).thenAnswer(invocation -> {
            ProjectDocument saved = new ProjectDocument();
            saved.setId(11L);
            saved.setProjectId(3L);
            saved.setTitle("接口设计");
            saved.setContentJson("[]");
            saved.setSortOrder(0);
            saved.setVersion(1L);
            saved.setCreatorId(7L);
            saved.setLastEditorId(7L);
            return saved;
        });

        service.create(3L, new CreateProjectDocumentRequest(null, " 接口设计 "), 7L);

        ArgumentCaptor<ProjectDocument> documentCaptor = ArgumentCaptor.forClass(ProjectDocument.class);
        verify(documentMapper).insert(documentCaptor.capture());
        assertEquals("[]", documentCaptor.getValue().getContentJson());
        assertEquals("接口设计", documentCaptor.getValue().getTitle());
        ArgumentCaptor<ProjectDocumentRevision> revisionCaptor = ArgumentCaptor.forClass(ProjectDocumentRevision.class);
        verify(revisionMapper).insert(revisionCaptor.capture());
        assertEquals(1L, revisionCaptor.getValue().getVersion());
        verify(eventPublisher).publishEvent(new ProjectContentSemanticIndexRequestedEvent(
                ProjectContentType.DOCUMENT, 11L));
    }

    @Test
    void createAtomicallyPersistsProvidedInitialContent() {
        doAnswer(invocation -> {
            ProjectDocument document = invocation.getArgument(0);
            document.setId(12L);
            return 1;
        }).when(documentMapper).insert(any(ProjectDocument.class));
        when(documentMapper.selectList(any())).thenReturn(List.of());
        when(documentMapper.selectById(12L)).thenAnswer(invocation -> {
            ProjectDocument saved = new ProjectDocument();
            saved.setId(12L);
            saved.setProjectId(3L);
            saved.setTitle("迁移文档");
            saved.setContentJson("[{\"type\":\"paragraph\"}]");
            saved.setSortOrder(0);
            saved.setVersion(1L);
            saved.setCreatorId(7L);
            saved.setLastEditorId(7L);
            return saved;
        });

        service.create(3L, new CreateProjectDocumentRequest(
                null, "迁移文档", "[{\"type\":\"paragraph\"}]"), 7L);

        ArgumentCaptor<ProjectDocument> documentCaptor = ArgumentCaptor.forClass(ProjectDocument.class);
        verify(documentMapper).insert(documentCaptor.capture());
        assertEquals("[{\"type\":\"paragraph\"}]", documentCaptor.getValue().getContentJson());
    }

    @Test
    void createReusesDocumentOnlyByProjectAndExternalSourceId() {
        ProjectDocument existing = document(41L, 3L, null, 4L, 0);
        existing.setExternalSource("outline");
        existing.setExternalSourceId("EUEFsRqmJ4");
        when(documentMapper.selectOne(any())).thenReturn(existing);

        ProjectDocumentResponse response = service.create(3L, new CreateProjectDocumentRequest(
                null, "不能用于匹配的标题", "[]", "outline", "EUEFsRqmJ4"), 7L);

        assertEquals(41L, response.id());
        assertEquals("outline", response.externalSource());
        assertEquals("EUEFsRqmJ4", response.externalSourceId());
        verify(documentMapper, never()).insert(any());
        verify(revisionMapper, never()).insert(any());
    }

    @Test
    void createRejectsPartialExternalBinding() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                service.create(3L, new CreateProjectDocumentRequest(
                        null, "迁移文档", "[]", null, "EUEFsRqmJ4"), 7L));

        assertEquals("externalSource 与 externalSourceId 必须同时提供", error.getMessage());
        verify(documentMapper, never()).insert(any());
    }

    @Test
    void updateRejectsNonArrayContentBeforeWriting() {
        when(documentMapper.selectById(11L)).thenReturn(document(11L, 3L, null, 2L, 0));

        assertThrows(IllegalArgumentException.class, () -> service.update(
                11L, new UpdateProjectDocumentRequest(2L, "标题", "{}"), 7L));

        verify(documentMapper, never()).updateContentIfVersionMatches(any(), any(), any(), any(), any());
    }

    @Test
    void updateReturnsCurrentVersionOnOptimisticLockConflict() {
        ProjectDocument initial = document(11L, 3L, null, 2L, 0);
        ProjectDocument concurrent = document(11L, 3L, null, 3L, 0);
        when(documentMapper.selectById(11L)).thenReturn(initial, concurrent);
        when(documentMapper.updateContentIfVersionMatches(11L, 2L, "标题", "[]", 7L)).thenReturn(0);

        DocumentVersionConflictException error = assertThrows(DocumentVersionConflictException.class, () ->
                service.update(11L, new UpdateProjectDocumentRequest(2L, "标题", "[]"), 7L));

        assertEquals(3L, error.getCurrentVersion());
        verify(revisionMapper, never()).insert(any());
    }

    @Test
    void moveAcrossParentsPersistsParentEvenWhenSortOrderDoesNotChange() {
        ProjectDocument moving = document(11L, 3L, 10L, 2L, 1);
        ProjectDocument targetParent = document(20L, 3L, null, 1L, 0);
        ProjectDocument previousSibling = document(30L, 3L, 20L, 1L, 0);
        when(documentMapper.selectById(11L)).thenReturn(moving, moving);
        when(documentMapper.selectById(20L)).thenReturn(targetParent);
        when(documentMapper.selectById(30L)).thenReturn(previousSibling);
        when(documentMapper.selectSubtreeIds(3L, 11L)).thenReturn(List.of(11L));
        when(documentMapper.selectList(any())).thenReturn(List.of(previousSibling), List.of());

        service.move(11L, new MoveProjectDocumentRequest(20L, 30L), 7L);

        assertEquals(20L, moving.getParentDocumentId());
        assertEquals(1, moving.getSortOrder());
        verify(documentMapper).updateById(moving);
        var order = inOrder(documentMapper);
        order.verify(documentMapper).lockProjectDocumentMutations(3L);
        order.verify(documentMapper).updateById(previousSibling);
        order.verify(documentMapper).updateById(moving);
    }

    @Test
    void archivePublishesDeleteForEntireSubtree() {
        ProjectDocument root = document(11L, 3L, null, 2L, 0);
        when(documentMapper.selectById(11L)).thenReturn(root, root);
        when(documentMapper.selectSubtreeIds(3L, 11L)).thenReturn(List.of(11L, 12L));
        when(documentMapper.selectList(any())).thenReturn(List.of());

        service.archive(11L, 7L);

        verify(eventPublisher).publishEvent(new ProjectContentSemanticDeleteRequestedEvent(
                ProjectContentType.DOCUMENT, List.of(11L, 12L)));
    }

    private ProjectDocument document(Long id, Long projectId, Long parentId, Long version, Integer sortOrder) {
        ProjectDocument document = new ProjectDocument();
        document.setId(id);
        document.setProjectId(projectId);
        document.setParentDocumentId(parentId);
        document.setTitle("标题");
        document.setContentJson("[]");
        document.setVersion(version);
        document.setSortOrder(sortOrder);
        return document;
    }
}
