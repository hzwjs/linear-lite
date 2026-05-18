package com.linearlite.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linearlite.server.dto.TaskListItemResponse;
import com.linearlite.server.dto.TaskSubIssueCount;
import com.linearlite.server.entity.Task;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface TaskMapper extends BaseMapper<Task> {
    @Select("""
            <script>
            SELECT
              t.id,
              t.task_key,
              t.title,
              t.status,
              t.priority,
              t.project_id,
              t.parent_id,
              t.creator_id,
              t.assignee_id,
              t.assignee_display_name,
              t.due_date,
              t.planned_start_date,
              t.progress_percent,
              t.completed_at,
              t.created_at,
              t.updated_at
            FROM project_members pm
            LEFT JOIN tasks t
              ON t.project_id = pm.project_id
            <if test="parentId != null">
              AND t.parent_id = #{parentId}
            </if>
            <if test="parentId == null and topLevelOnly == true">
              AND t.parent_id IS NULL
            </if>
            WHERE pm.project_id = #{projectId}
              AND pm.user_id = #{userId}
            ORDER BY t.id ASC
            </script>
            """)
    List<Task> selectListItems(
            @Param("projectId") Long projectId,
            @Param("topLevelOnly") Boolean topLevelOnly,
            @Param("parentId") Long parentId,
            @Param("userId") Long userId);

    @Select("""
            <script>
            SELECT
              t.id,
              t.task_key,
              t.title,
              t.status,
              t.priority,
              t.project_id,
              t.parent_id,
              t.creator_id,
              t.assignee_id,
              t.assignee_display_name,
              t.due_date,
              t.planned_start_date,
              t.progress_percent,
              t.completed_at,
              t.created_at,
              t.updated_at
            FROM project_members pm
            LEFT JOIN tasks t
              ON t.project_id = pm.project_id
            <if test="parentId != null">
              AND t.parent_id = #{parentId}
            </if>
            <if test="parentId == null and topLevelOnly == true">
              AND t.parent_id IS NULL
            </if>
            WHERE pm.project_id = #{projectId}
              AND pm.user_id = #{userId}
            ORDER BY t.id ASC
            </script>
            """)
    List<TaskListItemResponse> selectListItemResponses(
            @Param("projectId") Long projectId,
            @Param("topLevelOnly") Boolean topLevelOnly,
            @Param("parentId") Long parentId,
            @Param("userId") Long userId);

    @Select("""
            <script>
            SELECT
              parent_id AS parentId,
              COUNT(*) AS totalCount,
              SUM(CASE WHEN LOWER(status) IN ('done', 'canceled') THEN 1 ELSE 0 END) AS completedCount
            FROM tasks
            WHERE parent_id IN
            <foreach collection="parentIds" item="id" open="(" separator="," close=")">
              #{id}
            </foreach>
            GROUP BY parent_id
            </script>
            """)
    List<TaskSubIssueCount> selectSubIssueCounts(@Param("parentIds") List<Long> parentIds);
}
