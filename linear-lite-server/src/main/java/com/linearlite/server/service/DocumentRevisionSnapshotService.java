package com.linearlite.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linearlite.server.entity.ProjectDocument;
import com.linearlite.server.entity.ProjectDocumentRevision;
import com.linearlite.server.mapper.ProjectDocumentMapper;
import com.linearlite.server.mapper.ProjectDocumentRevisionMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Objects;

/** 服务端唯一的文档历史快照入口，避免把留档可靠性绑定到浏览器生命周期。 */
@Service
public class DocumentRevisionSnapshotService {
    private final ProjectDocumentMapper documentMapper;
    private final ProjectDocumentRevisionMapper revisionMapper;
    private final TransactionTemplate transactionTemplate;

    public DocumentRevisionSnapshotService(
            ProjectDocumentMapper documentMapper,
            ProjectDocumentRevisionMapper revisionMapper) {
        this(documentMapper, revisionMapper, null);
    }

    @Autowired
    public DocumentRevisionSnapshotService(
            ProjectDocumentMapper documentMapper,
            ProjectDocumentRevisionMapper revisionMapper,
            PlatformTransactionManager transactionManager) {
        this.documentMapper = documentMapper;
        this.revisionMapper = revisionMapper;
        this.transactionTemplate = transactionManager == null ? null : new TransactionTemplate(transactionManager);
    }

    @Transactional(rollbackFor = Exception.class)
    public void captureInitial(ProjectDocument document, Long editorId) {
        captureIfMissing(document, editorId);
    }

    @Transactional(rollbackFor = Exception.class)
    public void captureBeforeUpdate(ProjectDocument current, Long editorId) {
        ProjectDocumentRevision latest = latest(current.getId());
        boolean editorChanged = !editorId.equals(current.getLastEditorId());
        boolean idleBoundary = Boolean.TRUE.equals(documentMapper.selectUpdatedBeforeIdle(current.getId()));
        if (latest == null || (latest.getVersion() < current.getVersion() && (editorChanged || idleBoundary))) {
            captureIfMissing(current, current.getLastEditorId());
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void captureAfterUpdate(ProjectDocument saved, Long editorId) {
        if ("HTML".equals(saved.getContentFormat()) || revisionMapper.isActiveSnapshotDue(saved.getId())) {
            captureIfMissing(saved, editorId);
        }
    }

    /** Creates exactly one immutable snapshot for an edited version, or verifies its existing content. */
    @Transactional(rollbackFor = Exception.class)
    public Long captureForced(ProjectDocument saved, Long editorId) {
        ProjectDocumentRevision existing = revisionMapper.selectOne(new LambdaQueryWrapper<ProjectDocumentRevision>()
                .eq(ProjectDocumentRevision::getDocumentId, saved.getId())
                .eq(ProjectDocumentRevision::getVersion, saved.getVersion()));
        if (existing != null) {
            if (!Objects.equals(existing.getTitle(), saved.getTitle())
                    || !Objects.equals(existing.getContent(), saved.getContent())
                    || !Objects.equals(existing.getContentFormat(), saved.getContentFormat())) {
                throw new IllegalStateException("相同文档版本的修订快照内容不一致");
            }
            return existing.getId();
        }
        ProjectDocumentRevision revision = new ProjectDocumentRevision();
        revision.setDocumentId(saved.getId());
        revision.setVersion(saved.getVersion());
        revision.setTitle(saved.getTitle());
        revision.setContent(saved.getContent());
        revision.setContentFormat(saved.getContentFormat());
        revision.setEditorId(editorId);
        revisionMapper.insert(revision);
        return revision.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public void captureRestored(ProjectDocument saved, Long editorId) {
        captureIfMissing(saved, editorId);
    }

    @Transactional(rollbackFor = Exception.class)
    public void captureBeforeRestore(ProjectDocument current) {
        captureIfMissing(current, current.getLastEditorId());
    }

    @Scheduled(fixedDelayString = "${app.document-revision.idle-worker-delay-millis:60000}")
    public void captureIdleRevisions() {
        List<ProjectDocument> candidates = revisionMapper.selectDocumentsNeedingIdleRevision();
        for (ProjectDocument candidate : candidates) {
            Runnable capture = () -> captureIdleRevision(candidate);
            if (transactionTemplate == null) capture.run();
            else transactionTemplate.executeWithoutResult(status -> capture.run());
        }
    }

    private void captureIdleRevision(ProjectDocument candidate) {
        documentMapper.lockProjectDocumentMutations(candidate.getProjectId());
        ProjectDocument locked = documentMapper.selectByIdForUpdate(candidate.getId());
        if (locked == null || locked.getArchivedAt() != null || locked.getUpdatedAt() == null) return;
        if (!Boolean.TRUE.equals(documentMapper.selectUpdatedBeforeIdle(locked.getId()))) return;
        captureIfMissing(locked, locked.getLastEditorId());
    }

    private ProjectDocumentRevision latest(Long documentId) {
        return revisionMapper.selectOne(new LambdaQueryWrapper<ProjectDocumentRevision>()
                .eq(ProjectDocumentRevision::getDocumentId, documentId)
                .orderByDesc(ProjectDocumentRevision::getCreatedAt)
                .orderByDesc(ProjectDocumentRevision::getId)
                .last("LIMIT 1"));
    }

    private void captureIfMissing(ProjectDocument document, Long editorId) {
        ProjectDocumentRevision existing = revisionMapper.selectOne(new LambdaQueryWrapper<ProjectDocumentRevision>()
                .eq(ProjectDocumentRevision::getDocumentId, document.getId())
                .eq(ProjectDocumentRevision::getVersion, document.getVersion()));
        if (existing != null) return;

        ProjectDocumentRevision revision = new ProjectDocumentRevision();
        revision.setDocumentId(document.getId());
        revision.setVersion(document.getVersion());
        revision.setTitle(document.getTitle());
        revision.setContent(document.getContent());
        revision.setContentFormat(document.getContentFormat());
        revision.setEditorId(editorId);
        revisionMapper.insert(revision);
    }
}
