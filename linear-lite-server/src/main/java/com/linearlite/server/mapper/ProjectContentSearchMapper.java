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
                   t.project_id AS projectId, p.identifier AS projectIdentifier, p.name AS projectName,
                   t.title, t.description AS sourceContent
            FROM tasks t
            INNER JOIN projects p ON p.id = t.project_id
            WHERE t.id = #{id}
            """)
    SearchableProjectContent selectTask(@Param("id") Long id);

    @Select("""
            SELECT 'DOCUMENT' AS contentType, d.id AS numericId, CAST(d.id AS CHAR) AS resourceId,
                   d.project_id AS projectId, p.identifier AS projectIdentifier, p.name AS projectName,
                   d.title, d.content_json AS sourceContent
            FROM project_documents d
            INNER JOIN projects p ON p.id = d.project_id
            WHERE d.id = #{id} AND d.archived_at IS NULL
            """)
    SearchableProjectContent selectDocument(@Param("id") Long id);

    @Select("SELECT id FROM tasks ORDER BY id")
    List<Long> selectAllTaskIds();

    @Select("SELECT id FROM project_documents WHERE archived_at IS NULL ORDER BY id")
    List<Long> selectAllDocumentIds();

    @Select("""
            <script>
            SELECT 'TASK' AS contentType, t.id AS numericId, t.task_key AS resourceId,
                   t.project_id AS projectId, p.identifier AS projectIdentifier, p.name AS projectName,
                   t.title, t.description AS sourceContent
            FROM tasks t
            INNER JOIN projects p ON p.id = t.project_id
            WHERE t.project_id IN
            <foreach collection="projectIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            UNION ALL
            SELECT 'DOCUMENT' AS contentType, d.id AS numericId, CAST(d.id AS CHAR) AS resourceId,
                   d.project_id AS projectId, p.identifier AS projectIdentifier, p.name AS projectName,
                   d.title, d.content_json AS sourceContent
            FROM project_documents d
            INNER JOIN projects p ON p.id = d.project_id
            WHERE d.archived_at IS NULL AND d.project_id IN
            <foreach collection="projectIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            ORDER BY numericId ASC
            </script>
            """)
    List<SearchableProjectContent> selectAccessibleContents(@Param("projectIds") List<Long> projectIds);
}
