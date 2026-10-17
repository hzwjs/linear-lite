package com.linearlite.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linearlite.server.config.CollaborationProperties;
import com.linearlite.server.config.JwtProperties;
import com.linearlite.server.dto.DocumentCollaborationDtos.AuthorizeRequest;
import com.linearlite.server.entity.ProjectDocument;
import com.linearlite.server.entity.ProjectDocumentCollabState;
import com.linearlite.server.entity.ProjectDocumentCollabTransition;
import com.linearlite.server.entity.ProjectDocumentCollabTransitionItem;
import com.linearlite.server.entity.User;
import com.linearlite.server.exception.ConflictOperationException;
import com.linearlite.server.exception.DocumentVersionConflictException;
import com.linearlite.server.exception.ResourceNotFoundException;
import com.linearlite.server.mapper.ProjectDocumentCollabStateMapper;
import com.linearlite.server.mapper.ProjectDocumentCollabTransitionItemMapper;
import com.linearlite.server.mapper.ProjectDocumentCollabTransitionMapper;
import com.linearlite.server.mapper.ProjectDocumentMapper;
import com.linearlite.server.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;

@ExtendWith(MockitoExtension.class)
class DocumentCollaborationServiceTest {
    @Mock private ProjectDocumentMapper documentMapper;
    @Mock private ProjectDocumentCollabStateMapper stateMapper;
    @Mock private ProjectDocumentCollabTransitionMapper transitionMapper;
    @Mock private ProjectDocumentCollabTransitionItemMapper transitionItemMapper;
    @Mock private UserMapper userMapper;
    @Mock private ProjectAccessGuard accessGuard;
    @Mock private ProjectDocumentAttachmentService attachmentService;
    @Mock private CollaborationNodeClient nodeClient;
    @Mock private DocumentRevisionSnapshotService revisionSnapshots;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private PlatformTransactionManager transactionManager;

    private DocumentCollaborationService service;

    @BeforeEach
    void setUp() {
        JwtProperties jwt = new JwtProperties();
        jwt.setSecret("document-collaboration-test-signing-key-2026-long-enough");
        CollaborationProperties properties = new CollaborationProperties();
        properties.setWebsocketUrl("wss://docs.example.test/collaboration");
        service = new DocumentCollaborationService(documentMapper, stateMapper, userMapper, accessGuard,
                attachmentService, nodeClient, properties, jwt, new ObjectMapper(),
                revisionSnapshots, eventPublisher, transactionManager, transitionMapper, transitionItemMapper);
    }

    @Test
    void rejectsRoomTokenAfterArchiveRestoreChangesEpoch() {
        ProjectDocument document = document(false);
        ProjectDocumentCollabState oldState = state("epoch-old", 1L);
        when(documentMapper.selectById(11L)).thenReturn(document, document);
        when(stateMapper.selectById(11L)).thenReturn(oldState);
        when(userMapper.selectById(7L)).thenReturn(user());

        var session = service.createSession(11L, 7L);
        oldState.setEpoch("epoch-new");

        when(stateMapper.selectById(11L)).thenReturn(oldState);
        assertThrows(ConflictOperationException.class, () -> service.authorize(
                new AuthorizeRequest(session.roomToken(), session.roomName())));
    }

    @Test
    void internalStateReadAllowsArchivedDocumentForUnarchiveRoomClose() {
        ProjectDocument document = document(true);
        ProjectDocumentCollabState state = state("epoch-archived", 4L);
        state.setStateBinary(new byte[]{1, 2, 3});
        when(documentMapper.selectById(11L)).thenReturn(document);
        when(stateMapper.selectById(11L)).thenReturn(state);

        var result = service.readState(11L, "epoch-archived");

        assertEquals(4L, result.commitSeq());
        assertEquals("AQID", result.stateBase64());
    }

    @Test
    void archivedDocumentCannotCreateSession() {
        when(documentMapper.selectById(11L)).thenReturn(document(true));
        assertThrows(ResourceNotFoundException.class, () -> service.createSession(11L, 7L));
    }

    @Test
    void refreshSessionKeepsSessionIdAndIssuesFreshToken() {
        when(documentMapper.selectById(11L)).thenReturn(document(false));
        ProjectDocumentCollabState state = state("epoch-current", 8L);
        when(stateMapper.selectById(11L)).thenReturn(state);

        var refreshed = service.refreshSession(11L, "session-current", 7L);

        assertEquals("session-current", refreshed.sessionId());
        assertNotEquals("", refreshed.roomToken());
        assertEquals("epoch-current", refreshed.epoch());
        org.junit.jupiter.api.Assertions.assertNotNull(refreshed.expiresAt());
        verifyNoInteractions(userMapper);
    }

    @Test
    void createSessionReturnsTheYjsCheckpointAndDocumentVersion() {
        ProjectDocument document = document(false);
        ProjectDocumentCollabState state = state("epoch-current", 8L);
        state.setStateBinary(new byte[]{4, 5, 6});
        when(documentMapper.selectById(11L)).thenReturn(document);
        when(stateMapper.selectById(11L)).thenReturn(state);
        when(userMapper.selectById(7L)).thenReturn(user());

        var session = service.createSession(11L, 7L);

        assertEquals("epoch-current", session.epoch());
        assertEquals("BLOCKNOTE", session.contentFormat());
        assertEquals("BAUG", session.stateBase64());
        assertEquals(8L, session.commitSeq());
        assertEquals(document.getVersion(), session.version());
    }

    @Test
    void ordinaryStateReadIsRejectedDuringTransitionFence() {
        ProjectDocument document = document(false);
        ProjectDocumentCollabState state = state("epoch-current", 8L);
        state.setStateBinary(new byte[]{1});
        state.setTransitionId("transition-current");
        when(documentMapper.selectById(11L)).thenReturn(document);
        when(stateMapper.selectById(11L)).thenReturn(state);
        when(transitionMapper.selectActiveAndUnexpired("transition-current")).thenReturn(true);

        assertThrows(com.linearlite.server.exception.CollaborationTransitionFenceException.class,
                () -> service.readState(11L, "epoch-current"));
        assertEquals(8L, service.readState(11L, "epoch-current", "transition-current").commitSeq());
    }

    @Test
    void expiredDrainReadIsRejectedAndRestartRecoveryAbortsDurableFence() {
        ProjectDocument document = document(false);
        ProjectDocumentCollabState collabState = state("epoch-current", 8L);
        collabState.setStateBinary(new byte[]{1});
        collabState.setTransitionId("transition-expired");
        when(documentMapper.selectById(11L)).thenReturn(document);
        when(stateMapper.selectById(11L)).thenReturn(collabState);
        when(transitionMapper.selectActiveAndUnexpired("transition-expired")).thenReturn(false);
        var expired = assertThrows(com.linearlite.server.exception.CollaborationTransitionFenceException.class,
                () -> service.readState(11L, "epoch-current", "transition-expired"));
        assertEquals("TRANSITION_EXPIRED", expired.getErrorType());

        ProjectDocumentCollabTransition transition = new ProjectDocumentCollabTransition();
        transition.setTransitionId("transition-expired");
        transition.setProjectId(4L);
        transition.setStatus("ACTIVE");
        ProjectDocumentCollabTransitionItem item = new ProjectDocumentCollabTransitionItem();
        item.setTransitionId("transition-expired");
        item.setDocumentId(11L);
        item.setEpoch("epoch-current");
        when(transitionMapper.selectExpiredTransitionIds()).thenReturn(java.util.List.of("transition-expired"));
        when(transitionMapper.selectById("transition-expired")).thenReturn(transition);
        when(transitionMapper.selectByIdForUpdate("transition-expired")).thenReturn(transition);
        when(transitionItemMapper.selectList(any())).thenReturn(java.util.List.of(item));
        when(transitionItemMapper.selectByTransitionForUpdate("transition-expired"))
                .thenReturn(java.util.List.of(item));
        when(documentMapper.selectByIdForUpdate(11L)).thenReturn(document);
        when(stateMapper.selectByDocumentIdForUpdate(11L)).thenReturn(collabState);
        when(transitionMapper.selectActiveAndExpired("transition-expired")).thenReturn(true);
        when(transitionMapper.selectStatus("transition-expired")).thenReturn("ABORTED");

        service.recoverExpiredArchiveTransitions();

        verify(stateMapper).abortTransition(11L, "transition-expired");
        verify(transitionMapper).finishIfActive("transition-expired", "ABORTED");
        verify(nodeClient).releaseTransition(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void deletedTransitionRecoveryUsesRetainedTerminalItemWithoutLoadingDeletedRows() {
        ProjectDocumentCollabTransition transition = new ProjectDocumentCollabTransition();
        transition.setTransitionId("transition-deleted");
        transition.setOperation("delete");
        transition.setStatus("FINALIZED");
        ProjectDocumentCollabTransitionItem item = new ProjectDocumentCollabTransitionItem();
        item.setTransitionId("transition-deleted");
        item.setDocumentId(11L);
        item.setEpoch("epoch-old");
        item.setNextEpoch("epoch-next");
        item.setCommitSeq(9L);
        item.setVersion(5L);
        when(transitionMapper.selectById("transition-deleted")).thenReturn(transition);
        when(transitionItemMapper.selectByTransition("transition-deleted")).thenReturn(java.util.List.of(item));

        var state = service.transitionState("transition-deleted");

        assertEquals("FINALIZED", state.status());
        assertEquals("epoch-next", state.documents().get(0).epoch());
        assertEquals(9L, state.documents().get(0).commitSeq());
        org.mockito.Mockito.verifyNoInteractions(documentMapper, stateMapper);
    }

    @Test
    void titleUpdateRejectsBodyVersionChangeAfterNodeSnapshot() {
        ProjectDocument document = document(false);
        ProjectDocumentCollabState state = state("epoch-current", 8L);
        when(documentMapper.selectById(11L)).thenReturn(document, document);
        when(stateMapper.selectById(11L)).thenReturn(state);
        when(documentMapper.selectByIdForUpdate(11L)).thenReturn(document);
        when(stateMapper.selectByDocumentIdForUpdate(11L)).thenReturn(state);
        when(nodeClient.snapshot(11L, "epoch-current", true)).thenReturn(
                new CollaborationNodeClient.SnapshotResponse(
                        "epoch-current", 2L, "BLOCKNOTE", "[]"));

        DocumentVersionConflictException conflict = assertThrows(DocumentVersionConflictException.class,
                () -> service.updateTitle(11L, "文档", "新标题", 7L));

        assertEquals(3L, conflict.getCurrentVersion());
        verify(nodeClient).snapshot(11L, "epoch-current", true);
    }

    private ProjectDocument document(boolean archived) {
        ProjectDocument document = new ProjectDocument();
        document.setId(11L);
        document.setProjectId(4L);
        document.setTitle("文档");
        document.setContentFormat("BLOCKNOTE");
        document.setVersion(3L);
        if (archived) document.setArchivedAt(LocalDateTime.now());
        return document;
    }

    private ProjectDocumentCollabState state(String epoch, Long commitSeq) {
        ProjectDocumentCollabState state = new ProjectDocumentCollabState();
        state.setDocumentId(11L);
        state.setEpoch(epoch);
        state.setCommitSeq(commitSeq);
        state.setStateBinary(new byte[0]);
        return state;
    }

    private User user() {
        User user = new User();
        user.setId(7L);
        user.setUsername("alice");
        return user;
    }
}
