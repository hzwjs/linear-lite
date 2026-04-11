package com.linearlite.server.mapper;

import com.linearlite.server.entity.ProjectTaskSeq;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ProjectTaskSeqMapper {

    @Select("SELECT project_id AS projectId, next_number AS nextNumber " +
            "FROM project_task_seq WHERE project_id = #{projectId} FOR UPDATE")
    ProjectTaskSeq selectByProjectIdForUpdate(@Param("projectId") Long projectId);

    @Insert("INSERT INTO project_task_seq (project_id, next_number) VALUES (#{projectId}, #{nextNumber})")
    int insertRow(@Param("projectId") Long projectId, @Param("nextNumber") Long nextNumber);

    @Update("UPDATE project_task_seq SET next_number = #{nextNumber} WHERE project_id = #{projectId}")
    int updateNextNumber(@Param("projectId") Long projectId, @Param("nextNumber") Long nextNumber);

    @Select("SELECT COALESCE(MAX(CAST(SUBSTRING(task_key, #{startPos}) AS UNSIGNED)), 0) " +
            "FROM tasks WHERE project_id = #{projectId} AND task_key LIKE CONCAT(#{identifier}, '-%')")
    Long selectMaxTaskNumber(
            @Param("projectId") Long projectId,
            @Param("identifier") String identifier,
            @Param("startPos") int startPos);
}
