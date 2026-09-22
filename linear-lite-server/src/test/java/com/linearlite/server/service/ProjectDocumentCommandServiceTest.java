package com.linearlite.server.service;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linearlite.server.dto.CreateProjectDocumentRequest;
import com.linearlite.server.dto.MoveProjectDocumentRequest;
import com.linearlite.server.dto.ProjectDocumentResponse;
import com.linearlite.server.dto.UpdateProjectDocumentRequest;
import com.linearlite.server.entity.ProjectDocument;
import com.linearlite.server.entity.ProjectDocumentFavorite;
import com.linearlite.server.entity.ProjectDocumentRevision;
import com.linearlite.server.exception.DocumentVersionConflictException;
import com.linearlite.server.mapper.ProjectDocumentMapper;
import com.linearlite.server.mapper.ProjectDocumentFavoriteMapper;
import com.linearlite.server.mapper.ProjectDocumentRevisionMapper;
import com.linearlite.server.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    @Mock private ProjectDocumentFavoriteMapper favoriteMapper;
    @Mock private ProjectDocumentRevisionMapper revisionMapper;
    @Mock private ProjectAccessGuard accessGuard;
    @Mock private DocumentRevisionSnapshotService revisionSnapshotService;
    @Mock private UserMapper userMapper;
    @Mock private ProjectDocumentAttachmentService attachmentService;
    @Mock private ApplicationEventPublisher eventPublisher;

    private ProjectDocumentCommandService service;

    @BeforeEach
    void setUp() {
        ProjectDocumentQueryService queryService = new ProjectDocumentQueryService(
                documentMapper, favoriteMapper, revisionMapper, userMapper, accessGuard, attachmentService);
        service = new ProjectDocumentCommandService(
                documentMapper, favoriteMapper, revisionMapper, accessGuard, revisionSnapshotService,
                queryService, new ObjectMapper(), eventPublisher);
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
        verify(revisionSnapshotService).captureInitial(any(ProjectDocument.class), org.mockito.ArgumentMatchers.eq(7L));
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
    void createPlacesNewDocumentFirstAndShiftsExistingSiblings() {
        ProjectDocument first = document(21L, 3L, null, 1L, 0);
        ProjectDocument second = document(22L, 3L, null, 1L, 1);
        doAnswer(invocation -> {
            ProjectDocument document = invocation.getArgument(0);
            document.setId(23L);
            return 1;
        }).when(documentMapper).insert(any(ProjectDocument.class));
        when(documentMapper.selectList(any())).thenReturn(List.of(first, second));
        when(documentMapper.selectById(23L)).thenAnswer(invocation -> {
            ProjectDocument saved = new ProjectDocument();
            saved.setId(23L);
            saved.setProjectId(3L);
            saved.setTitle("新文档");
            saved.setContentJson("[]");
            saved.setSortOrder(0);
            saved.setVersion(1L);
            saved.setCreatorId(7L);
            saved.setLastEditorId(7L);
            return saved;
        });

        service.create(3L, new CreateProjectDocumentRequest(null, "新文档"), 7L);

        ArgumentCaptor<ProjectDocument> documentCaptor = ArgumentCaptor.forClass(ProjectDocument.class);
        verify(documentMapper).insert(documentCaptor.capture());
        assertEquals(0, documentCaptor.getValue().getSortOrder());
        // 现有兄弟整体后移一位，新文档占据列表第一位
        verify(documentMapper).updatePosition(21L, null, 1);
        verify(documentMapper).updatePosition(22L, null, 2);
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
    void createWithContentFactoryCapturesFinalImageBodyBeforeSnapshotAndIndexing() {
        String finalContent = "[{\"type\":\"documentImage\",\"props\":{\"imageAssetId\":31}}]";
        AtomicReference<String> persistedContent = new AtomicReference<>("[]");
        doAnswer(invocation -> {
            ProjectDocument document = invocation.getArgument(0);
            document.setId(11L);
            return 1;
        }).when(documentMapper).insert(any(ProjectDocument.class));
        when(documentMapper.selectList(any())).thenReturn(List.of());
        when(documentMapper.update(org.mockito.ArgumentMatchers.isNull(), any(UpdateWrapper.class)))
                .thenAnswer(invocation -> {
                    persistedContent.set(finalContent);
                    return 1;
                });
        when(documentMapper.selectById(11L)).thenAnswer(invocation -> {
            ProjectDocument saved = document(11L, 3L, null, 1L, 0);
            saved.setTitle("插图设计");
            saved.setContentJson(persistedContent.get());
            return saved;
        });

        ProjectDocumentResponse response = service.createWithContentFactory(
                3L, new CreateProjectDocumentRequest(null, "插图设计", null), 7L, id -> {
                    assertEquals(11L, id);
                    return finalContent;
                });

        assertEquals(finalContent, response.content());
        org.mockito.InOrder order = inOrder(documentMapper, revisionSnapshotService, eventPublisher);
        order.verify(documentMapper).insert(any(ProjectDocument.class));
        order.verify(documentMapper).update(org.mockito.ArgumentMatchers.isNull(), any(UpdateWrapper.class));
        order.verify(revisionSnapshotService).captureInitial(any(ProjectDocument.class),
                org.mockito.ArgumentMatchers.eq(7L));
        order.verify(eventPublisher).publishEvent(new ProjectContentSemanticIndexRequestedEvent(
                ProjectContentType.DOCUMENT, 11L));
    }

    @Test
    void idempotentCreateDoesNotRunImageContentFactory() {
        ProjectDocument existing = document(41L, 3L, null, 4L, 0);
        existing.setExternalSource("mcp");
        existing.setExternalSourceId("request-1");
        when(documentMapper.selectOne(any())).thenReturn(existing);
        AtomicBoolean invoked = new AtomicBoolean();

        service.createWithContentFactory(3L,
                new CreateProjectDocumentRequest(null, "同一文档", null, "mcp", "request-1"), 7L,
                id -> {
                    invoked.set(true);
                    return "[]";
                });

        org.junit.jupiter.api.Assertions.assertFalse(invoked.get());
        verify(documentMapper, never()).insert(any());
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
    void createRejectsLegacyImageUrlBlock() {
        String legacyContent = "[{\"type\":\"image\",\"props\":{\"url\":\"https://example.com/image.png\"}}]";

        assertThrows(IllegalArgumentException.class, () -> service.create(
                3L, new CreateProjectDocumentRequest(null, "标题", legacyContent), 7L));

        verify(documentMapper, never()).insert(any(ProjectDocument.class));
    }

    @Test
    void updateRejectsLegacyImageUrlBlock() {
        when(documentMapper.selectById(11L)).thenReturn(document(11L, 3L, null, 2L, 0));
        String legacyContent = "[{\"type\":\"image\",\"props\":{\"url\":\"https://example.com/image.png\"}}]";

        assertThrows(IllegalArgumentException.class, () -> service.update(
                11L, new UpdateProjectDocumentRequest(2L, "标题", legacyContent), 7L));

        verify(documentMapper, never()).updateContentIfVersionMatches(any(), any(), any(), any(), any());
    }

    @Test
    void documentImageRejectsAssetIdsOutsideLongRange() {
        String invalidContent = "[{\"type\":\"documentImage\",\"props\":{\"imageAssetId\":9223372036854775808}}]";

        assertThrows(IllegalArgumentException.class, () -> service.create(
                3L, new CreateProjectDocumentRequest(null, "标题", invalidContent), 7L));

        verify(documentMapper, never()).insert(any(ProjectDocument.class));
    }

    @Test
    void updateReturnsCurrentVersionOnOptimisticLockConflict() {
        ProjectDocument initial = document(11L, 3L, null, 2L, 0);
        ProjectDocument concurrent = document(11L, 3L, null, 3L, 0);
        when(documentMapper.selectById(11L)).thenReturn(initial, concurrent);
        when(documentMapper.updateContentIfVersionMatches(11L, 2L, "新标题", "[]", 7L)).thenReturn(0);

        DocumentVersionConflictException error = assertThrows(DocumentVersionConflictException.class, () ->
                service.update(11L, new UpdateProjectDocumentRequest(2L, "新标题", "[]"), 7L));

        assertEquals(3L, error.getCurrentVersion());
        verify(revisionMapper, never()).insert(any());
    }

    @Test
    void updateVersionConflictDoesNotRunImageContentFactory() {
        when(documentMapper.selectById(11L)).thenReturn(document(11L, 3L, null, 3L, 0));
        AtomicBoolean invoked = new AtomicBoolean();

        assertThrows(DocumentVersionConflictException.class, () -> service.updateWithContentFactory(
                11L, new UpdateProjectDocumentRequest(2L, "新标题", null), 7L, id -> {
                    invoked.set(true);
                    return "[]";
                }));

        org.junit.jupiter.api.Assertions.assertFalse(invoked.get());
        verify(revisionMapper, never()).insert(any());
    }

    @Test
    void restoreRevisionRejectsLegacyImageUrlBlock() {
        when(documentMapper.selectById(11L)).thenReturn(document(11L, 3L, null, 2L, 0));
        ProjectDocumentRevision revision = revision(41L, 11L,
                "[{\"type\":\"image\",\"props\":{\"url\":\"/api/project-documents/11/attachments/31/download\"}}]");
        when(revisionMapper.selectOne(any())).thenReturn(revision);

        assertThrows(IllegalArgumentException.class, () -> service.restoreRevision(11L, 41L, 2L, 7L));

        verify(revisionSnapshotService, never()).captureBeforeRestore(any());
        verify(documentMapper, never()).updateContentIfVersionMatches(any(), any(), any(), any(), any());
    }

    @Test
    void restoreRevisionAcceptsDocumentImageAssetId() {
        String content = "[{\"type\":\"documentImage\",\"props\":{\"imageAssetId\":31}}]";
        ProjectDocument current = document(11L, 3L, null, 2L, 0);
        ProjectDocument saved = document(11L, 3L, null, 3L, 0);
        saved.setTitle("历史标题");
        saved.setContentJson(content);
        ProjectDocumentRevision revision = revision(41L, 11L, content);
        revision.setTitle("历史标题");
        when(documentMapper.selectById(11L)).thenReturn(current, saved);
        when(revisionMapper.selectOne(any())).thenReturn(revision);
        when(documentMapper.updateContentIfVersionMatches(11L, 2L, "历史标题", content, 7L)).thenReturn(1);

        ProjectDocumentResponse response = service.restoreRevision(11L, 41L, 2L, 7L);

        assertEquals(content, response.content());
        verify(revisionSnapshotService).captureBeforeRestore(current);
        verify(revisionSnapshotService).captureRestored(saved, 7L);
    }

    @Test
    void updateAutosaveChangesCurrentDocumentWithoutCreatingRevision() {
        ProjectDocument initial = document(11L, 3L, null, 2L, 0);
        ProjectDocument saved = document(11L, 3L, null, 3L, 0);
        saved.setTitle("新标题");
        when(documentMapper.selectById(11L)).thenReturn(initial, saved);
        when(documentMapper.updateContentIfVersionMatches(11L, 2L, "新标题", "[]", 7L)).thenReturn(1);

        ProjectDocumentResponse response = service.update(
                11L, new UpdateProjectDocumentRequest(2L, "新标题", "[]"), 7L);

        assertEquals(3L, response.version());
        verify(revisionMapper, never()).insert(any());
        verify(eventPublisher).publishEvent(new ProjectContentSemanticIndexRequestedEvent(
                ProjectContentType.DOCUMENT, 11L));
    }

    @Test
    void updateCheckpointDelegatesSnapshotDecisionToRevisionService() {
        ProjectDocument initial = document(11L, 3L, null, 2L, 0);
        ProjectDocument saved = document(11L, 3L, null, 3L, 0);
        saved.setTitle("新标题");
        when(documentMapper.selectById(11L)).thenReturn(initial, saved);
        when(documentMapper.updateContentIfVersionMatches(11L, 2L, "新标题", "[]", 7L)).thenReturn(1);

        service.update(11L, new UpdateProjectDocumentRequest(2L, "新标题", "[]"), 7L);

        verify(revisionSnapshotService).captureBeforeUpdate(any(ProjectDocument.class), org.mockito.ArgumentMatchers.eq(7L));
        verify(revisionSnapshotService).captureAfterUpdate(any(ProjectDocument.class), org.mockito.ArgumentMatchers.eq(7L));
    }

    @Test
    void updateSkipsUnchangedContentAndVersion() {
        ProjectDocument current = document(11L, 3L, null, 2L, 0);
        when(documentMapper.selectById(11L)).thenReturn(current);

        ProjectDocumentResponse response = service.update(
                11L, new UpdateProjectDocumentRequest(2L, "标题", "[]"), 7L);

        assertEquals(2L, response.version());
        verify(documentMapper, never()).updateContentIfVersionMatches(any(), any(), any(), any(), any());
        verify(revisionMapper, never()).insert(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void unchangedContentDoesNotInvokeSnapshotService() {
        ProjectDocument current = document(11L, 3L, null, 2L, 0);
        when(documentMapper.selectById(11L)).thenReturn(current);
        service.update(11L, new UpdateProjectDocumentRequest(2L, "标题", "[]"), 7L);

        verify(revisionSnapshotService, never()).captureBeforeUpdate(any(), any());
        verify(revisionSnapshotService, never()).captureAfterUpdate(any(), any());
        verify(documentMapper, never()).updateContentIfVersionMatches(any(), any(), any(), any(), any());
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
        verify(documentMapper).updatePosition(11L, 20L, 1);
        var order = inOrder(documentMapper);
        order.verify(documentMapper).lockProjectDocumentMutations(3L);
        order.verify(documentMapper).updatePosition(30L, 20L, 0);
        order.verify(documentMapper).updatePosition(11L, 20L, 1);
    }

    @Test
    void moveToRootExplicitlyPersistsNullParent() {
        ProjectDocument moving = document(34L, 7L, 9L, 1L, 4);
        ProjectDocument management = document(9L, 7L, null, 1L, 3);
        when(documentMapper.selectById(34L)).thenReturn(moving, moving);
        when(documentMapper.selectById(9L)).thenReturn(management);
        when(documentMapper.selectList(any())).thenReturn(List.of(management), List.of());

        service.move(34L, new MoveProjectDocumentRequest(null, 9L), 7L);

        assertNull(moving.getParentDocumentId());
        assertEquals(1, moving.getSortOrder());
        verify(documentMapper).updatePosition(9L, null, 0);
        verify(documentMapper).updatePosition(34L, null, 1);
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

    @Test
    void restorePublishesUpsertForEntireSubtree() {
        ProjectDocument root = document(11L, 3L, null, 2L, 0);
        root.setArchivedAt(java.time.LocalDateTime.now());
        when(documentMapper.selectById(11L)).thenReturn(root, root);
        when(documentMapper.selectSubtreeIds(3L, 11L)).thenReturn(List.of(11L, 12L));
        when(documentMapper.selectList(any())).thenReturn(List.of(root));

        service.restore(11L, 7L);

        verify(eventPublisher).publishEvent(new ProjectContentSemanticIndexRequestedEvent(
                ProjectContentType.DOCUMENT, 11L));
        verify(eventPublisher).publishEvent(new ProjectContentSemanticIndexRequestedEvent(
                ProjectContentType.DOCUMENT, 12L));
    }

    @Test
    void addFavoritePersistsTheCurrentUserDocumentRelation() {
        ProjectDocument document = document(11L, 3L, null, 2L, 0);
        when(documentMapper.selectById(11L)).thenReturn(document);
        when(favoriteMapper.selectCount(any())).thenReturn(0L);

        ProjectDocumentResponse response = service.addFavorite(11L, 7L);

        ArgumentCaptor<ProjectDocumentFavorite> favoriteCaptor = ArgumentCaptor.forClass(ProjectDocumentFavorite.class);
        verify(favoriteMapper).insert(favoriteCaptor.capture());
        assertEquals(7L, favoriteCaptor.getValue().getUserId());
        assertEquals(11L, favoriteCaptor.getValue().getDocumentId());
        assertEquals(true, response.favorited());
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

    private ProjectDocumentRevision revision(Long id, Long documentId, String content) {
        ProjectDocumentRevision revision = new ProjectDocumentRevision();
        revision.setId(id);
        revision.setDocumentId(documentId);
        revision.setTitle("标题");
        revision.setContentJson(content);
        return revision;
    }
}
