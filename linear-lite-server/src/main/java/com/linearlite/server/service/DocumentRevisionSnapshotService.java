package com.linearlite.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linearlite.server.entity.ProjectDocument;
import com.linearlite.server.entity.ProjectDocumentRevision;
import com.linearlite.server.mapper.ProjectDocumentMapper;
import com.linearlite.server.mapper.ProjectDocumentRevisionMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/** 服务端唯一的文档历史快照入口，避免把留档可靠性绑定到浏览器生命周期。 */
@Service
public class DocumentRevisionSnapshotService {
    private static final Duration ACTIVE_SNAPSHOT_INTERVAL = Duration.ofMinutes(10);
    private static final Duration IDLE_SNAPSHOT_DELAY = Duration.ofMinutes(2);

    private final ProjectDocumentMapper documentMapper;
    private final ProjectDocumentRevisionMapper revisionMapper;

    public DocumentRevisionSnapshotService(
            ProjectDocumentMapper documentMapper,
            ProjectDocumentRevisionMapper revisionMapper) {
        this.documentMapper = documentMapper;
        this.revisionMapper = revisionMapper;
    }

    @Transactional(rollbackFor = Exception.class)
    public void captureInitial(ProjectDocument document, Long editorId) {
        captureIfMissing(document, editorId);
    }

    @Transactional(rollbackFor = Exception.class)
    public void captureBeforeUpdate(ProjectDocument current, Long editorId) {
        ProjectDocumentRevision latest = latest(current.getId());
        boolean editorChanged = !editorId.equals(current.getLastEditorId());
        boolean idleBoundary = current.getUpdatedAt() != null
                && current.getUpdatedAt().plus(IDLE_SNAPSHOT_DELAY).isBefore(LocalDateTime.now());
        if (latest == null || (latest.getVersion() < current.getVersion() && (editorChanged || idleBoundary))) {
            captureIfMissing(current, current.getLastEditorId());
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void captureAfterUpdate(ProjectDocument saved, Long editorId) {
        ProjectDocumentRevision latest = latest(saved.getId());
        if (latest == null || Duration.between(latest.getCreatedAt(), LocalDateTime.now())
                .compareTo(ACTIVE_SNAPSHOT_INTERVAL) >= 0) {
            captureIfMissing(saved, editorId);
        }
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
    @Transactional(rollbackFor = Exception.class)
    public void captureIdleRevisions() {
        LocalDateTime cutoff = LocalDateTime.now().minus(IDLE_SNAPSHOT_DELAY);
        List<ProjectDocument> candidates = revisionMapper.selectDocumentsNeedingIdleRevision(cutoff);
        for (ProjectDocument candidate : candidates) {
            ProjectDocument locked = documentMapper.selectByIdForUpdate(candidate.getId());
            if (locked == null || locked.getArchivedAt() != null || locked.getUpdatedAt() == null
                    || locked.getUpdatedAt().isAfter(cutoff)) {
                continue;
            }
            captureIfMissing(locked, locked.getLastEditorId());
        }
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
        revision.setContentJson(document.getContentJson());
        revision.setEditorId(editorId);
        revision.setCreatedAt(LocalDateTime.now());
        revisionMapper.insert(revision);
    }
}
