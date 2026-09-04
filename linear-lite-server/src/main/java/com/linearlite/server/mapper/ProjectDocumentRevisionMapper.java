package com.linearlite.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linearlite.server.entity.ProjectDocumentRevision;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface ProjectDocumentRevisionMapper extends BaseMapper<ProjectDocumentRevision> {
    @Select("""
            SELECT d.*
            FROM project_documents d
            LEFT JOIN project_document_revisions revision
              ON revision.document_id = d.id AND revision.version = d.version
            WHERE d.archived_at IS NULL
              AND d.updated_at <= CURRENT_TIMESTAMP - INTERVAL 2 MINUTE
              AND revision.id IS NULL
            """)
    List<com.linearlite.server.entity.ProjectDocument> selectDocumentsNeedingIdleRevision();

    /** 活跃编辑阈值使用数据库时钟，避免 JVM 与数据库时区不同造成每次编辑都留档。 */
    @Select("""
            SELECT COUNT(*) = 0
                   OR MAX(created_at) <= CURRENT_TIMESTAMP - INTERVAL 10 MINUTE
            FROM project_document_revisions
            WHERE document_id = #{documentId}
            """)
    boolean isActiveSnapshotDue(@Param("documentId") Long documentId);
}
