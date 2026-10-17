package com.linearlite.server.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linearlite.server.config.CollaborationProperties;
import com.linearlite.server.config.JwtProperties;
import com.linearlite.server.dto.DocumentCollaborationDtos;
import com.linearlite.server.entity.ProjectDocument;
import com.linearlite.server.entity.ProjectDocumentCollabState;
import com.linearlite.server.entity.ProjectDocumentCollabTransition;
import com.linearlite.server.entity.ProjectDocumentCollabTransitionItem;
import com.linearlite.server.entity.User;
import com.linearlite.server.exception.ConflictOperationException;
import com.linearlite.server.exception.CollaborationTransitionFenceException;
import com.linearlite.server.exception.DocumentVersionConflictException;
import com.linearlite.server.exception.ResourceNotFoundException;
import com.linearlite.server.mapper.ProjectDocumentCollabStateMapper;
import com.linearlite.server.mapper.ProjectDocumentCollabTransitionItemMapper;
import com.linearlite.server.mapper.ProjectDocumentCollabTransitionMapper;
import com.linearlite.server.mapper.ProjectDocumentMapper;
import com.linearlite.server.mapper.UserMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Objects;

import static com.linearlite.server.dto.DocumentCollaborationDtos.*;

@Service
public class DocumentCollaborationService {
    private static final long ROOM_TOKEN_TTL_SECONDS = 15 * 60L;
    private static final String TOKEN_PURPOSE = "document-collaboration-room";
    private static final List<String> USER_COLORS = List.of(
            "#7C3AED", "#2563EB", "#0891B2", "#059669", "#D97706", "#DC2626", "#DB2777");

    private final ProjectDocumentMapper documentMapper;
    private final ProjectDocumentCollabStateMapper stateMapper;
    private final UserMapper userMapper;
    private final ProjectAccessGuard accessGuard;
    private final ProjectDocumentAttachmentService attachmentService;
    private final CollaborationNodeClient nodeClient;
    private final CollaborationProperties properties;
    private final JwtProperties jwtProperties;
    private final ObjectMapper objectMapper;
    private final DocumentRevisionSnapshotService revisionSnapshots;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;
    private final ProjectDocumentCollabTransitionMapper transitionMapper;
    private final ProjectDocumentCollabTransitionItemMapper transitionItemMapper;

    public DocumentCollaborationService(
            ProjectDocumentMapper documentMapper,
            ProjectDocumentCollabStateMapper stateMapper,
            UserMapper userMapper,
            ProjectAccessGuard accessGuard,
            ProjectDocumentAttachmentService attachmentService,
            CollaborationNodeClient nodeClient,
            CollaborationProperties properties,
            JwtProperties jwtProperties,
            ObjectMapper objectMapper,
            DocumentRevisionSnapshotService revisionSnapshots,
            ApplicationEventPublisher eventPublisher,
            PlatformTransactionManager transactionManager,
            ProjectDocumentCollabTransitionMapper transitionMapper,
            ProjectDocumentCollabTransitionItemMapper transitionItemMapper) {
        this.documentMapper = documentMapper;
        this.stateMapper = stateMapper;
        this.userMapper = userMapper;
        this.accessGuard = accessGuard;
        this.attachmentService = attachmentService;
        this.nodeClient = nodeClient;
        this.properties = properties;
        this.jwtProperties = jwtProperties;
        this.objectMapper = objectMapper;
        this.revisionSnapshots = revisionSnapshots;
        this.eventPublisher = eventPublisher;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transitionMapper = transitionMapper;
        this.transitionItemMapper = transitionItemMapper;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public SessionResponse createSession(Long documentId, Long userId) {
        return issueSession(documentId, userId, UUID.randomUUID().toString());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public RenewalResponse refreshSession(Long documentId, String sessionId, Long userId) {
        if (sessionId == null || sessionId.isBlank() || sessionId.length() > 128) {
            throw new IllegalArgumentException("sessionId 无效");
        }
        requireActiveDocument(documentId, userId);
        ProjectDocumentCollabState state = stateMapper.selectById(documentId);
        if (state == null) {
            throw new ConflictOperationException("文档协作状态尚未初始化");
        }
        requireNoTransitionFence(state);
        String roomName = roomName(documentId, state.getEpoch());
        Instant expiresAt = Instant.now().plusSeconds(ROOM_TOKEN_TTL_SECONDS);
        String roomToken = issueRoomToken(documentId, userId, state.getEpoch(), roomName, sessionId, expiresAt);
        return new RenewalResponse(sessionId, state.getEpoch(), roomToken, expiresAt);
    }

    private SessionResponse issueSession(Long documentId, Long userId, String sessionId) {
        ProjectDocument document = requireActiveDocument(documentId, userId);
        ProjectDocumentCollabState state = stateMapper.selectById(documentId);
        if (state == null) {
            throw new ConflictOperationException("文档协作状态尚未初始化");
        }
        requireNoTransitionFence(state);
        User user = userMapper.selectById(userId);
        if (user == null) throw new ResourceNotFoundException("用户不存在: " + userId);
        String roomName = roomName(documentId, state.getEpoch());
        Instant expiresAt = Instant.now().plusSeconds(ROOM_TOKEN_TTL_SECONDS);
        String roomToken = issueRoomToken(documentId, userId, state.getEpoch(), roomName, sessionId, expiresAt);
        DocumentCollaborationDtos.User responseUser = new DocumentCollaborationDtos.User(
                userId, user.getUsername(), colorFor(userId));
        return new SessionResponse(documentId, state.getEpoch(), roomName,
                websocketUrl(), roomToken, responseUser, sessionId, expiresAt,
                document.getVersion(), document.getContentFormat(),
                Base64.getEncoder().encodeToString(state.getStateBinary()), state.getCommitSeq());
    }

    public Authorization authorize(AuthorizeRequest request) {
        if (request == null || request.roomToken() == null || request.roomName() == null) {
            throw new IllegalArgumentException("roomToken 与 roomName 不能为空");
        }
        Claims claims = parseRoomToken(request.roomToken());
        Long documentId = claimLong(claims, "documentId");
        Long userId = claimLong(claims, "userId");
        String epoch = claims.get("epoch", String.class);
        String sessionId = claims.get("sessionId", String.class);
        String expectedRoomName = roomName(documentId, epoch);
        if (!expectedRoomName.equals(request.roomName())
                || !expectedRoomName.equals(claims.get("roomName", String.class))) {
            throw new IllegalArgumentException("协作房间与令牌不匹配");
        }
        ProjectDocument document = requireActiveDocument(documentId, userId);
        ProjectDocumentCollabState state = requireState(documentId);
        if (!epoch.equals(state.getEpoch())) throw new ConflictOperationException("协作房间 epoch 已失效");
        requireNoTransitionFence(state);
        User user = userMapper.selectById(userId);
        if (user == null) throw new ResourceNotFoundException("用户不存在: " + userId);
        return new Authorization(documentId, document.getProjectId(), document.getContentFormat(), userId,
                user.getUsername(), colorFor(userId), sessionId, epoch, document.getVersion(), false);
    }

    public StateResponse readState(Long documentId, String epoch) {
        return readState(documentId, epoch, null);
    }

    public StateResponse readState(Long documentId, String epoch, String transitionId) {
        ProjectDocument document = requireDocument(documentId);
        ProjectDocumentCollabState state = requireState(documentId);
        if (!state.getEpoch().equals(epoch)) throw new ConflictOperationException("协作房间 epoch 已失效");
        requireReadTransitionFence(state, transitionId);
        return new StateResponse(Base64.getEncoder().encodeToString(state.getStateBinary()),
                state.getCommitSeq(), document.getVersion(), document.getContentFormat());
    }

    @Transactional(rollbackFor = Exception.class)
    public ArchiveTransitionPrepared prepareArchiveTransition(Long projectId, Long rootDocumentId,
                                                               List<Long> documentIds, String operation) {
        if (!"archive".equals(operation) && !"unarchive".equals(operation) && !"delete".equals(operation)) {
            throw new IllegalArgumentException("文档 transition operation 无效");
        }
        List<Long> ids = documentIds.stream().distinct().sorted().toList();
        if (ids.isEmpty() || ids.size() != documentIds.size()) {
            throw new IllegalArgumentException("文档 transition 子树不能为空且不能包含重复项");
        }
        documentMapper.lockProjectDocumentMutations(projectId);
        List<Long> currentScope = "delete".equals(operation)
                ? documentMapper.selectProjectDocumentIds(projectId)
                : documentMapper.selectSubtreeIds(projectId, rootDocumentId).stream().sorted().toList();
        if (!ids.equals(currentScope)) throw new ConflictOperationException(
                "delete".equals(operation) ? "删除期间项目文档集合发生变化" : "归档期间文档子树发生变化");
        List<ProjectDocument> documents = new ArrayList<>(ids.size());
        List<ProjectDocumentCollabState> states = new ArrayList<>(ids.size());
        for (Long id : ids) {
            ProjectDocument document = documentMapper.selectByIdForUpdate(id);
            if (document == null || !projectId.equals(document.getProjectId())) {
                throw new ResourceNotFoundException("项目文档不存在: " + id);
            }
            boolean isArchived = document.getArchivedAt() != null;
            if (("archive".equals(operation) && isArchived) || ("unarchive".equals(operation) && !isArchived)) {
                throw new ConflictOperationException("文档归档状态已变化: " + id);
            }
            documents.add(document);
        }
        for (Long id : ids) {
            ProjectDocumentCollabState state = stateMapper.selectByDocumentIdForUpdate(id);
            if (state == null) throw new ResourceNotFoundException("项目文档协作状态不存在: " + id);
            requireNoTransitionFence(state);
            states.add(state);
        }
        String transitionId = UUID.randomUUID().toString();
        if (transitionMapper.insertActive(transitionId, projectId, rootDocumentId, operation) != 1) {
            throw new IllegalStateException("无法创建文档 transition");
        }
        List<ArchiveTransitionDocument> prepared = new ArrayList<>(ids.size());
        for (int index = 0; index < ids.size(); index++) {
            Long documentId = ids.get(index);
            ProjectDocument document = documents.get(index);
            ProjectDocumentCollabState state = states.get(index);
            String nextEpoch = UUID.randomUUID().toString();
            if (stateMapper.beginTransitionIfClear(documentId, transitionId) != 1) {
                throw new CollaborationTransitionFenceException(
                        "TRANSITION_FENCE_ACTIVE", "文档已处于归档 transition: " + documentId);
            }
            ProjectDocumentCollabTransitionItem item = new ProjectDocumentCollabTransitionItem();
            item.setTransitionId(transitionId);
            item.setDocumentId(documentId);
            item.setEpoch(state.getEpoch());
            item.setNextEpoch(nextEpoch);
            item.setCommitSeq(state.getCommitSeq());
            item.setVersion(document.getVersion());
            transitionItemMapper.insert(item);
            prepared.add(new ArchiveTransitionDocument(documentId, state.getEpoch(), nextEpoch,
                    state.getCommitSeq(), document.getVersion()));
        }
        return new ArchiveTransitionPrepared(transitionId,
                transitionMapper.selectDeadlineEpochMs(transitionId), List.copyOf(prepared));
    }

    @Transactional(rollbackFor = Exception.class)
    public Long renewArchiveTransition(String transitionId) {
        TransitionContext context = lockTransitionContext(transitionId);
        if (!"ACTIVE".equals(context.transition().getStatus())
                || Boolean.FALSE.equals(transitionMapper.selectActiveAndUnexpired(transitionId))) {
            throw new CollaborationTransitionFenceException("TRANSITION_EXPIRED", "文档 transition 已超时");
        }
        if (transitionMapper.renewActive(transitionId) != 1) {
            throw new CollaborationTransitionFenceException("TRANSITION_EXPIRED", "文档 transition 已超时");
        }
        return transitionMapper.selectDeadlineEpochMs(transitionId);
    }

    @Transactional(rollbackFor = Exception.class)
    public TransitionState finalizeArchiveTransition(String transitionId, List<DrainedTransitionDocument> drained) {
        TransitionContext context = lockTransitionContext(transitionId);
        requireActiveTransition(transitionId, context.transition());
        List<Long> currentSubtree = documentMapper.selectSubtreeIds(context.transition().getProjectId(),
                context.transition().getRootDocumentId()).stream().sorted().toList();
        if (!context.items().stream().map(ProjectDocumentCollabTransitionItem::getDocumentId)
                .toList().equals(currentSubtree)) {
            throw new ConflictOperationException("归档期间文档子树发生变化");
        }
        Map<Long, DrainedTransitionDocument> drainedById = uniqueDrainedDocuments(transitionId, drained,
                context.items().size());
        for (int index = 0; index < context.items().size(); index++) {
            ProjectDocumentCollabTransitionItem item = context.items().get(index);
            ProjectDocument document = context.documents().get(index);
            ProjectDocumentCollabState state = context.states().get(index);
            DrainedTransitionDocument result = drainedById.get(item.getDocumentId());
            if (result == null || !transitionId.equals(result.transitionId())
                    || !item.getEpoch().equals(result.epoch())
                    || !item.getEpoch().equals(state.getEpoch())
                    || !transitionId.equals(state.getTransitionId())
                    || !Objects.equals(result.commitSeq(), state.getCommitSeq())
                    || !Objects.equals(result.version(), document.getVersion())
                    || document.getVersion() < item.getVersion()
                    || state.getCommitSeq() < item.getCommitSeq()) {
                throw new ConflictOperationException("文档 drain 状态与数据库不一致: " + item.getDocumentId());
            }
        }
        String operation = context.transition().getOperation();
        for (int index = 0; index < context.items().size(); index++) {
            ProjectDocumentCollabTransitionItem item = context.items().get(index);
            if (stateMapper.finalizeTransition(item.getDocumentId(), item.getEpoch(),
                    context.states().get(index).getCommitSeq(), transitionId, item.getNextEpoch()) != 1) {
                throw new CollaborationTransitionFenceException("TRANSITION_EXPIRED", "文档 transition finalize CAS 失败");
            }
            int changed = "archive".equals(operation)
                    ? documentMapper.archiveIfActive(item.getDocumentId())
                    : documentMapper.unarchiveIfArchived(item.getDocumentId());
            if (changed != 1) throw new ConflictOperationException("文档归档状态已变化: " + item.getDocumentId());
        }
        if (transitionMapper.finishIfActive(transitionId, "FINALIZED") != 1) {
            throw new CollaborationTransitionFenceException("TRANSITION_EXPIRED", "文档 transition finalize 失败");
        }
        List<Long> documentIds = context.items().stream()
                .map(ProjectDocumentCollabTransitionItem::getDocumentId).toList();
        if ("archive".equals(operation)) {
            eventPublisher.publishEvent(new ProjectContentSemanticDeleteRequestedEvent(
                    ProjectContentType.DOCUMENT, documentIds));
        } else {
            documentIds.forEach(id -> eventPublisher.publishEvent(new ProjectContentSemanticIndexRequestedEvent(
                    ProjectContentType.DOCUMENT, id)));
        }
        return transitionState(transitionId);
    }

    @Transactional(rollbackFor = Exception.class)
    public TransitionState finalizeProjectDeleteTransition(String transitionId,
                                                            List<DrainedTransitionDocument> drained,
                                                            Runnable deleteProjectRows) {
        if (deleteProjectRows == null) throw new IllegalArgumentException("项目删除事务不能为空");
        TransitionContext context = lockTransitionContext(transitionId);
        requireActiveTransition(transitionId, context.transition());
        if (!"delete".equals(context.transition().getOperation())) {
            throw new ConflictOperationException("transition operation 不是项目删除");
        }
        List<Long> currentProjectDocuments = documentMapper.selectProjectDocumentIds(
                context.transition().getProjectId());
        if (!context.items().stream().map(ProjectDocumentCollabTransitionItem::getDocumentId)
                .toList().equals(currentProjectDocuments)) {
            throw new ConflictOperationException("删除期间项目文档集合发生变化");
        }
        Map<Long, DrainedTransitionDocument> drainedById = uniqueDrainedDocuments(transitionId, drained,
                context.items().size());
        List<TransitionDocumentState> releasedDocuments = new ArrayList<>(context.items().size());
        for (int index = 0; index < context.items().size(); index++) {
            ProjectDocumentCollabTransitionItem item = context.items().get(index);
            ProjectDocument document = context.documents().get(index);
            ProjectDocumentCollabState state = context.states().get(index);
            DrainedTransitionDocument result = drainedById.get(item.getDocumentId());
            if (result == null || !item.getEpoch().equals(result.epoch())
                    || !item.getEpoch().equals(state.getEpoch())
                    || !transitionId.equals(state.getTransitionId())
                    || !Objects.equals(result.commitSeq(), state.getCommitSeq())
                    || !Objects.equals(result.version(), document.getVersion())
                    || result.commitSeq() < item.getCommitSeq() || result.version() < item.getVersion()) {
                throw new ConflictOperationException("项目文档 drain 状态与数据库不一致: " + item.getDocumentId());
            }
            if (stateMapper.deleteForTransition(item.getDocumentId(), item.getEpoch(),
                    result.commitSeq(), transitionId) != 1) {
                throw new CollaborationTransitionFenceException("TRANSITION_EXPIRED",
                        "项目删除 transition 状态 CAS 失败");
            }
            if (transitionItemMapper.updateDrained(transitionId, item.getDocumentId(), item.getEpoch(),
                    item.getNextEpoch(), result.commitSeq(), result.version()) != 1) {
                throw new ConflictOperationException("transition release 记录更新失败: " + item.getDocumentId());
            }
            releasedDocuments.add(new TransitionDocumentState(item.getDocumentId(), item.getNextEpoch(),
                    result.commitSeq(), result.version(), true));
        }
        if (transitionMapper.finishIfActive(transitionId, "FINALIZED") != 1) {
            throw new CollaborationTransitionFenceException("TRANSITION_EXPIRED", "项目删除 transition finalize 失败");
        }
        deleteProjectRows.run();
        return new TransitionState(transitionId, "FINALIZED", List.copyOf(releasedDocuments));
    }

    @Transactional(rollbackFor = Exception.class)
    public TransitionState abortArchiveTransition(String transitionId) {
        TransitionContext context = lockTransitionContext(transitionId);
        if ("ACTIVE".equals(context.transition().getStatus())) {
            for (ProjectDocumentCollabTransitionItem item : context.items()) {
                stateMapper.abortTransition(item.getDocumentId(), transitionId);
            }
            transitionMapper.finishIfActive(transitionId, "ABORTED");
        }
        return transitionStateFromContext(transitionId, context);
    }

    @Transactional(rollbackFor = Exception.class)
    public TransitionState transitionState(String transitionId) {
        ProjectDocumentCollabTransition observed = transitionMapper.selectById(transitionId);
        if (observed != null && "delete".equals(observed.getOperation())
                && "FINALIZED".equals(observed.getStatus())) {
            List<TransitionDocumentState> documents = transitionItemMapper.selectByTransition(transitionId).stream()
                    .map(item -> new TransitionDocumentState(item.getDocumentId(), item.getNextEpoch(),
                            item.getCommitSeq(), item.getVersion(), true)).toList();
            return new TransitionState(transitionId, "FINALIZED", documents);
        }
        TransitionContext context = lockTransitionContext(transitionId);
        if ("ACTIVE".equals(context.transition().getStatus())
                && Boolean.TRUE.equals(transitionMapper.selectActiveAndExpired(transitionId))) {
            for (ProjectDocumentCollabTransitionItem item : context.items()) {
                stateMapper.abortTransition(item.getDocumentId(), transitionId);
            }
            transitionMapper.finishIfActive(transitionId, "ABORTED");
        }
        return transitionStateFromContext(transitionId, context);
    }

    @Scheduled(fixedDelay = 10_000)
    public void recoverExpiredArchiveTransitions() {
        for (String transitionId : transitionMapper.selectExpiredTransitionIds()) {
            try {
                TransitionState state = transactionTemplate.execute(status -> transitionState(transitionId));
                if ("ABORTED".equals(state.status())) {
                    List<CollaborationNodeClient.TransitionDocumentRelease> documents = state.documents().stream()
                            .map(item -> new CollaborationNodeClient.TransitionDocumentRelease(
                                    item.documentId(), item.epoch())).toList();
                    nodeClient.releaseTransition(new CollaborationNodeClient.TransitionReleaseRequest(
                            transitionId, state.status(), documents));
                }
            } catch (RuntimeException ignored) {
                // The ACTIVE fence remains in MySQL; the next scheduled pass retries its DB CAS recovery.
            }
        }
    }

    public void validateUpdate(ValidateUpdateRequest request) {
        if (request == null || request.documentId() == null || request.userId() == null) {
            throw new IllegalArgumentException("协作更新缺少文档或用户身份");
        }
        ProjectDocument document = requireActiveDocument(request.documentId(), request.userId());
        ProjectDocumentCollabState state = requireState(request.documentId());
        if (!state.getEpoch().equals(request.epoch())) throw new ConflictOperationException("协作房间 epoch 已失效");
        requireNoTransitionFence(state);
        if (!document.getContentFormat().equals(request.contentFormat())) {
            throw new IllegalArgumentException("协作正文类型与文档类型不一致");
        }
        long contentBytes = request.contentBytes() == null ? -1 : request.contentBytes();
        long maximum = "HTML".equals(request.contentFormat())
                ? properties.getHtmlMaxContentBytes() : properties.getBlocknoteMaxContentBytes();
        if (contentBytes < 0 || contentBytes > maximum) {
            throw new IllegalArgumentException("协作文档正文超过大小限制");
        }
        validateOwnedImageAssets(request.documentId(), request.imageAssetIds());
    }

    @Transactional(rollbackFor = Exception.class)
    public StateCommitted commitState(Long documentId, UpdateStateRequest request) {
        if (request == null || request.epoch() == null || request.expectedCommitSeq() == null
                || request.stateBase64() == null || request.content() == null || request.editorId() == null) {
            throw new IllegalArgumentException("协作状态提交参数不完整");
        }
        ProjectDocument observed = requireActiveDocument(documentId, request.editorId());
        documentMapper.lockProjectDocumentMutations(observed.getProjectId());
        ProjectDocument document = documentMapper.selectByIdForUpdate(documentId);
        ProjectDocumentCollabState state = stateMapper.selectByDocumentIdForUpdate(documentId);
        requireCurrentEpochAndSequence(documentId, state, request.epoch(), request.expectedCommitSeq());
        requireCommitTransitionFence(state, request.transitionId());
        if (!document.getContentFormat().equals(request.contentFormat())) {
            throw new IllegalArgumentException("协作正文类型与文档类型不一致");
        }
        validateContent(documentId, request.contentFormat(), request.content());
        byte[] stateBinary = decodeState(request.stateBase64());
        state.setStateBinary(stateBinary);
        if (stateMapper.updateStateIfMatches(documentId, request.epoch(), request.expectedCommitSeq(), state,
                request.transitionId()) != 1) {
            throw new ConflictOperationException("协作状态已被其他提交更新");
        }
        long nextVersion = document.getVersion() + 1;
        if (documentMapper.updateVersionAndEditorIfMatches(documentId, document.getVersion(),
                nextVersion, request.editorId()) != 1) {
            throwVersionConflict(documentId);
        }
        ProjectDocument saved = documentMapper.selectById(documentId);
        revisionSnapshots.captureForced(saved, request.editorId(), request.content());
        eventPublisher.publishEvent(new ProjectContentSemanticIndexRequestedEvent(
                ProjectContentType.DOCUMENT, documentId));
        return new StateCommitted(request.expectedCommitSeq() + 1, nextVersion);
    }

    @Transactional(rollbackFor = Exception.class)
    public EpochRestored restoreEpoch(Long documentId, RestoreEpochRequest request) {
        if (request == null || request.expectedEpoch() == null || request.expectedCommitSeq() == null
                || request.stateBase64() == null || request.content() == null || request.editorId() == null
                || request.operationId() == null || request.operationId().isBlank()
                || request.title() == null || request.title().isBlank()) {
            throw new IllegalArgumentException("epoch 恢复参数不完整");
        }
        ProjectDocument observed = requireActiveDocument(documentId, request.editorId());
        documentMapper.lockProjectDocumentMutations(observed.getProjectId());
        ProjectDocument document = documentMapper.selectByIdForUpdate(documentId);
        ProjectDocumentCollabState state = stateMapper.selectByDocumentIdForUpdate(documentId);
        requireCurrentEpochAndSequence(documentId, state, request.expectedEpoch(), request.expectedCommitSeq());
        requireNoTransitionFence(state);
        if (!document.getContentFormat().equals(request.contentFormat())) {
            throw new IllegalArgumentException("协作正文类型与文档类型不一致");
        }
        String restoredTitle = requireTitle(request.title());
        validateContent(documentId, request.contentFormat(), request.content());
        byte[] stateBinary = decodeState(request.stateBase64());
        String nextEpoch = UUID.randomUUID().toString();
        state.setEpoch(nextEpoch);
        state.setCommitSeq(1L);
        state.setStateBinary(stateBinary);
        stateMapper.updateById(state);
        long nextVersion = document.getVersion() + 1;
        if (documentMapper.updateCollaborationRestoreIfVersionMatches(documentId, document.getVersion(),
                nextVersion, request.editorId(), restoredTitle) != 1) {
            throwVersionConflict(documentId);
        }
        ProjectDocument saved = documentMapper.selectById(documentId);
        revisionSnapshots.captureForced(saved, request.editorId(), request.content());
        eventPublisher.publishEvent(new ProjectContentSemanticIndexRequestedEvent(
                ProjectContentType.DOCUMENT, documentId));
        return new EpochRestored(nextEpoch, 1L, nextVersion);
    }

    public ProjectDocument updateTitle(Long documentId, String expectedTitle, String title, Long editorId) {
        ProjectDocument observed = requireActiveDocument(documentId, editorId);
        String normalized = requireTitle(title);
        ProjectDocumentCollabState observedState = requireState(documentId);
        requireNoTransitionFence(observedState);
        CollaborationNodeClient.SnapshotResponse snapshot = nodeClient.snapshot(
                documentId, observedState.getEpoch(), true);
        if (snapshot == null || snapshot.content() == null
                || !observedState.getEpoch().equals(snapshot.epoch())
                || !observed.getContentFormat().equals(snapshot.contentFormat())) {
            throw new ConflictOperationException("标题更新时文档协作状态已变化");
        }
        return transactionTemplate.execute(status -> updateTitleTransaction(
                documentId, expectedTitle, normalized, editorId, observed.getProjectId(), snapshot));
    }

    private ProjectDocument updateTitleTransaction(Long documentId, String expectedTitle, String normalized,
                                                     Long editorId, Long projectId,
                                                     CollaborationNodeClient.SnapshotResponse snapshot) {
        documentMapper.lockProjectDocumentMutations(projectId);
        ProjectDocument current = documentMapper.selectByIdForUpdate(documentId);
        ProjectDocumentCollabState state = stateMapper.selectByDocumentIdForUpdate(documentId);
        if (current == null || current.getArchivedAt() != null) {
            throw new ResourceNotFoundException("项目文档不存在: " + documentId);
        }
        accessGuard.requireMember(current.getProjectId(), editorId);
        if (!snapshot.epoch().equals(state.getEpoch()) || snapshot.version() == null
                || !snapshot.version().equals(current.getVersion())
                || !snapshot.contentFormat().equals(current.getContentFormat())) {
            throw new DocumentVersionConflictException(current.getVersion());
        }
        if (expectedTitle == null || !expectedTitle.equals(current.getTitle())) {
            throw new ConflictOperationException("文档标题已被其他编辑者更新");
        }
        requireNoTransitionFence(state);
        if (normalized.equals(current.getTitle())) return current;
        long nextVersion = current.getVersion() + 1;
        if (documentMapper.updateTitleIfMatches(documentId, expectedTitle, normalized,
                current.getVersion(), nextVersion, editorId) != 1) {
            throw new ConflictOperationException("文档标题已被其他编辑者更新");
        }
        ProjectDocument saved = documentMapper.selectById(documentId);
        revisionSnapshots.captureTitleVersion(saved, editorId, snapshot.content());
        eventPublisher.publishEvent(new ProjectContentSemanticIndexRequestedEvent(
                ProjectContentType.DOCUMENT, documentId));
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override public void afterCommit() {
                        nodeClient.publishMetadata(documentId,
                                new CollaborationNodeClient.MetadataEvent(state.getEpoch(), saved.getVersion(),
                                        saved.getTitle(), editorId));
                    }
                });
        return saved;
    }

    @Transactional(rollbackFor = Exception.class)
    public ProjectDocumentCollabState createInitialState(ProjectDocument document, String content) {
        String format = document.getContentFormat();
        validateContent(document.getId(), format, content);
        CollaborationNodeClient.EncodeResponse encoded = nodeClient.encode(format, content);
        if (encoded == null || encoded.stateBase64() == null || encoded.content() == null) {
            throw new IllegalStateException("协作服务未返回 Yjs 初始状态");
        }
        if (!sameProjection(format, content, encoded.content())) {
            throw new IllegalStateException("协作服务编码回读与初始正文不一致");
        }
        ProjectDocumentCollabState state = new ProjectDocumentCollabState();
        state.setDocumentId(document.getId());
        state.setEpoch(UUID.randomUUID().toString());
        state.setCommitSeq(1L);
        state.setStateBinary(decodeState(encoded.stateBase64()));
        stateMapper.insert(state);
        revisionSnapshots.captureForced(document, document.getCreatorId(), content);
        return state;
    }

    public CollaborationNodeClient.SnapshotResponse getSnapshot(Long documentId, boolean flush) {
        ProjectDocumentCollabState state = requireState(documentId);
        requireNoTransitionFence(state);
        return nodeClient.snapshot(documentId, state.getEpoch(), flush);
    }

    public void validateReplacement(Long documentId, String contentFormat, String content, Long userId) {
        ProjectDocument document = requireActiveDocument(documentId, userId);
        requireNoTransitionFence(requireState(documentId));
        if (!document.getContentFormat().equals(contentFormat)) {
            throw new IllegalArgumentException("文档类型不能变更");
        }
        validateContent(documentId, contentFormat, content);
    }

    public ProjectDocumentCollabState requireState(Long documentId) {
        ProjectDocumentCollabState state = stateMapper.selectById(documentId);
        if (state == null) throw new ResourceNotFoundException("项目文档协作状态不存在: " + documentId);
        return state;
    }

    private TransitionContext lockTransitionContext(String transitionId) {
        ProjectDocumentCollabTransition observed = transitionMapper.selectById(transitionId);
        if (observed == null) throw new ResourceNotFoundException("文档 transition 不存在: " + transitionId);
        documentMapper.lockProjectDocumentMutations(observed.getProjectId());
        List<ProjectDocumentCollabTransitionItem> observedItems = transitionItemMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ProjectDocumentCollabTransitionItem>()
                        .eq(ProjectDocumentCollabTransitionItem::getTransitionId, transitionId)
                        .orderByAsc(ProjectDocumentCollabTransitionItem::getDocumentId));
        List<ProjectDocument> documents = new ArrayList<>(observedItems.size());
        List<ProjectDocumentCollabState> states = new ArrayList<>(observedItems.size());
        for (ProjectDocumentCollabTransitionItem item : observedItems) {
            ProjectDocument document = documentMapper.selectByIdForUpdate(item.getDocumentId());
            if (document == null) throw new ResourceNotFoundException("项目文档不存在: " + item.getDocumentId());
            documents.add(document);
        }
        for (ProjectDocumentCollabTransitionItem item : observedItems) {
            ProjectDocumentCollabState state = stateMapper.selectByDocumentIdForUpdate(item.getDocumentId());
            if (state == null) throw new ResourceNotFoundException("项目文档协作状态不存在: " + item.getDocumentId());
            states.add(state);
        }
        List<ProjectDocumentCollabTransitionItem> lockedItems = transitionItemMapper
                .selectByTransitionForUpdate(transitionId);
        ProjectDocumentCollabTransition transition = transitionMapper.selectByIdForUpdate(transitionId);
        return new TransitionContext(transition, lockedItems, documents, states);
    }

    private void requireActiveTransition(String transitionId, ProjectDocumentCollabTransition transition) {
        if (transition == null || !"ACTIVE".equals(transition.getStatus())) {
            throw new CollaborationTransitionFenceException("TRANSITION_FENCE_ACTIVE",
                    "文档 transition 已结束或不存在: " + transitionId);
        }
        if (!Boolean.TRUE.equals(transitionMapper.selectActiveAndUnexpired(transitionId))) {
            throw new CollaborationTransitionFenceException("TRANSITION_EXPIRED", "文档 transition 已超时");
        }
    }

    private Map<Long, DrainedTransitionDocument> uniqueDrainedDocuments(
            String transitionId, List<DrainedTransitionDocument> drained, int expectedCount) {
        if (drained == null || drained.size() != expectedCount) {
            throw new ConflictOperationException("Node drain 文档数量与 transition 不一致");
        }
        Map<Long, DrainedTransitionDocument> result = new java.util.HashMap<>();
        for (DrainedTransitionDocument document : drained) {
            if (document.documentId() == null || !transitionId.equals(document.transitionId())
                    || document.epoch() == null || document.commitSeq() == null || document.version() == null
                    || result.putIfAbsent(document.documentId(), document) != null) {
                throw new ConflictOperationException("Node drain 文档身份无效");
            }
        }
        return result;
    }

    private void requireNoTransitionFence(ProjectDocumentCollabState state) {
        if (state.getTransitionId() != null) {
            throw new CollaborationTransitionFenceException("TRANSITION_FENCE_ACTIVE", "文档正在归档或恢复");
        }
    }

    private void requireReadTransitionFence(ProjectDocumentCollabState state, String transitionId) {
        String activeId = state.getTransitionId();
        if (activeId == null) {
            if (transitionId != null) {
                throw new CollaborationTransitionFenceException("TRANSITION_FENCE_ACTIVE", "文档 transition 已结束");
            }
            return;
        }
        if (transitionId == null || !activeId.equals(transitionId)) {
            throw new CollaborationTransitionFenceException("TRANSITION_FENCE_ACTIVE", "文档正在归档或恢复");
        }
        if (!Boolean.TRUE.equals(transitionMapper.selectActiveAndUnexpired(activeId))) {
            throw new CollaborationTransitionFenceException("TRANSITION_EXPIRED", "文档 transition 已超时");
        }
    }

    private void requireCommitTransitionFence(ProjectDocumentCollabState state, String transitionId) {
        if (state.getTransitionId() == null) {
            if (transitionId != null) {
                throw new CollaborationTransitionFenceException("TRANSITION_FENCE_ACTIVE", "文档 transition 已结束");
            }
            return;
        }
        if (transitionId == null || !state.getTransitionId().equals(transitionId)) {
            throw new CollaborationTransitionFenceException("TRANSITION_FENCE_ACTIVE", "文档正在归档或恢复");
        }
        if (!Boolean.TRUE.equals(transitionMapper.selectActiveAndUnexpired(transitionId))) {
            throw new CollaborationTransitionFenceException("TRANSITION_EXPIRED", "文档 transition 已超时");
        }
    }

    private TransitionState transitionStateFromContext(String transitionId, TransitionContext context) {
        String status = transitionMapper.selectStatus(transitionId);
        List<TransitionDocumentState> documents = new ArrayList<>(context.items().size());
        for (ProjectDocumentCollabTransitionItem item : context.items()) {
            ProjectDocument document = documentMapper.selectById(item.getDocumentId());
            ProjectDocumentCollabState state = stateMapper.selectById(item.getDocumentId());
            documents.add(new TransitionDocumentState(item.getDocumentId(), state.getEpoch(),
                    state.getCommitSeq(), document.getVersion(), document.getArchivedAt() != null));
        }
        return new TransitionState(transitionId, status, List.copyOf(documents));
    }

    private record TransitionContext(ProjectDocumentCollabTransition transition,
                                     List<ProjectDocumentCollabTransitionItem> items,
                                     List<ProjectDocument> documents,
                                     List<ProjectDocumentCollabState> states) { }

    private Claims parseRoomToken(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(signingKey()).build()
                    .parseSignedClaims(token).getPayload();
            if (!TOKEN_PURPOSE.equals(claims.get("purpose", String.class))) {
                throw new IllegalArgumentException("协作令牌用途无效");
            }
            return claims;
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("协作令牌无效或已过期", failure);
        }
    }

    private Long claimLong(Claims claims, String name) {
        Object value = claims.get(name);
        if (value instanceof Number number) return number.longValue();
        if (value instanceof String text) {
            try { return Long.parseLong(text); }
            catch (NumberFormatException failure) { throw new IllegalArgumentException("协作令牌字段无效", failure); }
        }
        throw new IllegalArgumentException("协作令牌字段缺失");
    }

    private String issueRoomToken(Long documentId, Long userId, String epoch, String roomName,
                                  String sessionId, Instant expiresAt) {
        Instant now = Instant.now();
        return Jwts.builder().subject(String.valueOf(userId))
                .claim("purpose", TOKEN_PURPOSE)
                .claim("documentId", documentId)
                .claim("userId", userId)
                .claim("epoch", epoch)
                .claim("roomName", roomName)
                .claim("sessionId", sessionId)
                .issuedAt(java.util.Date.from(now))
                .expiration(java.util.Date.from(expiresAt))
                .signWith(signingKey()).compact();
    }

    private SecretKey signingKey() {
        return Keys.hmacShaKeyFor(jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8));
    }

    private ProjectDocument requireActiveDocument(Long documentId, Long userId) {
        ProjectDocument document = requireDocument(documentId);
        accessGuard.requireMember(document.getProjectId(), userId);
        if (document.getArchivedAt() != null) throw new ResourceNotFoundException("项目文档不存在: " + documentId);
        return document;
    }

    private ProjectDocument requireDocument(Long documentId) {
        ProjectDocument document = documentMapper.selectById(documentId);
        if (document == null) throw new ResourceNotFoundException("项目文档不存在: " + documentId);
        return document;
    }

    private void requireCurrentEpochAndSequence(Long documentId, ProjectDocumentCollabState state,
                                                String expectedEpoch, Long expectedCommitSeq) {
        if (state == null) throw new ResourceNotFoundException("项目文档协作状态不存在: " + documentId);
        if (!expectedEpoch.equals(state.getEpoch())) throw new ConflictOperationException("协作房间 epoch 已失效");
        if (!expectedCommitSeq.equals(state.getCommitSeq())) throw new ConflictOperationException("协作状态已被其他提交更新");
    }

    private void validateContent(Long documentId, String contentFormat, String content) {
        if (content == null) throw new IllegalArgumentException("文档正文不能为空");
        long size = content.getBytes(StandardCharsets.UTF_8).length;
        long maximum = "HTML".equals(contentFormat)
                ? properties.getHtmlMaxContentBytes() : properties.getBlocknoteMaxContentBytes();
        if (size > maximum) throw new IllegalArgumentException("协作文档正文超过大小限制");
        if ("HTML".equals(contentFormat)) {
            HtmlDocumentContent.requireSource(content);
            return;
        }
        if (!"BLOCKNOTE".equals(contentFormat)) throw new IllegalArgumentException("contentFormat 无效");
        try {
            JsonNode blocks = objectMapper.readTree(content);
            if (blocks == null || !blocks.isArray()) throw new IllegalArgumentException("文档正文必须是 BlockNote JSON 数组");
            Set<Long> imageAssetIds = new HashSet<>();
            collectImageAssetIds(blocks, imageAssetIds);
            validateOwnedImageAssets(documentId, new ArrayList<>(imageAssetIds));
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("文档正文必须是有效的 BlockNote JSON 数组");
        }
    }

    private boolean sameProjection(String contentFormat, String expected, String actual) {
        if ("HTML".equals(contentFormat)) return expected.equals(actual);
        try {
            JsonNode expectedTree = objectMapper.readTree(expected);
            JsonNode actualTree = objectMapper.readTree(actual);
            return expectedTree != null && expectedTree.equals(actualTree);
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("文档正文必须是有效 BlockNote JSON 数组", failure);
        }
    }

    private void collectImageAssetIds(JsonNode nodes, Set<Long> ids) {
        if (!nodes.isArray()) return;
        for (JsonNode node : nodes) {
            if ("documentImage".equals(node.path("type").asText())) {
                JsonNode assetId = node.path("props").path("imageAssetId");
                if (!assetId.canConvertToLong() || assetId.longValue() <= 0) {
                    throw new IllegalArgumentException("文档图片必须包含有效 imageAssetId");
                }
                ids.add(assetId.longValue());
            }
            collectImageAssetIds(node.path("children"), ids);
        }
    }

    private void validateOwnedImageAssets(Long documentId, List<Long> assetIds) {
        if (assetIds == null || assetIds.isEmpty()) return;
        Set<Long> owned = attachmentService.listImageAssets(documentId).stream()
                .map(asset -> asset.assetId()).collect(java.util.stream.Collectors.toSet());
        if (assetIds.stream().anyMatch(id -> id == null || !owned.contains(id))) {
            throw new IllegalArgumentException("文档图片资源不属于当前文档");
        }
    }

    private byte[] decodeState(String stateBase64) {
        try {
            byte[] state = Base64.getDecoder().decode(stateBase64);
            if (state.length == 0) throw new IllegalArgumentException("Yjs 状态不能为空");
            return state;
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("Yjs 状态编码无效", failure);
        }
    }

    private void throwVersionConflict(Long documentId) {
        ProjectDocument current = documentMapper.selectById(documentId);
        throw new DocumentVersionConflictException(current == null ? null : current.getVersion());
    }

    private String requireTitle(String title) {
        if (title == null || title.isBlank()) throw new IllegalArgumentException("文档标题不能为空");
        String normalized = title.trim();
        if (normalized.length() > 256) throw new IllegalArgumentException("文档标题不能超过 256 个字符");
        return normalized;
    }

    private static String roomName(Long documentId, String epoch) {
        if (documentId == null || epoch == null) throw new IllegalArgumentException("协作房间身份不完整");
        return "doc:" + documentId + ":epoch:" + epoch;
    }

    private String websocketUrl() {
        String configured = properties.getWebsocketUrl();
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException("COLLABORATION_WEBSOCKET_URL 必须配置为浏览器可访问地址");
        }
        java.net.URI uri = java.net.URI.create(configured);
        if (!("ws".equalsIgnoreCase(uri.getScheme()) || "wss".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null) {
            throw new IllegalStateException("COLLABORATION_WEBSOCKET_URL 必须使用可访问的 ws:// 或 wss:// 地址");
        }
        return configured;
    }

    private static String colorFor(Long userId) {
        return USER_COLORS.get(Math.floorMod(userId.hashCode(), USER_COLORS.size()));
    }
}
