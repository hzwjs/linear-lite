package com.linearlite.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linearlite.server.entity.ExternalAssignee;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ExternalAssigneeMapper extends BaseMapper<ExternalAssignee> {
    @Select("SELECT id, project_id AS projectId, display_name AS displayName FROM external_assignees WHERE project_id = #{projectId} ORDER BY display_name")
    List<ExternalAssignee> selectByProject(@Param("projectId") Long projectId);

    @Select("SELECT id, project_id AS projectId, display_name AS displayName FROM external_assignees WHERE project_id = #{projectId} AND display_name = #{displayName} LIMIT 1")
    ExternalAssignee selectByProjectAndName(@Param("projectId") Long projectId, @Param("displayName") String displayName);

    @Select("""
            <script>
            SELECT id, project_id AS projectId, display_name AS displayName
            FROM external_assignees WHERE project_id = #{projectId} AND id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<ExternalAssignee> selectByProjectAndIds(@Param("projectId") Long projectId, @Param("ids") List<Long> ids);
}
