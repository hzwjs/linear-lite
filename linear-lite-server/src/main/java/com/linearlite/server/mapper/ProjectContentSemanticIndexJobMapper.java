package com.linearlite.server.mapper;

import com.linearlite.server.entity.ProjectContentSemanticIndexJob;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface ProjectContentSemanticIndexJobMapper {
    @Insert("""
            INSERT INTO project_content_semantic_index_jobs
              (content_type, resource_id, operation, content_hash, run_after, version, attempts)
            VALUES (#{contentType}, #{resourceId}, #{operation}, #{contentHash}, #{runAfter}, 1, 0)
            ON DUPLICATE KEY UPDATE operation = VALUES(operation), content_hash = VALUES(content_hash),
              run_after = VALUES(run_after), version = version + 1, attempts = 0
            """)
    int upsert(@Param("contentType") String contentType, @Param("resourceId") Long resourceId,
               @Param("operation") String operation, @Param("contentHash") String contentHash,
               @Param("runAfter") LocalDateTime runAfter);

    @Select("SELECT content_type, resource_id, operation, content_hash, run_after, version, attempts "
            + "FROM project_content_semantic_index_jobs WHERE run_after <= #{now} "
            + "ORDER BY run_after ASC LIMIT #{limit}")
    List<ProjectContentSemanticIndexJob> selectDue(@Param("now") LocalDateTime now, @Param("limit") int limit);

    @Delete("DELETE FROM project_content_semantic_index_jobs WHERE content_type = #{contentType} "
            + "AND resource_id = #{resourceId} AND version = #{version}")
    int deleteIfVersion(@Param("contentType") String contentType, @Param("resourceId") Long resourceId,
                        @Param("version") Long version);

    @Update("UPDATE project_content_semantic_index_jobs SET attempts = attempts + 1, run_after = #{runAfter} "
            + "WHERE content_type = #{contentType} AND resource_id = #{resourceId} AND version = #{version}")
    int rescheduleIfVersion(@Param("contentType") String contentType, @Param("resourceId") Long resourceId,
                            @Param("version") Long version, @Param("runAfter") LocalDateTime runAfter);
}
