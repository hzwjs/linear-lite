package com.linearlite.server.service;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linearlite.server.dto.CreateProjectDocumentRequest;
import com.linearlite.server.dto.ProjectDocumentResponse;
import com.linearlite.server.dto.UpdateProjectDocumentRequest;
import com.linearlite.server.dto.DocumentCollaborationDtos;
import com.linearlite.server.entity.ProjectDocument;
import com.linearlite.server.entity.ProjectDocumentCollabState;
import com.linearlite.server.entity.ProjectDocumentFavorite;
import com.linearlite.server.entity.ProjectDocumentRevision;
import com.linearlite.server.mapper.ProjectDocumentFavoriteMapper;
import com.linearlite.server.mapper.ProjectDocumentMapper;
import com.linearlite.server.mapper.ProjectDocumentRevisionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectDocumentCommandServiceTest {
    @Mock private ProjectDocumentMapper documentMapper;
    @Mock private ProjectDocumentFavoriteMapper favoriteMapper;
    @Mock private ProjectDocumentRevisionMapper revisionMapper;
    @Mock private ProjectAccessGuard accessGuard;
    @Mock private DocumentCollaborationService collaborationService;
    @Mock private CollaborationNodeClient nodeClient;
    @Mock private ProjectDocumentQueryService queryService;
    @Mock private ApplicationEventPublisher eventPublisher;

    private ProjectDocumentCommandService service;

    @BeforeEach
    void setUp() {
        service = new ProjectDocumentCommandService(documentMapper, favoriteMapper, revisionMapper, accessGuard,
                collaborationService, nodeClient, queryService, new ObjectMapper(), eventPublisher);
    }

    @Test
    void createStoresMetadataAndYjsStateWithoutActiveBodyField() {
        when(documentMapper.selectList(any())).thenReturn(List.of());
        when(documentMapper.insert(any(ProjectDocument.class))).thenAnswer(invocation -> {
            ProjectDocument document = invocation.getArgument(0);
            document.setId(11L);
            return 1;
        });
        when(documentMapper.selectById(11L)).thenAnswer(invocation -> document(11L, 4L, 1L));

        service.create(4L, new CreateProjectDocumentRequest(null, " 文档 ", null, null, null, "BLOCKNOTE"), 7L);

        ArgumentCaptor<ProjectDocument> inserted = ArgumentCaptor.forClass(ProjectDocument.class);
        verify(documentMapper).insert(inserted.capture());
        assertEquals("文档", inserted.getValue().getTitle());
        assertEquals(1L, inserted.getValue().getVersion());
        verify(collaborationService).createInitialState(inserted.getValue(), "[]");
    }

    @Test
    void updateUsesNodeCommandInsteadOfWritingActiveBody() {
        ProjectDocument current = document(11L, 4L, 1L);
        when(documentMapper.selectById(11L)).thenReturn(current);
        when(collaborationService.getSnapshot(11L, true)).thenReturn(new CollaborationNodeClient.SnapshotResponse(
                "epoch-1", 1L, "BLOCKNOTE", "[]"));
        when(queryService.getDocument(11L, 7L)).thenReturn(null);

        service.update(11L, new UpdateProjectDocumentRequest(1L, "标题", "[{\"type\":\"paragraph\"}]",
                "BLOCKNOTE"), 7L);

        verify(collaborationService).validateReplacement(11L, "BLOCKNOTE", "[{\"type\":\"paragraph\"}]", 7L);
        verify(nodeClient).command(any(Long.class), any(CollaborationNodeClient.CommandRequest.class));
    }

    @Test
    void archiveFlushesAndClosesRoomsBeforeStartingDatabaseMutation() {
        when(documentMapper.selectById(11L)).thenReturn(document(11L, 4L, 1L));
        when(documentMapper.selectSubtreeIds(4L, 11L)).thenReturn(List.of(11L, 12L));
        var prepared = new DocumentCollaborationDtos.ArchiveTransitionPrepared("transition-1", 1_900_000_000_000L,
                List.of(new DocumentCollaborationDtos.ArchiveTransitionDocument(11L, "epoch-11", "next-11", 3L, 1L),
                        new DocumentCollaborationDtos.ArchiveTransitionDocument(12L, "epoch-12", "next-12", 5L, 1L)));
        when(collaborationService.prepareArchiveTransition(4L, 11L, List.of(11L, 12L), "archive"))
                .thenReturn(prepared);
        when(nodeClient.closeRoom(any(Long.class), any(CollaborationNodeClient.CloseRoomRequest.class)))
                .thenAnswer(invocation -> {
                    Long id = invocation.getArgument(0);
                    String epoch = id == 11L ? "epoch-11" : "epoch-12";
                    long seq = id == 11L ? 3L : 5L;
                    return new CollaborationNodeClient.CloseRoomResponse(epoch, seq, 2L, "transition-1");
                });
        var transitionState = new DocumentCollaborationDtos.TransitionState("transition-1", "FINALIZED", List.of(
                new DocumentCollaborationDtos.TransitionDocumentState(11L, "next-11", 1L, 2L, true),
                new DocumentCollaborationDtos.TransitionDocumentState(12L, "next-12", 1L, 2L, true)));
        when(collaborationService.finalizeArchiveTransition(eq("transition-1"), any())).thenReturn(transitionState);

        service.archive(11L, 7L);

        InOrder order = inOrder(collaborationService, nodeClient);
        order.verify(collaborationService).prepareArchiveTransition(4L, 11L, List.of(11L, 12L), "archive");
        order.verify(nodeClient, org.mockito.Mockito.times(2)).closeRoom(any(Long.class),
                any(CollaborationNodeClient.CloseRoomRequest.class));
        order.verify(collaborationService).finalizeArchiveTransition(eq("transition-1"), any());
        verify(nodeClient).releaseTransition(any(CollaborationNodeClient.TransitionReleaseRequest.class));
    }

    @Test
    void restoreAdvancesEpochBeforeReopeningArchivedSubtree() {
        ProjectDocument archivedRoot = document(11L, 4L, 1L);
        archivedRoot.setArchivedAt(java.time.LocalDateTime.now());
        when(documentMapper.selectById(11L)).thenReturn(archivedRoot);
        when(documentMapper.selectSubtreeIds(4L, 11L)).thenReturn(List.of(11L, 12L));
        when(collaborationService.prepareArchiveTransition(4L, 11L, List.of(11L, 12L), "unarchive"))
                .thenReturn(new DocumentCollaborationDtos.ArchiveTransitionPrepared("transition-2", 1_900_000_000_000L,
                        List.of(new DocumentCollaborationDtos.ArchiveTransitionDocument(11L, "epoch-11", "next-11", 3L, 1L),
                                new DocumentCollaborationDtos.ArchiveTransitionDocument(12L, "epoch-12", "next-12", 5L, 1L))));
        when(nodeClient.closeRoom(any(Long.class), any(CollaborationNodeClient.CloseRoomRequest.class)))
                .thenAnswer(invocation -> {
                    Long id = invocation.getArgument(0);
                    return new CollaborationNodeClient.CloseRoomResponse(
                            id == 11L ? "epoch-11" : "epoch-12", id == 11L ? 3L : 5L, 1L, "transition-2");
                });
        when(collaborationService.finalizeArchiveTransition(eq("transition-2"), any()))
                .thenReturn(new DocumentCollaborationDtos.TransitionState("transition-2", "FINALIZED", List.of(
                        new DocumentCollaborationDtos.TransitionDocumentState(11L, "next-11", 1L, 2L, false),
                        new DocumentCollaborationDtos.TransitionDocumentState(12L, "next-12", 1L, 2L, false))));

        service.restore(11L, 7L);

        verify(collaborationService).prepareArchiveTransition(4L, 11L, List.of(11L, 12L), "unarchive");
        verify(collaborationService).finalizeArchiveTransition(eq("transition-2"), any());
        verify(nodeClient).releaseTransition(any(CollaborationNodeClient.TransitionReleaseRequest.class));
    }

    private ProjectDocument document(Long id, Long projectId, Long version) {
        ProjectDocument document = new ProjectDocument();
        document.setId(id);
        document.setProjectId(projectId);
        document.setTitle("标题");
        document.setContentFormat("BLOCKNOTE");
        document.setVersion(version);
        document.setCreatorId(7L);
        document.setLastEditorId(7L);
        return document;
    }

    private ProjectDocumentCollabState state(Long id, String epoch, Long commitSeq) {
        ProjectDocumentCollabState state = new ProjectDocumentCollabState();
        state.setDocumentId(id);
        state.setEpoch(epoch);
        state.setCommitSeq(commitSeq);
        return state;
    }
}
