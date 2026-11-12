package com.linearlite.server.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linearlite.server.dto.*;
import com.linearlite.server.entity.ProjectDocument;
import com.linearlite.server.entity.Task;
import com.linearlite.server.entity.TaskExecutionAttempt;
import com.linearlite.server.exception.ConflictOperationException;
import com.linearlite.server.exception.ForbiddenOperationException;
import com.linearlite.server.mapper.ProjectDocumentMapper;
import com.linearlite.server.mapper.TaskExecutionAttemptMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CollaborationContextServiceTest {
    private final CollaborationContextReadAuthorizationService reads = mock(CollaborationContextReadAuthorizationService.class);
    private final TaskExecutionAccessService access = mock(TaskExecutionAccessService.class);
    private final TaskExecutionOperationService operations = mock(TaskExecutionOperationService.class);
    private final TaskExecutionAttemptMapper attempts = mock(TaskExecutionAttemptMapper.class);
    private final ProjectDocumentMapper documentMapper = mock(ProjectDocumentMapper.class);
    private final ProjectDocumentQueryService documents = mock(ProjectDocumentQueryService.class);
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final CollaborationContextService service = new CollaborationContextService(
            reads, access, operations, attempts, documentMapper, documents, json);
    private final CollaborationBatchRequest batch = new CollaborationBatchRequest("batch-secret", 30L, "device", "/work");
    private final CollaborationAttemptCommand command = new CollaborationAttemptCommand(20L, "claim-secret", 2L, "host", "agent", "adopt-1");
    private final CollaborationContextRef documentRef = new CollaborationContextRef("DOCUMENT", 11L, 3L, null, null);
    private final CollaborationContextRef fileRef = new CollaborationContextRef("PROJECT_FILE", null, null,
            "docs/guide.md", "sha256:" + "a".repeat(64));
    private TaskExecutionAttempt attempt;
    private TaskExecutionAccessService.OwnedAttempt owned;

    @BeforeEach void setup() {
        Task task = new Task(); task.setId(10L); task.setProjectId(6L); task.setInputRevision(2L);
        attempt = new TaskExecutionAttempt(); attempt.setId(20L); attempt.setTaskId(10L);
        attempt.setInputRevision(2L); attempt.setCheckpoints(json.createArrayNode());
        var context = new CollaborationBatchAuthorizationService.BatchContext("batch", 1L, 6L, 30L, 1L,
                "device", List.of(10L), Instant.now().plusSeconds(300), 11L, "host", 1L,
                Instant.now().plusSeconds(30), Instant.now().plusSeconds(300), 5, 2);
        owned = new TaskExecutionAccessService.OwnedAttempt(context, task, attempt);
    }

    private void writable() {
        when(access.requireOwnedAttempt(1L, batch, 10L, command)).thenReturn(owned);
        when(access.checkWritable(owned, command)).thenReturn(new TaskExecutionAccessService.WriteCheck(true, null, attempt));
    }

    private ProjectDocument document(long project, long version) {
        ProjectDocument d = new ProjectDocument(); d.setId(11L); d.setProjectId(project); d.setVersion(version); return d;
    }

    private void adopted(CollaborationContextRef... refs) {
        var point = json.createObjectNode(); point.set("contextRefs", json.valueToTree(List.of(refs)));
        attempt.setCheckpoints(json.createArrayNode().add(point));
    }

    @Test void adoptionWritesAttemptFactOnceAndDoesNotMutateTaskInput() throws Exception {
        writable(); when(documentMapper.selectById(11L)).thenReturn(document(6, 3));
        when(attempts.appendCheckpoint(eq(20L), eq(0), anyString())).thenReturn(1);
        assertTrue(service.adopt(1L, batch, 10L, command, List.of(documentRef, fileRef)).path("accepted").asBoolean());
        ArgumentCaptor<String> fact = ArgumentCaptor.forClass(String.class);
        verify(attempts).appendCheckpoint(eq(20L), eq(0), fact.capture());
        JsonNode saved = json.readTree(fact.getValue());
        assertEquals(2, saved.path("contextRefs").size());
        assertEquals(2L, owned.task().getInputRevision());
        verify(attempts, never()).requestStopForTask(anyLong(), any(), anyString());
        JsonNode original = json.createObjectNode().put("accepted", true);
        when(operations.findReplay(eq(6L), eq(1L), eq("adopt-1"), eq("context_adopt"), any())).thenReturn(original);
        assertEquals(original, service.adopt(1L, batch, 10L, command, List.of(documentRef, fileRef)));
        verify(attempts, times(1)).appendCheckpoint(anyLong(), anyInt(), anyString());
    }

    @Test void documentOwnershipAndVersionAreCheckedUnderAttemptAuthorization() {
        writable(); when(documentMapper.selectById(11L)).thenReturn(document(8, 3));
        assertThrows(ForbiddenOperationException.class, () -> service.adopt(1L, batch, 10L, command, List.of(documentRef)));
        when(documentMapper.selectById(11L)).thenReturn(document(6, 4));
        assertThrows(ConflictOperationException.class, () -> service.adopt(1L, batch, 10L, command, List.of(documentRef)));
        verify(attempts, never()).appendCheckpoint(anyLong(), anyInt(), anyString());
    }

    @Test void refsEnforceSourceFieldsAndContentDigest() {
        var badFile = new CollaborationContextRef("PROJECT_FILE", null, null, "docs/a.md", "123");
        var mixed = new CollaborationContextRef("DOCUMENT", 11L, 3L, "docs/a.md", null);
        assertThrows(IllegalArgumentException.class, () -> service.adopt(1L, batch, 10L, command, List.of(badFile)));
        assertThrows(IllegalArgumentException.class, () -> service.adopt(1L, batch, 10L, command, List.of(mixed)));
        verifyNoInteractions(access);
    }

    @Test void changedDocumentMarksOnlyConsumersAndFileNeedsHostProof() throws Exception {
        adopted(documentRef, fileRef);
        TaskExecutionAttempt unrelated = new TaskExecutionAttempt(); unrelated.setId(21L); unrelated.setCheckpoints(json.createArrayNode());
        when(attempts.selectByProjectForUpdate(6L)).thenReturn(List.of(attempt, unrelated));
        when(attempts.appendCheckpoint(eq(20L), eq(1), anyString())).thenReturn(1);
        service.onDocumentChanged(new CollaborationDocumentChangedEvent(6L,
                List.of(new CollaborationDocumentChangedEvent.Change(11L, 4L, true)), 1L));
        ArgumentCaptor<String> fact = ArgumentCaptor.forClass(String.class);
        verify(attempts).appendCheckpoint(eq(20L), eq(1), fact.capture());
        assertEquals("CONTEXT_REVIEW_REQUIRED", json.readTree(fact.getValue()).path("kind").asText());
        verify(attempts, never()).appendCheckpoint(eq(21L), anyInt(), anyString());
        verify(attempts, never()).requestStopForTask(anyLong(), any(), anyString());
        when(documentMapper.selectById(11L)).thenReturn(document(6, 4));
        var status = service.project(attempt, 6L, Map.of());
        assertEquals("REVIEW_REQUIRED", status.get(0).status());
        assertEquals("PENDING_CONFIRMATION", status.get(1).status());
        assertEquals("CURRENT", service.project(attempt, 6L, Map.of("docs/guide.md", fileRef.contentVersion())).get(1).status());
        assertEquals("REVIEW_REQUIRED", service.project(attempt, 6L, Map.of("docs/guide.md", "sha256:" + "b".repeat(64))).get(1).status());
    }

    @Test void evidenceRequiresCurrentAttemptAdoption() {
        assertThrows(IllegalArgumentException.class, () -> CollaborationContextService.requireAdopted(attempt, List.of(documentRef)));
        adopted(documentRef, fileRef);
        assertThrows(IllegalArgumentException.class, () -> CollaborationContextService.requireAllAdopted(attempt, List.of(documentRef)));
        assertDoesNotThrow(() -> CollaborationContextService.requireAllAdopted(attempt, List.of(documentRef, fileRef)));
    }

    @Test void readRejectsCrossProjectBeforeSnapshot() {
        when(reads.authorize(1L, batch, 10L, command)).thenReturn(6L);
        when(documents.requireDocument(11L, 1L)).thenReturn(document(8, 3));
        assertThrows(ForbiddenOperationException.class, () -> service.readDocument(1L, batch, 10L, command, 11L));
        verify(documents, never()).getDocument(anyLong(), anyLong());
    }

    @Test void snapshotReadDoesNotHoldAuthorizationTransaction() throws Exception {
        var read = CollaborationContextService.class.getMethod("readDocument", Long.class,
                CollaborationBatchRequest.class, Long.class, CollaborationAttemptCommand.class, Long.class);
        assertNull(read.getAnnotation(org.springframework.transaction.annotation.Transactional.class));
        var authorize = CollaborationContextReadAuthorizationService.class.getMethod("authorize", Long.class,
                CollaborationBatchRequest.class, Long.class, CollaborationAttemptCommand.class);
        assertNotNull(authorize.getAnnotation(org.springframework.transaction.annotation.Transactional.class));
    }
}
