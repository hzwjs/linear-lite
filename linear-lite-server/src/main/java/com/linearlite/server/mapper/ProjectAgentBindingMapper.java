package com.linearlite.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linearlite.server.dto.AgentProjectResponse;
import com.linearlite.server.entity.ProjectAgentBinding;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ProjectAgentBindingMapper extends BaseMapper<ProjectAgentBinding> {
    @Select("""
            SELECT b.project_id AS projectId, p.name AS projectName
            FROM project_agent_bindings b
            INNER JOIN projects p ON p.id = b.project_id
            WHERE b.agent_user_id = #{agentUserId} AND b.enabled = TRUE
            ORDER BY p.name, p.id
            """)
    List<AgentProjectResponse> selectEnabledProjects(@Param("agentUserId") Long agentUserId);
}
