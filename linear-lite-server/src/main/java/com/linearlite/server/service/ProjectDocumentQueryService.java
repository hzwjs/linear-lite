package com.linearlite.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linearlite.server.dto.ProjectDocumentResponse;
import com.linearlite.server.dto.ProjectDocumentRevisionResponse;
import com.linearlite.server.dto.ProjectDocumentRevisionSummary;
import com.linearlite.server.dto.ProjectDocumentTreeNode;
import com.linearlite.server.entity.ProjectDocument;
import com.linearlite.server.entity.ProjectDocumentRevision;
import com.linearlite.server.exception.ResourceNotFoundException;
import com.linearlite.server.mapper.ProjectDocumentMapper;
import com.linearlite.server.mapper.ProjectDocumentRevisionMapper;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ProjectDocumentQueryService {
    private final ProjectDocumentMapper documentMapper;
    private final ProjectDocumentRevisionMapper revisionMapper;
    private final ProjectAccessGuard projectAccessGuard;

    public ProjectDocumentQueryService(
            ProjectDocumentMapper documentMapper,
            ProjectDocumentRevisionMapper revisionMapper,
            ProjectAccessGuard projectAccessGuard) {
        this.documentMapper = documentMapper;
        this.revisionMapper = revisionMapper;
        this.projectAccessGuard = projectAccessGuard;
    }

    public List<ProjectDocumentTreeNode> listTree(Long projectId, Long userId, boolean archived) {
        projectAccessGuard.requireMember(projectId, userId);
        return documentMapper.selectTreeNodes(projectId, archived);
    }

    public ProjectDocumentResponse getDocument(Long documentId, Long userId) {
        return toResponse(requireDocument(documentId, userId));
    }

    public List<ProjectDocumentRevisionSummary> listRevisions(Long documentId, Long userId) {
        requireDocument(documentId, userId);
        return revisionMapper.selectList(new LambdaQueryWrapper<ProjectDocumentRevision>()
                        .eq(ProjectDocumentRevision::getDocumentId, documentId)
                        .orderByDesc(ProjectDocumentRevision::getVersion))
                .stream()
                .map(revision -> new ProjectDocumentRevisionSummary(
                        revision.getVersion(), revision.getTitle(), revision.getEditorId(), revision.getCreatedAt()))
                .toList();
    }

    public ProjectDocumentRevisionResponse getRevision(Long documentId, Long version, Long userId) {
        requireDocument(documentId, userId);
        ProjectDocumentRevision revision = revisionMapper.selectOne(new LambdaQueryWrapper<ProjectDocumentRevision>()
                .eq(ProjectDocumentRevision::getDocumentId, documentId)
                .eq(ProjectDocumentRevision::getVersion, version));
        if (revision == null) {
            throw new ResourceNotFoundException("文档版本不存在: " + version);
        }
        return new ProjectDocumentRevisionResponse(
                revision.getDocumentId(), revision.getVersion(), revision.getTitle(), revision.getContentJson(),
                revision.getEditorId(), revision.getCreatedAt());
    }

    ProjectDocument requireDocument(Long documentId, Long userId) {
        ProjectDocument document = documentMapper.selectById(documentId);
        if (document == null) {
            throw new ResourceNotFoundException("项目文档不存在: " + documentId);
        }
        projectAccessGuard.requireMember(document.getProjectId(), userId);
        return document;
    }

    static ProjectDocumentResponse toResponse(ProjectDocument document) {
        return new ProjectDocumentResponse(
                document.getId(), document.getProjectId(), document.getParentDocumentId(),
                document.getExternalSource(), document.getExternalSourceId(), document.getTitle(),
                document.getContentJson(), document.getSortOrder(), document.getVersion(), document.getCreatorId(),
                document.getLastEditorId(), document.getArchivedAt(), document.getCreatedAt(), document.getUpdatedAt());
    }

}
