package com.linearlite.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linearlite.server.dto.TaskListItemResponse;
import com.linearlite.server.dto.TaskSubIssueCount;
import com.linearlite.server.dto.DirectChildCompletion;
import com.linearlite.server.entity.Task;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface TaskMapper extends BaseMapper<Task> {
    @Select("""
            SELECT *
            FROM tasks
            WHERE id = #{id}
            FOR UPDATE
            """)
    Task selectByIdForUpdate(@Param("id") Long id);

    @Select("""
            SELECT
              COUNT(*) AS totalCount,
              COALESCE(SUM(CASE WHEN LOWER(status) IN ('done', 'canceled', 'duplicate') THEN 1 ELSE 0 END), 0) AS terminalCount
            FROM tasks
            WHERE parent_id = #{parentId}
            """)
    DirectChildCompletion selectDirectChildCompletion(@Param("parentId") Long parentId);

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
            JOIN tasks t
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
            JOIN tasks t
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
              SUM(CASE WHEN LOWER(status) IN ('done', 'canceled', 'duplicate') THEN 1 ELSE 0 END) AS completedCount
            FROM tasks
            WHERE parent_id IN
            <foreach collection="parentIds" item="id" open="(" separator="," close=")">
              #{id}
            </foreach>
            GROUP BY parent_id
            </script>
            """)
    List<TaskSubIssueCount> selectSubIssueCounts(@Param("parentIds") List<Long> parentIds);

    @Select("""
            <script>
            SELECT
              t.id AS taskId,
              t.task_key AS taskKey,
              t.title AS title,
              t.status AS status,
              t.priority AS priority,
              t.project_id AS projectId,
              p.name AS projectName,
              t.assignee_id AS assigneeId,
              u.username AS assigneeUsername,
              u.email AS assigneeEmail,
              t.progress_percent AS progressPercent,
              t.due_date AS dueDate,
              t.completed_at AS completedAt
            FROM tasks t
            JOIN projects p ON p.id = t.project_id
            JOIN users u ON u.id = t.assignee_id
            WHERE t.project_id IN
            <foreach collection="projectIds" item="pid" open="(" separator="," close=")">
              #{pid}
            </foreach>
              AND (
                (t.due_date IS NOT NULL
                 AND t.due_date &lt; #{endOfToday}
                 -- 待规划任务不属于今日汇总，即使已设置截止日期也不纳入。
                 AND LOWER(t.status) NOT IN ('backlog', 'done', 'canceled', 'duplicate'))
                OR (LOWER(t.status) = 'done'
                    AND t.completed_at &gt;= #{completedWindowStart}
                    AND t.completed_at &lt; #{completedWindowEnd})
              )
            ORDER BY t.completed_at DESC, t.due_date ASC, t.id ASC
            </script>
            """)
    List<com.linearlite.server.dto.DailySummaryTaskDto> selectDueForDigest(
            @Param("projectIds") List<Long> projectIds,
            @Param("startOfToday") java.time.LocalDateTime startOfToday,
            @Param("endOfToday") java.time.LocalDateTime endOfToday,
            @Param("completedWindowStart") java.time.LocalDateTime completedWindowStart,
            @Param("completedWindowEnd") java.time.LocalDateTime completedWindowEnd);
}
