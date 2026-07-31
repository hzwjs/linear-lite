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
              (content_type, resource_id, operation, generation, run_after, attempts, lease_until)
            VALUES (#{contentType}, #{resourceId}, #{operation}, 1, #{runAfter}, 0, NULL)
            ON DUPLICATE KEY UPDATE operation = VALUES(operation), generation = generation + 1,
              run_after = VALUES(run_after), attempts = 0
            """)
    int upsert(@Param("contentType") String contentType, @Param("resourceId") Long resourceId,
               @Param("operation") String operation, @Param("runAfter") LocalDateTime runAfter);

    @Select("SELECT content_type, resource_id, operation, generation, run_after, attempts, lease_until "
            + "FROM project_content_semantic_index_jobs WHERE run_after <= #{now} "
            + "AND (lease_until IS NULL OR lease_until <= #{now}) "
            + "ORDER BY run_after ASC LIMIT #{limit}")
    List<ProjectContentSemanticIndexJob> selectDue(@Param("now") LocalDateTime now, @Param("limit") int limit);

    @Update("UPDATE project_content_semantic_index_jobs SET lease_until = #{leaseUntil} "
            + "WHERE content_type = #{contentType} AND resource_id = #{resourceId} "
            + "AND generation = #{generation} AND run_after <= #{now} "
            + "AND (lease_until IS NULL OR lease_until <= #{now})")
    int claimLease(@Param("contentType") String contentType, @Param("resourceId") Long resourceId,
                   @Param("generation") Long generation, @Param("now") LocalDateTime now,
                   @Param("leaseUntil") LocalDateTime leaseUntil);

    @Delete("DELETE FROM project_content_semantic_index_jobs WHERE content_type = #{contentType} "
            + "AND resource_id = #{resourceId} AND generation = #{generation}")
    int deleteIfGeneration(@Param("contentType") String contentType, @Param("resourceId") Long resourceId,
                           @Param("generation") Long generation);

    @Update("UPDATE project_content_semantic_index_jobs SET attempts = attempts + 1, "
            + "run_after = #{runAfter}, lease_until = NULL "
            + "WHERE content_type = #{contentType} AND resource_id = #{resourceId} "
            + "AND generation = #{generation}")
    int rescheduleIfGeneration(@Param("contentType") String contentType, @Param("resourceId") Long resourceId,
                               @Param("generation") Long generation, @Param("runAfter") LocalDateTime runAfter);
}
