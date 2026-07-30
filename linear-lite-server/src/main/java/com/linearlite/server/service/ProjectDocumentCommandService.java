package com.linearlite.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linearlite.server.dto.CreateProjectDocumentRequest;
import com.linearlite.server.dto.MoveProjectDocumentRequest;
import com.linearlite.server.dto.ProjectDocumentResponse;
import com.linearlite.server.dto.UpdateProjectDocumentRequest;
import com.linearlite.server.entity.ProjectDocument;
import com.linearlite.server.entity.ProjectDocumentFavorite;
import com.linearlite.server.entity.ProjectDocumentRevision;
import com.linearlite.server.exception.DocumentVersionConflictException;
import com.linearlite.server.exception.ResourceNotFoundException;
import com.linearlite.server.mapper.ProjectDocumentMapper;
import com.linearlite.server.mapper.ProjectDocumentFavoriteMapper;
import com.linearlite.server.mapper.ProjectDocumentRevisionMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Service
public class ProjectDocumentCommandService {
    private static final String EMPTY_BLOCK_NOTE_DOCUMENT = "[]";

    private final ProjectDocumentMapper documentMapper;
    private final ProjectDocumentFavoriteMapper favoriteMapper;
    private final ProjectDocumentRevisionMapper revisionMapper;
    private final ProjectAccessGuard projectAccessGuard;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

    public ProjectDocumentCommandService(
            ProjectDocumentMapper documentMapper,
            ProjectDocumentFavoriteMapper favoriteMapper,
            ProjectDocumentRevisionMapper revisionMapper,
            ProjectAccessGuard projectAccessGuard,
            ObjectMapper objectMapper,
            ApplicationEventPublisher eventPublisher) {
        this.documentMapper = documentMapper;
        this.favoriteMapper = favoriteMapper;
        this.revisionMapper = revisionMapper;
        this.projectAccessGuard = projectAccessGuard;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
    }

    @Transactional(rollbackFor = Exception.class)
    public ProjectDocumentResponse create(Long projectId, CreateProjectDocumentRequest request, Long userId) {
        projectAccessGuard.requireMember(projectId, userId);
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
                return toResponse(existing, userId);
            }
        }
        String title = requireTitle(request == null ? null : request.title());
        String content = request == null || request.content() == null
                ? EMPTY_BLOCK_NOTE_DOCUMENT
                : requireBlockNoteJson(request.content());
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
        // 导入场景允许原子创建首版正文，避免附件处理失败时遗留只有标题的文档。
        document.setContentJson(content);
        document.setSortOrder(loadSiblings(projectId, parentId, false).size());
        document.setVersion(1L);
        document.setCreatorId(userId);
        document.setLastEditorId(userId);
        documentMapper.insert(document);
        insertRevision(document, userId);
        publishUpsert(document.getId());
        return toResponse(requireDocument(document.getId()), userId);
    }

    @Transactional(rollbackFor = Exception.class)
    public ProjectDocumentResponse update(Long documentId, UpdateProjectDocumentRequest request, Long userId) {
        ProjectDocument current = requireAccessibleActiveDocument(documentId, userId);
        if (request == null || request.expectedVersion() == null) {
            throw new IllegalArgumentException("expectedVersion 不能为空");
        }
        String title = requireTitle(request.title());
        String content = requireBlockNoteJson(request.content());
        int updated = documentMapper.updateContentIfVersionMatches(
                documentId, request.expectedVersion(), title, content, userId);
        if (updated != 1) {
            throwVersionConflict(documentId);
        }
        ProjectDocument saved = requireDocument(documentId);
        insertRevision(saved, userId);
        publishUpsert(saved.getId());
        return toResponse(saved, userId);
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

    @Transactional(rollbackFor = Exception.class)
    public void archive(Long documentId, Long userId) {
        ProjectDocument document = requireAccessibleActiveDocument(documentId, userId);
        documentMapper.lockProjectDocumentMutations(document.getProjectId());
        document = requireAccessibleActiveDocument(documentId, userId);
        List<Long> subtreeIds = documentMapper.selectSubtreeIds(document.getProjectId(), documentId);
        documentMapper.update(null, new UpdateWrapper<ProjectDocument>()
                .in("id", subtreeIds)
                .set("archived_at", LocalDateTime.now()));
        eventPublisher.publishEvent(new ProjectContentSemanticDeleteRequestedEvent(
                ProjectContentType.DOCUMENT, subtreeIds));
        persistOrder(loadSiblings(document.getProjectId(), document.getParentDocumentId(), false));
    }

    @Transactional(rollbackFor = Exception.class)
    public void restore(Long documentId, Long userId) {
        ProjectDocument document = requireAccessibleDocument(documentId, userId);
        documentMapper.lockProjectDocumentMutations(document.getProjectId());
        document = requireAccessibleDocument(documentId, userId);
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
        List<Long> subtreeIds = documentMapper.selectSubtreeIds(document.getProjectId(), documentId);
        documentMapper.update(null, new UpdateWrapper<ProjectDocument>()
                .in("id", subtreeIds)
                .set("archived_at", null));
        subtreeIds.forEach(this::publishUpsert);
        persistOrder(loadSiblings(document.getProjectId(), document.getParentDocumentId(), false));
    }

    @Transactional(rollbackFor = Exception.class)
    public ProjectDocumentResponse restoreRevision(
            Long documentId, Long version, Long expectedVersion, Long userId) {
        requireAccessibleActiveDocument(documentId, userId);
        if (expectedVersion == null) {
            throw new IllegalArgumentException("expectedVersion 不能为空");
        }
        ProjectDocumentRevision revision = revisionMapper.selectOne(new LambdaQueryWrapper<ProjectDocumentRevision>()
                .eq(ProjectDocumentRevision::getDocumentId, documentId)
                .eq(ProjectDocumentRevision::getVersion, version));
        if (revision == null) {
            throw new ResourceNotFoundException("文档版本不存在: " + version);
        }
        int updated = documentMapper.updateContentIfVersionMatches(
                documentId, expectedVersion, revision.getTitle(), revision.getContentJson(), userId);
        if (updated != 1) {
            throwVersionConflict(documentId);
        }
        ProjectDocument saved = requireDocument(documentId);
        insertRevision(saved, userId);
        publishUpsert(saved.getId());
        return toResponse(saved, userId);
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
        return ProjectDocumentQueryService.toResponse(document, true);
    }

    @Transactional(rollbackFor = Exception.class)
    public ProjectDocumentResponse removeFavorite(Long documentId, Long userId) {
        ProjectDocument document = requireAccessibleActiveDocument(documentId, userId);
        favoriteMapper.delete(new LambdaQueryWrapper<ProjectDocumentFavorite>()
                .eq(ProjectDocumentFavorite::getUserId, userId)
                .eq(ProjectDocumentFavorite::getDocumentId, documentId));
        return ProjectDocumentQueryService.toResponse(document, false);
    }

    private ProjectDocumentResponse toResponse(ProjectDocument document, Long userId) {
        boolean favorited = favoriteMapper.selectCount(new LambdaQueryWrapper<ProjectDocumentFavorite>()
                .eq(ProjectDocumentFavorite::getUserId, userId)
                .eq(ProjectDocumentFavorite::getDocumentId, document.getId())) > 0;
        return ProjectDocumentQueryService.toResponse(document, favorited);
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

    private String requireBlockNoteJson(String content) {
        if (content == null) {
            throw new IllegalArgumentException("文档正文不能为空");
        }
        try {
            JsonNode value = objectMapper.readTree(content);
            if (value == null || !value.isArray()) {
                throw new IllegalArgumentException("文档正文必须是 BlockNote JSON 数组");
            }
            return content;
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("文档正文必须是有效的 BlockNote JSON 数组");
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

    private void insertRevision(ProjectDocument document, Long editorId) {
        ProjectDocumentRevision revision = new ProjectDocumentRevision();
        revision.setDocumentId(document.getId());
        revision.setVersion(document.getVersion());
        revision.setTitle(document.getTitle());
        revision.setContentJson(document.getContentJson());
        revision.setEditorId(editorId);
        revisionMapper.insert(revision);
    }

    private void throwVersionConflict(Long documentId) {
        ProjectDocument current = requireDocument(documentId);
        throw new DocumentVersionConflictException(current.getVersion());
    }

    private void publishUpsert(Long documentId) {
        eventPublisher.publishEvent(new ProjectContentSemanticIndexRequestedEvent(
                ProjectContentType.DOCUMENT, documentId));
    }
}
