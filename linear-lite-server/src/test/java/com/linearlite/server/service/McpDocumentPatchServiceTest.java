package com.linearlite.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linearlite.server.entity.McpDocumentPatchOperation;
import com.linearlite.server.entity.ProjectDocument;
import com.linearlite.server.exception.ConflictOperationException;
import com.linearlite.server.exception.DocumentVersionConflictException;
import com.linearlite.server.mapper.McpDocumentPatchOperationMapper;
import com.linearlite.server.mapper.ProjectDocumentMapper;
import com.linearlite.server.mapper.ProjectDocumentRevisionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class McpDocumentPatchServiceTest {
    @Mock private ProjectDocumentMapper documentMapper;
    @Mock private McpDocumentPatchOperationMapper operationMapper;
    @Mock private ProjectDocumentRevisionMapper revisionMapper;
    @Mock private ProjectAccessGuard accessGuard;
    @Mock private DocumentCollaborationService collaborationService;
    @Mock private CollaborationNodeClient nodeClient;

    private McpDocumentPatchService service;

    @BeforeEach
    void setUp() {
        service = new McpDocumentPatchService(documentMapper, operationMapper, accessGuard,
                collaborationService, nodeClient, new ObjectMapper(), revisionMapper);
    }

    @Test
    void replaysStoredResponseBeforeExpectedVersionCheck() throws Exception {
        when(documentMapper.selectById(15L)).thenReturn(document(6L));
        McpDocumentPatchOperation previous = new McpDocumentPatchOperation();
        previous.setRequestSha256("same");
        previous.setResponseJson("{\"documentId\":15,\"version\":6,\"changed\":true,\"revisionId\":81}");
        when(operationMapper.selectForUpdate(15L, 7L, "op-1")).thenReturn(previous);

        var result = service.patch(15L, 5L, "op-1", "same", null, 7L,
                (content, id) -> { throw new AssertionError("重放不得重算正文"); });

        assertEquals(6L, result.path("version").asLong());
        verify(collaborationService, never()).getSnapshot(15L, true);
    }

    @Test
    void rejectsIdempotencyKeyReuseWithDifferentRequestHash() {
        when(documentMapper.selectById(15L)).thenReturn(document(5L));
        McpDocumentPatchOperation previous = new McpDocumentPatchOperation();
        previous.setRequestSha256("old");
        when(operationMapper.selectForUpdate(15L, 7L, "op-1")).thenReturn(previous);

        assertThrows(ConflictOperationException.class, () -> service.patch(
                15L, 5L, "op-1", "new", null, 7L, (content, id) -> content));
    }

    @Test
    void changedPatchUsesCollaborationCommandAndStoresItsVersion() {
        String changed = "[{\"id\":\"p\",\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"changed\"}],\"children\":[]}]";
        when(documentMapper.selectById(15L)).thenReturn(document(5L));
        when(operationMapper.selectForUpdate(15L, 7L, "op-1")).thenReturn(null);
        when(collaborationService.getSnapshot(15L, true)).thenReturn(new CollaborationNodeClient.SnapshotResponse(
                "epoch-1", 5L, "BLOCKNOTE", "[]"));
        when(nodeClient.command(org.mockito.ArgumentMatchers.eq(15L), any(CollaborationNodeClient.CommandRequest.class)))
                .thenReturn(new CollaborationNodeClient.CommandResponse(
                        "epoch-1", 2L, 6L, "BLOCKNOTE", changed, "标题"));
        when(revisionMapper.selectOne(any())).thenReturn(null);

        var result = service.patch(15L, 5L, "op-1", "hash", "标题", 7L,
                (content, id) -> changed);

        assertEquals(6L, result.path("version").asLong());
        assertEquals(true, result.path("changed").asBoolean());
        verify(collaborationService).validateReplacement(15L, "BLOCKNOTE", changed, 7L);
        verify(nodeClient).command(org.mockito.ArgumentMatchers.eq(15L), any(CollaborationNodeClient.CommandRequest.class));
        verify(operationMapper).insert(any(McpDocumentPatchOperation.class));
    }

    @Test
    void noOpDoesNotCallNodeCommandOrAdvanceVersion() {
        when(documentMapper.selectById(15L)).thenReturn(document(5L));
        when(operationMapper.selectForUpdate(15L, 7L, "op-1")).thenReturn(null);
        when(collaborationService.getSnapshot(15L, true)).thenReturn(new CollaborationNodeClient.SnapshotResponse(
                "epoch-1", 5L, "BLOCKNOTE", "[]"));
        when(revisionMapper.selectOne(any())).thenReturn(null);

        var result = service.patch(15L, 5L, "op-1", "hash", null, 7L, (content, id) -> "[ ]");

        assertFalse(result.path("changed").asBoolean());
        assertEquals(5L, result.path("version").asLong());
        verify(nodeClient, never()).command(any(), any());
        verify(operationMapper).insert(any(McpDocumentPatchOperation.class));
    }

    @Test
    void staleVersionDoesNotResolveContentOrReserveOperationId() {
        when(documentMapper.selectById(15L)).thenReturn(document(5L));
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
        document.setContentFormat("BLOCKNOTE");
        document.setLastEditorId(7L);
        return document;
    }
}
