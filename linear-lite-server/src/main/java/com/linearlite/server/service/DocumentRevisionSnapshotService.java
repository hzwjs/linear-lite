package com.linearlite.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linearlite.server.entity.ProjectDocument;
import com.linearlite.server.entity.ProjectDocumentRevision;
import com.linearlite.server.mapper.ProjectDocumentRevisionMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/** Stores immutable projections of committed Yjs versions for history and recovery. */
@Service
public class DocumentRevisionSnapshotService {
    private final ProjectDocumentRevisionMapper revisionMapper;

    public DocumentRevisionSnapshotService(ProjectDocumentRevisionMapper revisionMapper) {
        this.revisionMapper = revisionMapper;
    }

    @Transactional(rollbackFor = Exception.class)
    public Long captureInitial(ProjectDocument document, Long editorId, String content) {
        return capture(document, editorId, content);
    }

    /** Explicit edits and restores create a revision at the committed version. */
    @Transactional(rollbackFor = Exception.class)
    public Long captureForced(ProjectDocument document, Long editorId, String content) {
        return capture(document, editorId, content);
    }

    @Transactional(rollbackFor = Exception.class)
    public void captureIfDue(ProjectDocument document, Long editorId, String content) {
        if (revisionMapper.isActiveSnapshotDue(document.getId())) {
            captureIfChanged(document, editorId, content);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void captureIfChanged(ProjectDocument document, Long editorId, String content) {
        ProjectDocumentRevision latest = revisionMapper.selectOne(new LambdaQueryWrapper<ProjectDocumentRevision>()
                .eq(ProjectDocumentRevision::getDocumentId, document.getId())
                .orderByDesc(ProjectDocumentRevision::getVersion)
                .last("LIMIT 1"));
        if (latest != null && Objects.equals(latest.getTitle(), document.getTitle())
                && Objects.equals(latest.getContent(), content)
                && Objects.equals(latest.getContentFormat(), document.getContentFormat())) {
            return;
        }
        capture(document, editorId, content);
    }

    public List<ProjectDocument> findIdleCandidates() {
        return revisionMapper.selectDocumentsNeedingIdleRevision();
    }

    @Transactional(rollbackFor = Exception.class)
    public void captureBeforeRestore(ProjectDocument document, String content) {
        if (findForVersion(document) == null) {
            insert(document, document.getLastEditorId(), content);
        }
    }

    private ProjectDocumentRevision findForVersion(ProjectDocument document) {
        return revisionMapper.selectOne(new LambdaQueryWrapper<ProjectDocumentRevision>()
                .eq(ProjectDocumentRevision::getDocumentId, document.getId())
                .eq(ProjectDocumentRevision::getVersion, document.getVersion()));
    }

    private Long capture(ProjectDocument document, Long editorId, String content) {
        ProjectDocumentRevision existing = findForVersion(document);
        if (existing != null) {
            if (!Objects.equals(existing.getTitle(), document.getTitle())
                    || !Objects.equals(existing.getContent(), content)
                    || !Objects.equals(existing.getContentFormat(), document.getContentFormat())) {
                throw new IllegalStateException("相同文档版本的修订快照内容不一致");
            }
            return existing.getId();
        }
        return insert(document, editorId, content);
    }

    private Long insert(ProjectDocument document, Long editorId, String content) {
        ProjectDocumentRevision revision = new ProjectDocumentRevision();
        revision.setDocumentId(document.getId());
        revision.setVersion(document.getVersion());
        revision.setTitle(document.getTitle());
        revision.setContent(content);
        revision.setContentFormat(document.getContentFormat());
        revision.setEditorId(editorId);
        revisionMapper.insert(revision);
        return revision.getId();
    }
}
