package com.linearlite.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linearlite.server.entity.AgentTaskSession;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AgentTaskSessionMapper extends BaseMapper<AgentTaskSession> {
    @Select("""
            SELECT * FROM agent_task_sessions
            WHERE task_id = #{taskId}
              AND execution_id = #{executionId}
              AND agent_user_id = #{ownerUserId}
              AND status IN ('waiting_input', 'running')
            LIMIT 1
            FOR UPDATE
            """)
    AgentTaskSession selectActiveForUpdate(
            @Param("taskId") Long taskId,
            @Param("executionId") String executionId,
            @Param("ownerUserId") Long ownerUserId);
}
