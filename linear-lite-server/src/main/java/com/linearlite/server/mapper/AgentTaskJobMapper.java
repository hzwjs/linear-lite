package com.linearlite.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linearlite.server.entity.AgentTaskJob;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;

@Mapper
public interface AgentTaskJobMapper extends BaseMapper<AgentTaskJob> {
    /** Turn 是否已经提交必须以持久化 Job 为准，不能由当前活动 Job 或前端会话状态推断。 */
    @Select("""
            SELECT EXISTS(
                SELECT 1 FROM agent_task_jobs
                WHERE session_id = #{sessionId}
                  AND source_type = 'turn'
            )
            """)
    boolean existsTurn(@Param("sessionId") Long sessionId);

    @Select("""
            SELECT j.*
            FROM agent_task_jobs j
            INNER JOIN agent_task_sessions s ON s.id = j.session_id
            WHERE s.agent_user_id = #{ownerUserId}
              AND s.execution_id = #{executionId}
              AND j.source_type = 'turn'
              AND j.status IN ('queued', 'leased')
              AND j.next_run_at <= #{now}
              AND (j.lease_until IS NULL OR j.lease_until < #{now})
            ORDER BY j.created_at, j.id
            LIMIT 1
            FOR UPDATE
            """)
    AgentTaskJob selectClaimableForExecution(
            @Param("ownerUserId") Long ownerUserId,
            @Param("executionId") String executionId,
            @Param("now") LocalDateTime now);
}
