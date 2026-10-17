package com.linearlite.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linearlite.server.entity.ProjectDocument;
import com.linearlite.server.entity.ProjectDocumentRevision;
import com.linearlite.server.mapper.ProjectDocumentRevisionMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    /** Each durable collaboration commit gets one projection so every expectedVersion remains restorable. */
    @Transactional(rollbackFor = Exception.class)
    public Long captureForced(ProjectDocument document, Long editorId, String content) {
        return capture(document, editorId, content);
    }

    @Transactional(rollbackFor = Exception.class)
    public Long captureTitleVersion(ProjectDocument document, Long editorId, String content) {
        return capture(document, editorId, content);
    }

    private Long capture(ProjectDocument document, Long editorId, String content) {
        ProjectDocumentRevision existing = revisionMapper.selectOne(new LambdaQueryWrapper<ProjectDocumentRevision>()
                .eq(ProjectDocumentRevision::getDocumentId, document.getId())
                .eq(ProjectDocumentRevision::getVersion, document.getVersion()));
        if (existing != null) {
            if (!Objects.equals(existing.getTitle(), document.getTitle())
                    || !Objects.equals(existing.getContent(), content)
                    || !Objects.equals(existing.getContentFormat(), document.getContentFormat())) {
                throw new IllegalStateException("相同文档版本的修订快照内容不一致");
            }
            return existing.getId();
        }
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
