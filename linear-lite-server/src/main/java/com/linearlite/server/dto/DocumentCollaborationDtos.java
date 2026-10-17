package com.linearlite.server.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class DocumentCollaborationDtos {
    private DocumentCollaborationDtos() { }

    public record SessionResponse(Long documentId, String epoch, String roomName, String websocketUrl,
                                  String roomToken, User user, String sessionId, Instant expiresAt,
                                  Long version, String contentFormat, String stateBase64, Long commitSeq) { }
    public record RenewalResponse(String sessionId, String epoch, String roomToken, Instant expiresAt) { }
    public record RefreshSessionRequest(String sessionId) { }
    public record User(Long id, String name, String color) { }
    public record AuthorizeRequest(String roomToken, String roomName) { }
    public record Authorization(Long documentId, Long projectId, String contentFormat, Long userId,
                                String userName, String color, String sessionId, String epoch,
                                Long version, boolean archived) { }
    public record StateResponse(String stateBase64, Long commitSeq, Long version, String contentFormat) { }
    public record ValidateUpdateRequest(Long documentId, String epoch, Long userId,
                                        List<Long> imageAssetIds, String contentFormat,
                                        Long contentBytes) { }
    public record UpdateStateRequest(String epoch, Long expectedCommitSeq, String stateBase64,
                                     String content, String contentFormat, Long editorId,
                                     Map<String, Long> counters, String transitionId) { }
    public record StateCommitted(Long commitSeq, Long version) { }
    public record RestoreEpochRequest(String expectedEpoch, Long expectedCommitSeq,
                                      String stateBase64, String content, String contentFormat,
                                      Long editorId, String operationId, String title) { }
    public record EpochRestored(String epoch, Long commitSeq, Long version) { }
    public record TitleUpdateRequest(String expectedTitle, String title) { }
    public record ArchiveTransitionDocument(Long documentId, String epoch, String nextEpoch,
                                            Long commitSeq, Long version) { }
    public record ArchiveTransitionPrepared(String transitionId, Long deadlineEpochMs,
                                             List<ArchiveTransitionDocument> documents) { }
    public record DrainedTransitionDocument(Long documentId, String transitionId, String epoch,
                                            Long commitSeq, Long version) { }
    public record TransitionDocumentState(Long documentId, String epoch, Long commitSeq,
                                          Long version, boolean archived) { }
    public record TransitionState(String transitionId, String status,
                                  List<TransitionDocumentState> documents) { }
    public record TransitionReleaseRequest(String transitionId, String status,
                                           List<TransitionDocumentState> documents) { }
}
