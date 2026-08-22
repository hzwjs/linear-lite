package com.linearlite.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linearlite.server.dto.ProjectDocumentResponse;
import com.linearlite.server.dto.ProjectDocumentRevisionResponse;
import com.linearlite.server.dto.ProjectDocumentRevisionSummary;
import com.linearlite.server.dto.ProjectDocumentTreeNode;
import com.linearlite.server.entity.ProjectDocument;
import com.linearlite.server.entity.ProjectDocumentFavorite;
import com.linearlite.server.entity.ProjectDocumentRevision;
import com.linearlite.server.exception.ConflictOperationException;
import com.linearlite.server.exception.ResourceNotFoundException;
import com.linearlite.server.mapper.ProjectDocumentMapper;
import com.linearlite.server.mapper.ProjectDocumentFavoriteMapper;
import com.linearlite.server.mapper.ProjectDocumentRevisionMapper;
import com.linearlite.server.mapper.UserMapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class ProjectDocumentQueryService {
    private final ProjectDocumentMapper documentMapper;
    private final ProjectDocumentFavoriteMapper favoriteMapper;
    private final ProjectDocumentRevisionMapper revisionMapper;
    private final UserMapper userMapper;
    private final ProjectAccessGuard projectAccessGuard;

    public ProjectDocumentQueryService(
            ProjectDocumentMapper documentMapper,
            ProjectDocumentFavoriteMapper favoriteMapper,
            ProjectDocumentRevisionMapper revisionMapper,
            UserMapper userMapper,
            ProjectAccessGuard projectAccessGuard) {
        this.documentMapper = documentMapper;
        this.favoriteMapper = favoriteMapper;
        this.revisionMapper = revisionMapper;
        this.userMapper = userMapper;
        this.projectAccessGuard = projectAccessGuard;
    }

    public List<ProjectDocumentTreeNode> listTree(Long projectId, Long userId, boolean archived) {
        projectAccessGuard.requireMember(projectId, userId);
        return documentMapper.selectTreeNodes(projectId, userId, archived);
    }

    public List<ProjectDocumentTreeNode> listFavorites(Long userId) {
        return documentMapper.selectFavoriteTreeNodes(userId);
    }

    public ProjectDocumentResponse getDocument(Long documentId, Long userId) {
        return toResponse(requireDocument(documentId, userId), isFavorite(documentId, userId));
    }

    public ProjectDocumentResponse getDocumentByTitle(String title, Long userId) {
        List<ProjectDocument> documents = documentMapper.selectAccessibleByTitle(title, userId);
        if (documents.isEmpty()) {
            throw new ResourceNotFoundException("项目文档不存在: " + title);
        }
        if (documents.size() > 1) {
            throw new ConflictOperationException("文档标题不唯一，请改用 documentId: " + title);
        }
        ProjectDocument document = documents.get(0);
        return toResponse(document, isFavorite(document.getId(), userId));
    }

    public List<ProjectDocumentRevisionSummary> listRevisions(Long documentId, Long userId) {
        requireDocument(documentId, userId);
        List<ProjectDocumentRevision> revisions = revisionMapper.selectList(new LambdaQueryWrapper<ProjectDocumentRevision>()
                        .eq(ProjectDocumentRevision::getDocumentId, documentId)
                        .orderByDesc(ProjectDocumentRevision::getCreatedAt)
                        .orderByDesc(ProjectDocumentRevision::getId));
        if (revisions.isEmpty()) return List.of();
        // editor_id 无外键约束，被删除用户不在 map 中，editorName 允许为 null
        Map<Long, String> editorNames = userMapper.selectBatchIds(revisions.stream()
                        .map(ProjectDocumentRevision::getEditorId)
                        .distinct()
                        .toList())
                .stream()
                .collect(Collectors.toMap(user -> user.getId(), user -> user.getUsername()));
        return revisions
                .stream()
                .map(revision -> new ProjectDocumentRevisionSummary(
                        revision.getId(), revision.getVersion(), revision.getTitle(), revision.getEditorId(),
                        editorNames.get(revision.getEditorId()), revision.getCreatedAt()))
                .toList();
    }

    public ProjectDocumentRevisionResponse getRevision(Long documentId, Long revisionId, Long userId) {
        requireDocument(documentId, userId);
        ProjectDocumentRevision revision = revisionMapper.selectOne(new LambdaQueryWrapper<ProjectDocumentRevision>()
                .eq(ProjectDocumentRevision::getDocumentId, documentId)
                .eq(ProjectDocumentRevision::getId, revisionId));
        if (revision == null) {
            throw new ResourceNotFoundException("文档修订版不存在: " + revisionId);
        }
        // editor_id 无外键约束，被删除用户不在 map 中，editorName 允许为 null
        Map<Long, String> editorNameMap = userMapper.selectBatchIds(List.of(revision.getEditorId()))
                .stream().collect(Collectors.toMap(user -> user.getId(), user -> user.getUsername()));
        String editorName = editorNameMap.get(revision.getEditorId());
        return new ProjectDocumentRevisionResponse(
                revision.getDocumentId(), revision.getId(), revision.getVersion(), revision.getTitle(),
                revision.getContentJson(), revision.getEditorId(), editorName, revision.getCreatedAt());
    }

    ProjectDocument requireDocument(Long documentId, Long userId) {
        ProjectDocument document = documentMapper.selectById(documentId);
        if (document == null) {
            throw new ResourceNotFoundException("项目文档不存在: " + documentId);
        }
        projectAccessGuard.requireMember(document.getProjectId(), userId);
        return document;
    }

    private boolean isFavorite(Long documentId, Long userId) {
        return favoriteMapper.selectCount(new LambdaQueryWrapper<ProjectDocumentFavorite>()
                .eq(ProjectDocumentFavorite::getUserId, userId)
                .eq(ProjectDocumentFavorite::getDocumentId, documentId)) > 0;
    }

    static ProjectDocumentResponse toResponse(ProjectDocument document, boolean favorited) {
        return new ProjectDocumentResponse(
                document.getId(), document.getProjectId(), document.getParentDocumentId(),
                document.getExternalSource(), document.getExternalSourceId(), document.getTitle(),
                document.getContentJson(), document.getSortOrder(), document.getVersion(), document.getCreatorId(),
                document.getLastEditorId(), favorited, document.getArchivedAt(), document.getCreatedAt(), document.getUpdatedAt());
    }

}
