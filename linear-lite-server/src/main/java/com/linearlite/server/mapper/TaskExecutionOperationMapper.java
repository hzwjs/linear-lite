package com.linearlite.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linearlite.server.entity.TaskExecutionOperation;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface TaskExecutionOperationMapper extends BaseMapper<TaskExecutionOperation> {
    @Select("""
            SELECT * FROM task_execution_operations
            WHERE project_id = #{projectId} AND user_id = #{userId} AND operation_id = #{operationId}
            FOR UPDATE
            """)
    TaskExecutionOperation selectForUpdate(@Param("projectId") Long projectId,
                                          @Param("userId") Long userId,
                                          @Param("operationId") String operationId);

    @Delete("DELETE FROM task_execution_operations WHERE project_id = #{projectId}")
    int deleteByProjectId(@Param("projectId") Long projectId);
}
