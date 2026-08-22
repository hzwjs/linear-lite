package com.linearlite.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linearlite.server.entity.ProjectDocumentRevision;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Mapper;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface ProjectDocumentRevisionMapper extends BaseMapper<ProjectDocumentRevision> {
    @Select("""
            SELECT d.*
            FROM project_documents d
            LEFT JOIN project_document_revisions revision
              ON revision.document_id = d.id AND revision.version = d.version
            WHERE d.archived_at IS NULL
              AND d.updated_at <= #{cutoff}
              AND revision.id IS NULL
            """)
    List<com.linearlite.server.entity.ProjectDocument> selectDocumentsNeedingIdleRevision(
            @Param("cutoff") LocalDateTime cutoff);
}
