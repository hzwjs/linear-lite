package com.linearlite.server.mapper;

import com.linearlite.server.entity.SearchableProjectContent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ProjectContentSearchMapper {
    @Select("""
            SELECT 'TASK' AS contentType, t.id AS numericId, t.task_key AS resourceId,
                   t.project_id AS projectId, t.title, t.description AS sourceContent,
                   t.updated_at AS sourceUpdatedAt
            FROM tasks t
            WHERE t.id = #{id}
            """)
    SearchableProjectContent selectTask(@Param("id") Long id);

    @Select("""
            SELECT 'DOCUMENT' AS contentType, d.id AS numericId, CAST(d.id AS CHAR) AS resourceId,
                   d.project_id AS projectId, d.title, d.content_json AS sourceContent,
                   d.updated_at AS sourceUpdatedAt
            FROM project_documents d
            WHERE d.id = #{id} AND d.archived_at IS NULL
            """)
    SearchableProjectContent selectDocument(@Param("id") Long id);

    @Select("SELECT id FROM tasks ORDER BY id")
    List<Long> selectAllTaskIds();

    @Select("SELECT id FROM project_documents WHERE archived_at IS NULL ORDER BY id")
    List<Long> selectAllDocumentIds();

}
