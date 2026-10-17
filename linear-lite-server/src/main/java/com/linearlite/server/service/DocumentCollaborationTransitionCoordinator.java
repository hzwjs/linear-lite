package com.linearlite.server.service;

import com.linearlite.server.dto.DocumentCollaborationDtos;
import com.linearlite.server.exception.ConflictOperationException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/** Coordinates durable transition fencing with Node room draining. */
public final class DocumentCollaborationTransitionCoordinator {
    private final DocumentCollaborationService collaborationService;
    private final CollaborationNodeClient nodeClient;

    public DocumentCollaborationTransitionCoordinator(DocumentCollaborationService collaborationService,
                                                      CollaborationNodeClient nodeClient) {
        this.collaborationService = collaborationService;
        this.nodeClient = nodeClient;
    }

    public DocumentCollaborationDtos.TransitionState execute(
            DocumentCollaborationDtos.ArchiveTransitionPrepared prepared, String operation,
            Function<List<DocumentCollaborationDtos.DrainedTransitionDocument>,
                    DocumentCollaborationDtos.TransitionState> finalizer) {
        AtomicLong deadlineEpochMs = new AtomicLong(prepared.deadlineEpochMs());
        AtomicReference<RuntimeException> renewalFailure = new AtomicReference<>();
        ScheduledExecutorService renewer = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "document-transition-renewal");
            thread.setDaemon(true);
            return thread;
        });
        ScheduledFuture<?> renewalTask = renewer.scheduleAtFixedRate(() -> {
            try {
                deadlineEpochMs.set(collaborationService.renewArchiveTransition(prepared.transitionId()));
            } catch (RuntimeException failure) {
                renewalFailure.compareAndSet(null, failure);
            }
        }, 30, 30, TimeUnit.SECONDS);
        try {
            List<DocumentCollaborationDtos.DrainedTransitionDocument> drained = new ArrayList<>();
            for (DocumentCollaborationDtos.ArchiveTransitionDocument document : prepared.documents()) {
                if (renewalFailure.get() != null) throw renewalFailure.get();
                CollaborationNodeClient.CloseRoomResponse closed = nodeClient.closeRoom(document.documentId(),
                        new CollaborationNodeClient.CloseRoomRequest(document.epoch(), document.nextEpoch(),
                                operation, prepared.transitionId(), deadlineEpochMs.get()));
                if (closed == null || !document.epoch().equals(closed.epoch())
                        || !prepared.transitionId().equals(closed.transitionId())
                        || closed.commitSeq() == null || closed.version() == null) {
                    throw new ConflictOperationException("协作房间 drain 状态与文档状态不一致: "
                            + document.documentId());
                }
                drained.add(new DocumentCollaborationDtos.DrainedTransitionDocument(
                        document.documentId(), closed.transitionId(), closed.epoch(),
                        closed.commitSeq(), closed.version()));
            }
            renewalTask.cancel(false);
            renewer.shutdown();
            if (!renewer.awaitTermination(5, TimeUnit.SECONDS)) {
                throw new ConflictOperationException("文档 transition 租约续期尚未结束");
            }
            if (renewalFailure.get() != null) throw renewalFailure.get();
            DocumentCollaborationDtos.TransitionState result = finalizer.apply(List.copyOf(drained));
            releaseTransitionBestEffort(result);
            return result;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            ConflictOperationException failure = new ConflictOperationException("文档 transition 被中断");
            failure.addSuppressed(interrupted);
            abortTransitionBestEffort(prepared.transitionId(), failure);
            throw failure;
        } catch (RuntimeException failure) {
            abortTransitionBestEffort(prepared.transitionId(), failure);
            throw failure;
        } finally {
            renewalTask.cancel(false);
            renewer.shutdownNow();
        }
    }

    private void abortTransitionBestEffort(String transitionId, RuntimeException originalFailure) {
        try {
            releaseTransitionBestEffort(collaborationService.abortArchiveTransition(transitionId));
        } catch (RuntimeException abortFailure) {
            originalFailure.addSuppressed(abortFailure);
        }
    }

    private void releaseTransitionBestEffort(DocumentCollaborationDtos.TransitionState state) {
        try {
            List<CollaborationNodeClient.TransitionDocumentRelease> documents = state.documents().stream()
                    .map(item -> new CollaborationNodeClient.TransitionDocumentRelease(
                            item.documentId(), item.epoch())).toList();
            nodeClient.releaseTransition(new CollaborationNodeClient.TransitionReleaseRequest(
                    state.transitionId(), state.status(), documents));
        } catch (RuntimeException ignored) {
            // Node resolves a lost notification through the durable transition status endpoint.
        }
    }
}
