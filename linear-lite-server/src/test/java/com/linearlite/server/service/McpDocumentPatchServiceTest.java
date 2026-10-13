package com.linearlite.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linearlite.server.entity.McpDocumentPatchOperation;
import com.linearlite.server.entity.ProjectDocument;
import com.linearlite.server.entity.ProjectDocumentRevision;
import com.linearlite.server.exception.ConflictOperationException;
import com.linearlite.server.exception.DocumentVersionConflictException;
import com.linearlite.server.mapper.McpDocumentPatchOperationMapper;
import com.linearlite.server.mapper.ProjectDocumentMapper;
import com.linearlite.server.mapper.ProjectDocumentRevisionMapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpDocumentPatchServiceTest {
    private final ProjectDocumentMapper documentMapper = mock(ProjectDocumentMapper.class);
    private final McpDocumentPatchOperationMapper operationMapper = mock(McpDocumentPatchOperationMapper.class);
    private final ProjectDocumentRevisionMapper revisionMapper = mock(ProjectDocumentRevisionMapper.class);
    private final ProjectAccessGuard accessGuard = mock(ProjectAccessGuard.class);
    private final DocumentRevisionSnapshotService snapshotService = mock(DocumentRevisionSnapshotService.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final McpDocumentPatchService service = new McpDocumentPatchService(
            documentMapper, operationMapper, revisionMapper, accessGuard, snapshotService,
            new ObjectMapper(), eventPublisher);

    @Test
    void replaysStoredResponseBeforeExpectedVersionCheck() throws Exception {
        ProjectDocument current = document(6L);
        when(documentMapper.selectById(15L)).thenReturn(current);
        when(documentMapper.selectByIdForUpdate(15L)).thenReturn(current);
        McpDocumentPatchOperation previous = new McpDocumentPatchOperation();
        previous.setRequestSha256("same");
        previous.setResponseJson("{\"documentId\":15,\"version\":6,\"changed\":true,\"revisionId\":81}");
        when(operationMapper.selectForUpdate(15L, 7L, "op-1")).thenReturn(previous);

        var result = service.patch(15L, 5L, "op-1", "same", null, 7L,
                (content, id) -> { throw new AssertionError("重放不得重算正文"); });

        assertEquals(6L, result.path("version").asLong());
        verify(documentMapper, never()).updateContentIfVersionMatches(any(), any(), any(), any(), any());
    }

    @Test
    void rejectsIdempotencyKeyReuseWithDifferentRequestHash() {
        ProjectDocument current = document(5L);
        when(documentMapper.selectById(15L)).thenReturn(current);
        when(documentMapper.selectByIdForUpdate(15L)).thenReturn(current);
        McpDocumentPatchOperation previous = new McpDocumentPatchOperation();
        previous.setRequestSha256("old");
        when(operationMapper.selectForUpdate(15L, 7L, "op-1")).thenReturn(previous);

        assertThrows(ConflictOperationException.class, () -> service.patch(
                15L, 5L, "op-1", "new", null, 7L, (content, id) -> content));
    }

    @Test
    void writesChangedBodyRevisionAndIdempotencyResultInOneFlow() {
        ProjectDocument current = document(5L);
        ProjectDocument saved = document(6L);
        saved.setContent("[{\"id\":\"p\",\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"changed\"}],\"children\":[]}]");
        when(documentMapper.selectById(15L)).thenReturn(current, saved);
        when(documentMapper.selectByIdForUpdate(15L)).thenReturn(current);
        when(operationMapper.selectForUpdate(15L, 7L, "op-1")).thenReturn(null);
        when(documentMapper.updateContentIfVersionMatches(15L, 5L, "标题", saved.getContent(), 7L)).thenReturn(1);
        when(snapshotService.captureForced(saved, 7L)).thenReturn(91L);

        var result = service.patch(15L, 5L, "op-1", "hash", "标题", 7L,
                (content, id) -> saved.getContent());

        assertEquals(6L, result.path("version").asLong());
        assertEquals(91L, result.path("revisionId").asLong());
        assertEquals(true, result.path("changed").asBoolean());
        verify(operationMapper).insert(any(McpDocumentPatchOperation.class));
        verify(eventPublisher).publishEvent(any(ProjectContentSemanticIndexRequestedEvent.class));
    }

    @Test
    void noOpDoesNotAdvanceVersionOrCreateSnapshot() {
        ProjectDocument current = document(5L);
        when(documentMapper.selectById(15L)).thenReturn(current);
        when(documentMapper.selectByIdForUpdate(15L)).thenReturn(current);
        when(operationMapper.selectForUpdate(15L, 7L, "op-1")).thenReturn(null);
        when(revisionMapper.selectOne(any())).thenReturn(null);

        var result = service.patch(15L, 5L, "op-1", "hash", null, 7L,
                (content, id) -> "[ { \"id\":\"p\", \"type\":\"paragraph\", \"content\":[], \"children\":[] } ]");

        assertFalse(result.path("changed").asBoolean());
        assertEquals(5L, result.path("version").asLong());
        assertEquals(true, result.path("revisionId").isNull());
        verify(documentMapper, never()).updateContentIfVersionMatches(any(), any(), any(), any(), any());
        verify(snapshotService, never()).captureForced(any(), any());
        verify(operationMapper).insert(any(McpDocumentPatchOperation.class));
    }

    @Test
    void staleVersionDoesNotResolveContentOrReserveOperationId() {
        ProjectDocument current = document(5L);
        when(documentMapper.selectById(15L)).thenReturn(current);
        when(documentMapper.selectByIdForUpdate(15L)).thenReturn(current);
        when(operationMapper.selectForUpdate(15L, 7L, "op-1")).thenReturn(null);

        assertThrows(DocumentVersionConflictException.class, () -> service.patch(
                15L, 4L, "op-1", "hash", null, 7L, (content, id) -> {
                    throw new AssertionError("版本冲突不得运行正文工厂");
                }));

        verify(operationMapper, never()).insert(any(McpDocumentPatchOperation.class));
    }

    private static ProjectDocument document(long version) {
        ProjectDocument document = new ProjectDocument();
        document.setId(15L);
        document.setProjectId(4L);
        document.setVersion(version);
        document.setTitle("标题");
        document.setContent("[{\"id\":\"p\",\"type\":\"paragraph\",\"content\":[],\"children\":[]}]");
        document.setContentFormat("BLOCKNOTE");
        document.setLastEditorId(7L);
        return document;
    }
}
