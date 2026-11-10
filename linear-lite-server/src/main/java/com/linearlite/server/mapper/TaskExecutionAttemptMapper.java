package com.linearlite.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.linearlite.server.entity.TaskExecutionAttempt;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface TaskExecutionAttemptMapper extends BaseMapper<TaskExecutionAttempt> {
    @Select("SELECT * FROM task_execution_attempts WHERE id = #{id} FOR UPDATE")
    @Results(id = "attemptWithJson", value = {
            @Result(column = "prerequisite_versions", property = "prerequisiteVersions", typeHandler = JacksonTypeHandler.class),
            @Result(column = "held_write_scopes", property = "heldWriteScopes", typeHandler = StringListJsonTypeHandler.class),
            @Result(column = "checkpoints", property = "checkpoints", typeHandler = JacksonTypeHandler.class),
            @Result(column = "result_evidence", property = "resultEvidence", typeHandler = JacksonTypeHandler.class),
            @Result(column = "stop_confirmation", property = "stopConfirmation", typeHandler = JacksonTypeHandler.class)
    })
    TaskExecutionAttempt selectByIdForUpdate(@Param("id") Long id);

    @Select("SELECT * FROM task_execution_attempts WHERE task_id = #{taskId} ORDER BY id")
    @ResultMap("attemptWithJson")
    List<TaskExecutionAttempt> selectByTaskId(@Param("taskId") Long taskId);

    @Select("SELECT a.* FROM task_execution_attempts a JOIN project_device_bindings b ON b.id=a.binding_id WHERE b.project_id=#{projectId} AND a.stop_confirmed_at IS NULL ORDER BY a.id")
    @ResultMap("attemptWithJson")
    List<TaskExecutionAttempt> selectUnconfirmedByProject(@Param("projectId") Long projectId);

    /** 调用方在事务内校验领取权与 operationId；返回 0 表示版本或执行记录发生变化。 */
    @Update("""
            UPDATE task_execution_attempts
            SET checkpoints = JSON_ARRAY_APPEND(checkpoints, '$', CAST(#{checkpointJson} AS JSON))
            WHERE id = #{id} AND JSON_LENGTH(checkpoints) = #{expectedCount}
            """)
    int appendCheckpoint(@Param("id") Long id, @Param("expectedCount") int expectedCount,
                         @Param("checkpointJson") String checkpointJson);

    @Update("""
            UPDATE task_execution_attempts
            SET status = 'stop_requested', stop_requested_at = COALESCE(stop_requested_at, #{occurredAt}),
                stop_reason = COALESCE(stop_reason, #{reason})
            WHERE task_id = #{taskId} AND stop_confirmed_at IS NULL
              AND status IN ('running', 'stop_requested', 'failed')
            """)
    int requestStopForTask(@Param("taskId") Long taskId,
                           @Param("occurredAt") java.time.LocalDateTime occurredAt,
                           @Param("reason") String reason);
}
