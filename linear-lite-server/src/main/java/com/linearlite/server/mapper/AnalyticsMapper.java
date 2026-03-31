package com.linearlite.server.mapper;

import com.linearlite.server.dto.AssigneeCount;
import com.linearlite.server.dto.PriorityCount;
import com.linearlite.server.dto.StatusCount;
import com.linearlite.server.dto.TaskSnapshotItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AnalyticsMapper {

    // -------- currentSnapshot（不受 from/to 影响）--------

    @Select("SELECT COUNT(*) FROM tasks WHERE project_id = #{projectId}")
    int countAllTasks(@Param("projectId") Long projectId);

    @Select("SELECT status, COUNT(*) AS count FROM tasks WHERE project_id = #{projectId} GROUP BY status ORDER BY count DESC")
    List<StatusCount> selectGlobalStatusBreakdown(@Param("projectId") Long projectId);

    @Select("SELECT COUNT(*) FROM tasks WHERE project_id = #{projectId} AND due_date IS NOT NULL AND due_date < NOW() AND status NOT IN ('done', 'canceled', 'duplicate')")
    int countOverdue(@Param("projectId") Long projectId);

    // -------- 区间内状态分布 --------

    @Select("SELECT status, COUNT(*) AS count FROM tasks WHERE project_id = #{projectId} AND created_at >= #{from} AND created_at < #{to} GROUP BY status ORDER BY count DESC")
    List<StatusCount> selectStatusBreakdown(@Param("projectId") Long projectId, @Param("from") String from, @Param("to") String to);

    // -------- 区间内负责人分布 --------

    @Select("SELECT t.assignee_id AS assigneeId, COALESCE(u.username, '未分配') AS assigneeName, " +
            "COUNT(*) AS totalCount, " +
            "SUM(CASE WHEN t.status IN ('done', 'canceled', 'duplicate') THEN 1 ELSE 0 END) AS completedCount, " +
            "SUM(CASE WHEN t.status = 'in_progress' THEN 1 ELSE 0 END) AS inProgressCount " +
            "FROM tasks t LEFT JOIN users u ON t.assignee_id = u.id " +
            "WHERE t.project_id = #{projectId} AND t.created_at >= #{from} AND t.created_at < #{to} " +
            "GROUP BY t.assignee_id, u.username ORDER BY totalCount DESC")
    List<AssigneeCount> selectAssigneeBreakdown(@Param("projectId") Long projectId, @Param("from") String from, @Param("to") String to);

    // -------- 区间内优先级分布 --------

    @Select("SELECT priority, COUNT(*) AS count FROM tasks WHERE project_id = #{projectId} AND created_at >= #{from} AND created_at < #{to} GROUP BY priority ORDER BY count DESC")
    List<PriorityCount> selectPriorityBreakdown(@Param("projectId") Long projectId, @Param("from") String from, @Param("to") String to);

    // -------- 任务明细分页 --------

    @Select("SELECT t.task_key AS taskKey, t.title, t.status, t.priority, t.assignee_id AS assigneeId, " +
            "COALESCE(u.username, '') AS assigneeName, t.created_at AS createdAt, t.completed_at AS completedAt, t.due_date AS dueDate " +
            "FROM tasks t LEFT JOIN users u ON t.assignee_id = u.id " +
            "WHERE t.project_id = #{projectId} AND t.created_at >= #{from} AND t.created_at < #{to} " +
            "ORDER BY t.created_at DESC LIMIT #{limit} OFFSET #{offset}")
    List<TaskSnapshotItem> selectTaskSnapshot(@Param("projectId") Long projectId,
                                              @Param("from") String from,
                                              @Param("to") String to,
                                              @Param("limit") int limit,
                                              @Param("offset") int offset);

    @Select("SELECT COUNT(*) FROM tasks WHERE project_id = #{projectId} AND created_at >= #{from} AND created_at < #{to}")
    int countTaskSnapshot(@Param("projectId") Long projectId, @Param("from") String from, @Param("to") String to);

    @Select("SELECT t.task_key AS taskKey, t.title, t.status, t.priority, t.assignee_id AS assigneeId, " +
            "COALESCE(u.username, '') AS assigneeName, t.created_at AS createdAt, t.completed_at AS completedAt, t.due_date AS dueDate " +
            "FROM tasks t LEFT JOIN users u ON t.assignee_id = u.id " +
            "WHERE t.project_id = #{projectId} AND t.completed_at IS NOT NULL " +
            "AND t.completed_at >= #{from} AND t.completed_at < #{to} " +
            "ORDER BY t.completed_at DESC LIMIT #{limit} OFFSET #{offset}")
    List<TaskSnapshotItem> selectTaskSnapshotByCompleted(@Param("projectId") Long projectId,
                                                        @Param("from") String from,
                                                        @Param("to") String to,
                                                        @Param("limit") int limit,
                                                        @Param("offset") int offset);

    @Select("SELECT COUNT(*) FROM tasks WHERE project_id = #{projectId} AND completed_at IS NOT NULL " +
            "AND completed_at >= #{from} AND completed_at < #{to}")
    int countTaskSnapshotByCompleted(@Param("projectId") Long projectId, @Param("from") String from, @Param("to") String to);

    @Select("SELECT t.task_key AS taskKey, t.title, t.status, t.priority, t.assignee_id AS assigneeId, " +
            "COALESCE(u.username, '') AS assigneeName, t.created_at AS createdAt, t.completed_at AS completedAt, t.due_date AS dueDate " +
            "FROM tasks t LEFT JOIN users u ON t.assignee_id = u.id " +
            "WHERE t.project_id = #{projectId} AND t.due_date IS NOT NULL " +
            "AND t.due_date >= #{from} AND t.due_date < #{to} " +
            "ORDER BY t.due_date DESC, t.created_at DESC LIMIT #{limit} OFFSET #{offset}")
    List<TaskSnapshotItem> selectTaskSnapshotByDue(@Param("projectId") Long projectId,
                                                   @Param("from") String from,
                                                   @Param("to") String to,
                                                   @Param("limit") int limit,
                                                   @Param("offset") int offset);

    @Select("SELECT COUNT(*) FROM tasks WHERE project_id = #{projectId} AND due_date IS NOT NULL " +
            "AND due_date >= #{from} AND due_date < #{to}")
    int countTaskSnapshotByDue(@Param("projectId") Long projectId, @Param("from") String from, @Param("to") String to);

    // -------- 趋势：created count per bucket --------

    @Select("SELECT DATE(created_at) AS bucketStart, COUNT(*) AS createdCount FROM tasks " +
            "WHERE project_id = #{projectId} AND created_at >= #{from} AND created_at < #{to} " +
            "GROUP BY DATE(created_at) ORDER BY bucketStart")
    List<java.util.Map<String, Object>> selectDailyCreatedCounts(@Param("projectId") Long projectId, @Param("from") String from, @Param("to") String to);

    @Select("SELECT DATE(completed_at) AS bucketStart, COUNT(*) AS completedCount FROM tasks " +
            "WHERE project_id = #{projectId} AND completed_at >= #{from} AND completed_at < #{to} " +
            "GROUP BY DATE(completed_at) ORDER BY bucketStart")
    List<java.util.Map<String, Object>> selectDailyCompletedCounts(@Param("projectId") Long projectId, @Param("from") String from, @Param("to") String to);

    @Select("SELECT DATE(due_date) AS bucketStart, COUNT(*) AS dueCount FROM tasks " +
            "WHERE project_id = #{projectId} AND due_date >= #{from} AND due_date < #{to} " +
            "GROUP BY DATE(due_date) ORDER BY bucketStart")
    List<java.util.Map<String, Object>> selectDailyDueCounts(@Param("projectId") Long projectId, @Param("from") String from, @Param("to") String to);
}
