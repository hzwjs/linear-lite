package com.linearlite.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linearlite.server.dto.CreateProjectDocumentRequest;
import com.linearlite.server.dto.DocumentCollaborationDtos;
import com.linearlite.server.dto.MoveProjectDocumentRequest;
import com.linearlite.server.dto.ProjectDocumentResponse;
import com.linearlite.server.dto.UpdateProjectDocumentRequest;
import com.linearlite.server.entity.ProjectDocument;
import com.linearlite.server.entity.ProjectDocumentFavorite;
import com.linearlite.server.entity.ProjectDocumentRevision;
import com.linearlite.server.exception.DocumentVersionConflictException;
import com.linearlite.server.exception.ConflictOperationException;
import com.linearlite.server.exception.ResourceNotFoundException;
import com.linearlite.server.mapper.ProjectDocumentMapper;
import com.linearlite.server.mapper.ProjectDocumentFavoriteMapper;
import com.linearlite.server.mapper.ProjectDocumentRevisionMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.LongFunction;

@Service
public class ProjectDocumentCommandService {
    private static final String EMPTY_BLOCK_NOTE_DOCUMENT = "[]";

    private final ProjectDocumentMapper documentMapper;
    private final ProjectDocumentFavoriteMapper favoriteMapper;
    private final ProjectDocumentRevisionMapper revisionMapper;
    private final ProjectAccessGuard projectAccessGuard;
    private final DocumentCollaborationService collaborationService;
    private final CollaborationNodeClient nodeClient;
    private final ProjectDocumentQueryService queryService;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;
    private final DocumentCollaborationTransitionCoordinator transitionCoordinator;

    @Autowired
    public ProjectDocumentCommandService(
            ProjectDocumentMapper documentMapper,
            ProjectDocumentFavoriteMapper favoriteMapper,
            ProjectDocumentRevisionMapper revisionMapper,
            ProjectAccessGuard projectAccessGuard,
            DocumentCollaborationService collaborationService,
            CollaborationNodeClient nodeClient,
            ProjectDocumentQueryService queryService,
            ObjectMapper objectMapper,
            ApplicationEventPublisher eventPublisher,
            PlatformTransactionManager transactionManager) {
        this.documentMapper = documentMapper;
        this.favoriteMapper = favoriteMapper;
        this.revisionMapper = revisionMapper;
        this.projectAccessGuard = projectAccessGuard;
        this.collaborationService = collaborationService;
        this.nodeClient = nodeClient;
        this.queryService = queryService;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transitionCoordinator = new DocumentCollaborationTransitionCoordinator(collaborationService, nodeClient);
    }

    public ProjectDocumentResponse create(Long projectId, CreateProjectDocumentRequest request, Long userId) {
        ProjectDocument document = executeCreateTransaction(projectId, request, userId, null);
        return toResponse(document, userId);
    }

    /** Creates the row and initial collaboration state atomically, then reads the Node projection after commit. */
    public ProjectDocumentResponse createWithContentFactory(
            Long projectId, CreateProjectDocumentRequest request, Long userId, LongFunction<String> contentFactory) {
        if (contentFactory == null) {
            throw new IllegalArgumentException("正文工厂不能为空");
        }
        ProjectDocument document = executeCreateTransaction(projectId, request, userId, contentFactory);
        return toResponse(document, userId);
    }

    private ProjectDocument executeCreateTransaction(
            Long projectId, CreateProjectDocumentRequest request, Long userId, LongFunction<String> contentFactory) {
        return Objects.requireNonNull(transactionTemplate.execute(
                status -> createInternal(projectId, request, userId, contentFactory)));
    }

    private ProjectDocument createInternal(
            Long projectId, CreateProjectDocumentRequest request, Long userId, LongFunction<String> contentFactory) {
        projectAccessGuard.requireMember(projectId, userId);
        String format = requireFormat(request == null ? null : request.contentFormat());
        if (contentFactory != null && !"BLOCKNOTE".equals(format)) {
            throw new IllegalArgumentException("HTML 正文不得经过 BlockNote 转换");
        }
        documentMapper.lockProjectDocumentMutations(projectId);
        String externalSource = normalizeExternalSource(request == null ? null : request.externalSource());
        String externalSourceId = normalizeExternalSourceId(
                request == null ? null : request.externalSourceId(), externalSource);
        if (externalSource != null) {
            ProjectDocument existing = documentMapper.selectOne(new LambdaQueryWrapper<ProjectDocument>()
                    .eq(ProjectDocument::getProjectId, projectId)
                    .eq(ProjectDocument::getExternalSource, externalSource)
                    .eq(ProjectDocument::getExternalSourceId, externalSourceId));
            if (existing != null) {
                requireActive(existing);
                if (!format.equals(existing.getContentFormat())) {
                    throw new IllegalArgumentException("外部绑定文档类型与请求不一致");
                }
                return existing;
            }
        }
        String title = requireTitle(request == null ? null : request.title());
        String content = contentFactory == null
                ? "BLOCKNOTE".equals(format) && request.content() == null
                        ? EMPTY_BLOCK_NOTE_DOCUMENT
                        : requireContent(format, request.content())
                : EMPTY_BLOCK_NOTE_DOCUMENT;
        Long parentId = request == null ? null : request.parentDocumentId();
        if (parentId != null) {
            ProjectDocument parent = requireDocument(parentId);
            requireSameProject(projectId, parent);
            requireActive(parent);
        }

        ProjectDocument document = new ProjectDocument();
        document.setProjectId(projectId);
        document.setParentDocumentId(parentId);
        document.setExternalSource(externalSource);
        document.setExternalSourceId(externalSourceId);
        document.setTitle(title);
        document.setContentFormat(format);
        // 新建文档始终排在列表第一位：现有兄弟整体后移一位，新文档占据 0 号位。
        // 项目行锁已串行化同项目创建，兄弟排序变更与插入在同一事务内生效。
        for (ProjectDocument sibling : loadSiblings(projectId, parentId, false)) {
            sibling.setSortOrder(sibling.getSortOrder() + 1);
            documentMapper.updatePosition(sibling.getId(), sibling.getParentDocumentId(), sibling.getSortOrder());
        }
        document.setSortOrder(0);
        document.setVersion(1L);
        document.setCreatorId(userId);
        document.setLastEditorId(userId);
        documentMapper.insert(document);
        if (contentFactory != null) content = requireBlockNoteJson(contentFactory.apply(document.getId()));
        collaborationService.createInitialState(document, content);
        publishUpsert(document.getId());
        return requireDocument(document.getId());
    }

    public ProjectDocumentResponse update(Long documentId, UpdateProjectDocumentRequest request, Long userId) {
        return updateInternal(documentId, request, userId, null);
    }

    /** Resolves attachment-backed content only after access and expectedVersion checks pass. */
    public ProjectDocumentResponse updateWithContentFactory(
            Long documentId, UpdateProjectDocumentRequest request, Long userId, LongFunction<String> contentFactory) {
        if (contentFactory == null) {
            throw new IllegalArgumentException("正文工厂不能为空");
        }
        return updateInternal(documentId, request, userId, contentFactory);
    }

    private ProjectDocumentResponse updateInternal(
            Long documentId, UpdateProjectDocumentRequest request, Long userId, LongFunction<String> contentFactory) {
        ProjectDocument current = requireAccessibleActiveDocument(documentId, userId);
        if (request == null || request.expectedVersion() == null) {
            throw new IllegalArgumentException("expectedVersion 不能为空");
        }
        String format = requireFormat(request.contentFormat());
        if (!format.equals(current.getContentFormat())) {
            throw new IllegalArgumentException("文档类型不能变更");
        }
        if (contentFactory != null && !"BLOCKNOTE".equals(format)) {
            throw new IllegalArgumentException("HTML 正文不得经过 BlockNote 转换");
        }
        String title = requireTitle(request.title());
        String content = contentFactory == null ? requireContent(format, request.content()) : null;
        if (!Objects.equals(current.getVersion(), request.expectedVersion())) {
            throwVersionConflict(documentId);
        }
        if (contentFactory != null) {
            content = requireBlockNoteJson(contentFactory.apply(documentId));
        }
        collaborationService.validateReplacement(documentId, format, content, userId);
        CollaborationNodeClient.SnapshotResponse snapshot = collaborationService.getSnapshot(documentId, true);
        if (!Objects.equals(snapshot.version(), request.expectedVersion())) {
            throw new DocumentVersionConflictException(snapshot.version());
        }
        if (Objects.equals(current.getTitle(), title) && Objects.equals(snapshot.content(), content)) {
            return queryService.getDocument(documentId, userId);
        }
        nodeClient.command(documentId, new CollaborationNodeClient.CommandRequest(
                java.util.UUID.randomUUID().toString(), userId, snapshot.epoch(), request.expectedVersion(),
                "replace", format, content, title));
        return queryService.getDocument(documentId, userId);
    }

    @Transactional(rollbackFor = Exception.class)
    public void move(Long documentId, MoveProjectDocumentRequest request, Long userId) {
        ProjectDocument document = requireAccessibleActiveDocument(documentId, userId);
        documentMapper.lockProjectDocumentMutations(document.getProjectId());
        document = requireAccessibleActiveDocument(documentId, userId);
        if (request == null) {
            throw new IllegalArgumentException("移动参数不能为空");
        }
        Long targetParentId = request.parentDocumentId();
        if (targetParentId != null) {
            ProjectDocument targetParent = requireDocument(targetParentId);
            requireSameProject(document.getProjectId(), targetParent);
            requireActive(targetParent);
            // 后代集合由递归 SQL 给出，禁止把节点移动到自身或任一后代。
            if (documentMapper.selectSubtreeIds(document.getProjectId(), documentId).contains(targetParentId)) {
                throw new IllegalArgumentException("不能把文档移动到自身或其子文档中");
            }
        }

        Long previousSiblingId = request.previousSiblingId();
        if (documentId.equals(previousSiblingId)) {
            throw new IllegalArgumentException("前一个兄弟文档不能是当前文档");
        }
        if (previousSiblingId != null) {
            ProjectDocument previousSibling = requireDocument(previousSiblingId);
            requireSameProject(document.getProjectId(), previousSibling);
            requireActive(previousSibling);
            if (!Objects.equals(targetParentId, previousSibling.getParentDocumentId())) {
                throw new IllegalArgumentException("前一个兄弟文档不属于目标父文档");
            }
        }

        Long sourceParentId = document.getParentDocumentId();
        List<ProjectDocument> targetSiblings = new ArrayList<>(loadSiblings(document.getProjectId(), targetParentId, false));
        targetSiblings.removeIf(item -> item.getId().equals(documentId));
        int targetIndex = 0;
        if (previousSiblingId != null) {
            targetIndex = indexOf(targetSiblings, previousSiblingId) + 1;
        }
        document.setParentDocumentId(targetParentId);
        targetSiblings.add(targetIndex, document);
        persistOrder(targetSiblings);

        if (!Objects.equals(sourceParentId, targetParentId)) {
            persistOrder(loadSiblings(document.getProjectId(), sourceParentId, false).stream()
                    .filter(item -> !item.getId().equals(documentId))
                    .toList());
        }
    }

    public void archive(Long documentId, Long userId) {
        ProjectDocument document = requireAccessibleActiveDocument(documentId, userId);
        List<Long> subtreeIds = documentMapper.selectSubtreeIds(document.getProjectId(), documentId)
                .stream().sorted().toList();
        runArchiveTransition(document.getProjectId(), documentId, subtreeIds, "archive");
    }

    public void restore(Long documentId, Long userId) {
        ProjectDocument document = requireAccessibleDocument(documentId, userId);
        if (document.getArchivedAt() == null) {
            throw new IllegalArgumentException("文档未归档");
        }
        if (document.getParentDocumentId() != null) {
            ProjectDocument parent = requireDocument(document.getParentDocumentId());
            if (parent.getArchivedAt() != null
                    && !documentMapper.selectSubtreeIds(document.getProjectId(), documentId).contains(parent.getId())) {
                throw new IllegalArgumentException("请先恢复父文档");
            }
        }
        List<Long> subtreeIds = documentMapper.selectSubtreeIds(document.getProjectId(), documentId)
                .stream().sorted().toList();
        runArchiveTransition(document.getProjectId(), documentId, subtreeIds, "unarchive");
    }

    private void runArchiveTransition(Long projectId, Long rootDocumentId, List<Long> subtreeIds,
                                      String operation) {
        DocumentCollaborationDtos.ArchiveTransitionPrepared prepared =
                collaborationService.prepareArchiveTransition(projectId, rootDocumentId, subtreeIds, operation);
        transitionCoordinator.execute(prepared, operation, drained ->
                collaborationService.finalizeArchiveTransition(prepared.transitionId(), drained));
    }

    public ProjectDocumentResponse restoreRevision(
            Long documentId, Long revisionId, Long expectedVersion, Long userId) {
        ProjectDocument current = requireAccessibleActiveDocument(documentId, userId);
        if (expectedVersion == null) {
            throw new IllegalArgumentException("expectedVersion 不能为空");
        }
        ProjectDocumentRevision revision = revisionMapper.selectOne(new LambdaQueryWrapper<ProjectDocumentRevision>()
                .eq(ProjectDocumentRevision::getDocumentId, documentId)
                .eq(ProjectDocumentRevision::getId, revisionId));
        if (revision == null) {
            throw new ResourceNotFoundException("文档修订版不存在: " + revisionId);
        }
        if (!current.getContentFormat().equals(revision.getContentFormat())) {
            throw new IllegalArgumentException("历史版本类型与文档不一致");
        }
        String revisionContent = requireContent(revision.getContentFormat(), revision.getContent());
        collaborationService.validateReplacement(documentId, revision.getContentFormat(), revisionContent, userId);
        CollaborationNodeClient.SnapshotResponse snapshot = collaborationService.getSnapshot(documentId, true);
        if (!Objects.equals(snapshot.version(), expectedVersion)) {
            throw new DocumentVersionConflictException(snapshot.version());
        }
        nodeClient.command(documentId, new CollaborationNodeClient.CommandRequest(
                java.util.UUID.randomUUID().toString(), userId, snapshot.epoch(), expectedVersion,
                "restore", revision.getContentFormat(), revisionContent, revision.getTitle()));
        return queryService.getDocument(documentId, userId);
    }

    @Transactional(rollbackFor = Exception.class)
    public ProjectDocumentResponse addFavorite(Long documentId, Long userId) {
        ProjectDocument document = requireAccessibleActiveDocument(documentId, userId);
        Long count = favoriteMapper.selectCount(new LambdaQueryWrapper<ProjectDocumentFavorite>()
                .eq(ProjectDocumentFavorite::getUserId, userId)
                .eq(ProjectDocumentFavorite::getDocumentId, documentId));
        if (count == 0) {
            ProjectDocumentFavorite favorite = new ProjectDocumentFavorite();
            favorite.setUserId(userId);
            favorite.setDocumentId(documentId);
            favoriteMapper.insert(favorite);
        }
        return queryService.toResponse(document, true);
    }

    @Transactional(rollbackFor = Exception.class)
    public ProjectDocumentResponse removeFavorite(Long documentId, Long userId) {
        ProjectDocument document = requireAccessibleActiveDocument(documentId, userId);
        favoriteMapper.delete(new LambdaQueryWrapper<ProjectDocumentFavorite>()
                .eq(ProjectDocumentFavorite::getUserId, userId)
                .eq(ProjectDocumentFavorite::getDocumentId, documentId));
        return queryService.toResponse(document, false);
    }

    private ProjectDocumentResponse toResponse(ProjectDocument document, Long userId) {
        boolean favorited = favoriteMapper.selectCount(new LambdaQueryWrapper<ProjectDocumentFavorite>()
                .eq(ProjectDocumentFavorite::getUserId, userId)
                .eq(ProjectDocumentFavorite::getDocumentId, document.getId())) > 0;
        return queryService.toResponse(document, favorited);
    }

    private ProjectDocument requireAccessibleActiveDocument(Long documentId, Long userId) {
        ProjectDocument document = requireAccessibleDocument(documentId, userId);
        requireActive(document);
        return document;
    }

    private ProjectDocument requireAccessibleDocument(Long documentId, Long userId) {
        ProjectDocument document = requireDocument(documentId);
        projectAccessGuard.requireMember(document.getProjectId(), userId);
        return document;
    }

    private ProjectDocument requireDocument(Long documentId) {
        ProjectDocument document = documentMapper.selectById(documentId);
        if (document == null) {
            throw new ResourceNotFoundException("项目文档不存在: " + documentId);
        }
        return document;
    }

    private void requireSameProject(Long projectId, ProjectDocument document) {
        if (!projectId.equals(document.getProjectId())) {
            throw new IllegalArgumentException("父子文档必须属于同一项目");
        }
    }

    private void requireActive(ProjectDocument document) {
        if (document.getArchivedAt() != null) {
            throw new IllegalArgumentException("归档文档不能执行此操作");
        }
    }

    private String requireTitle(String title) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("文档标题不能为空");
        }
        String normalized = title.trim();
        if (normalized.length() > 256) {
            throw new IllegalArgumentException("文档标题不能超过 256 个字符");
        }
        return normalized;
    }

    private String normalizeExternalSource(String externalSource) {
        String source = externalSource == null ? null : externalSource.trim();
        if (source == null || source.isEmpty()) {
            return null;
        }
        if (source.length() > 64) {
            throw new IllegalArgumentException("外部来源不能超过 64 个字符");
        }
        return source;
    }

    private String normalizeExternalSourceId(String externalSourceId, String externalSource) {
        String sourceId = externalSourceId == null ? null : externalSourceId.trim();
        if (externalSource == null && (sourceId == null || sourceId.isEmpty())) {
            return null;
        }
        if (externalSource == null || sourceId == null || sourceId.isEmpty()) {
            throw new IllegalArgumentException("externalSource 与 externalSourceId 必须同时提供");
        }
        if (sourceId.length() > 128) {
            throw new IllegalArgumentException("外部来源文档 ID 不能超过 128 个字符");
        }
        return sourceId;
    }

    private String requireFormat(String format) {
        if (!"BLOCKNOTE".equals(format) && !"HTML".equals(format)) {
            throw new IllegalArgumentException("contentFormat 必须为 BLOCKNOTE 或 HTML");
        }
        return format;
    }

    private String requireContent(String format, String content) {
        return "HTML".equals(requireFormat(format))
                ? HtmlDocumentContent.requireSource(content) : requireBlockNoteJson(content);
    }

    private String requireBlockNoteJson(String content) {
        if (content == null) {
            throw new IllegalArgumentException("文档正文不能为空");
        }
        try {
            JsonNode value = objectMapper.readTree(content);
            if (value == null || !value.isArray()) {
                throw new IllegalArgumentException("文档正文必须是 BlockNote JSON 数组");
            }
            validateDocumentImageBlocks(value);
            return content;
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("文档正文必须是有效的 BlockNote JSON 数组");
        }
    }

    private void validateDocumentImageBlocks(JsonNode blocks) {
        for (JsonNode block : blocks) {
            if (!block.isObject()) {
                continue;
            }
            String type = block.path("type").asText();
            if ("image".equals(type)) {
                throw new IllegalArgumentException("文档图片必须使用 documentImage.imageAssetId");
            }
            if ("documentImage".equals(type)) {
                JsonNode assetId = block.path("props").path("imageAssetId");
                if (!assetId.isIntegralNumber() || !assetId.canConvertToLong() || assetId.longValue() <= 0
                        || block.path("props").has("url")) {
                    throw new IllegalArgumentException("文档图片必须只通过有效 imageAssetId 引用资源");
                }
            }
            JsonNode children = block.get("children");
            if (children != null && children.isArray()) {
                validateDocumentImageBlocks(children);
            }
            JsonNode content = block.get("content");
            if (content != null && content.isArray()) {
                validateDocumentImageBlocks(content);
            }
        }
    }

    private List<ProjectDocument> loadSiblings(Long projectId, Long parentId, boolean archived) {
        LambdaQueryWrapper<ProjectDocument> query = new LambdaQueryWrapper<ProjectDocument>()
                .eq(ProjectDocument::getProjectId, projectId)
                .orderByAsc(ProjectDocument::getSortOrder)
                .orderByAsc(ProjectDocument::getId);
        if (parentId == null) {
            query.isNull(ProjectDocument::getParentDocumentId);
        } else {
            query.eq(ProjectDocument::getParentDocumentId, parentId);
        }
        if (archived) {
            query.isNotNull(ProjectDocument::getArchivedAt);
        } else {
            query.isNull(ProjectDocument::getArchivedAt);
        }
        return documentMapper.selectList(query);
    }

    private int indexOf(List<ProjectDocument> siblings, Long id) {
        for (int index = 0; index < siblings.size(); index++) {
            if (siblings.get(index).getId().equals(id)) {
                return index;
            }
        }
        throw new IllegalArgumentException("前一个兄弟文档不在目标位置中");
    }

    private void persistOrder(List<ProjectDocument> siblings) {
        for (int index = 0; index < siblings.size(); index++) {
            ProjectDocument sibling = siblings.get(index);
            // 显式 SQL 必须写入 null 父级；updateById 会按默认策略忽略 null，导致减少缩进未落库。
            sibling.setSortOrder(index);
            documentMapper.updatePosition(sibling.getId(), sibling.getParentDocumentId(), sibling.getSortOrder());
        }
    }

    private void throwVersionConflict(Long documentId) {
        ProjectDocument current = requireDocument(documentId);
        throw new DocumentVersionConflictException(current.getVersion());
    }

    private void publishUpsert(Long documentId) {
        // 同步监听器会在当前文档事务内持久化索引任务。
        eventPublisher.publishEvent(new ProjectContentSemanticIndexRequestedEvent(
                ProjectContentType.DOCUMENT, documentId));
    }
}
