package com.linearlite.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linearlite.server.entity.ProjectMember;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ProjectMemberMapper extends BaseMapper<ProjectMember> {
    /** 一次形成搜索权限快照，项目元数据不进入 Qdrant，避免项目改名后索引过期。 */
    @Select("""
            SELECT DISTINCT pm.project_id AS projectId,
                   p.identifier AS projectIdentifier,
                   p.name AS projectName
            FROM project_members pm
            INNER JOIN projects p ON p.id = pm.project_id
            WHERE pm.user_id = #{userId}
            ORDER BY pm.project_id
            """)
    List<ProjectPermissionScope> selectSearchPermissionScopes(@Param("userId") Long userId);

    record ProjectPermissionScope(Long projectId, String projectIdentifier, String projectName) {
    }
}
