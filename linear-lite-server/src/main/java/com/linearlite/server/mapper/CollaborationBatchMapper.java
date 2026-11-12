package com.linearlite.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linearlite.server.entity.CollaborationBatch;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface CollaborationBatchMapper extends BaseMapper<CollaborationBatch> {
    @Select("SELECT * FROM collaboration_batches WHERE batch_id = #{batchId} FOR UPDATE")
    CollaborationBatch selectForUpdate(@Param("batchId") String batchId);

    @Insert("INSERT INTO collaboration_batch_tasks (batch_id, task_id) VALUES (#{batchId}, #{taskId})")
    int insertTask(@Param("batchId") String batchId, @Param("taskId") Long taskId);

    @Select("SELECT task_id FROM collaboration_batch_tasks WHERE batch_id = #{batchId} ORDER BY task_id")
    List<Long> selectTaskIds(@Param("batchId") String batchId);

    @Select("SELECT COUNT(*) FROM collaboration_batch_tasks WHERE batch_id = #{batchId}")
    long countTasks(@Param("batchId") String batchId);

    @Update("UPDATE collaboration_batches SET max_attempts = max_attempts + #{delta} WHERE batch_id = #{batchId}")
    int addAttemptBudget(@Param("batchId") String batchId, @Param("delta") int delta);

    @Select("SELECT COUNT(*) FROM task_execution_attempts WHERE batch_id = #{batchId}")
    long countAttempts(@Param("batchId") String batchId);

    @Select("SELECT COUNT(*) FROM task_execution_attempts WHERE batch_id = #{batchId} AND stop_confirmed_at IS NULL")
    long countUnconfirmedAttempts(@Param("batchId") String batchId);

    @Select("""
            SELECT DISTINCT project_id FROM collaboration_batches
            WHERE host_authorization_id = #{grantId} AND desired_intent = 'RUN'
              AND lifecycle IN ('queued', 'accepted') ORDER BY project_id
            """)
    List<Long> selectRunningProjectsForGrant(@Param("grantId") Long grantId);

    @Select("""
            SELECT b.* FROM collaboration_batches b
            JOIN project_device_bindings d ON d.id = b.binding_id AND d.user_id = b.user_id
              AND d.project_id = b.project_id AND d.revision = b.binding_revision AND d.deleted_at IS NULL
            JOIN host_device_authorizations h ON h.id = b.host_authorization_id AND h.user_id = b.user_id
              AND h.device_id = d.device_id AND h.revoked_at IS NULL
            JOIN project_members m ON m.project_id = b.project_id AND m.user_id = b.user_id
            WHERE b.user_id = #{userId} AND b.host_authorization_id = #{grantId}
              AND b.lifecycle = 'queued' AND b.desired_intent = 'RUN'
            ORDER BY b.created_at, b.batch_id LIMIT 100
            """)
    List<CollaborationBatch> selectPendingForHost(@Param("userId") Long userId, @Param("grantId") Long grantId);

    @Update("""
            UPDATE collaboration_batches
            SET desired_intent = 'STOP', intent_revision = intent_revision + 1,
                ended_at = CASE WHEN lifecycle = 'queued' THEN #{now} ELSE ended_at END,
                end_reason = COALESCE(end_reason, 'host_authorization_revoked'),
                lifecycle = CASE WHEN lifecycle = 'queued' THEN 'ended' ELSE lifecycle END
            WHERE project_id = #{projectId} AND host_authorization_id = #{grantId}
              AND desired_intent = 'RUN' AND lifecycle IN ('queued', 'accepted')
            """)
    int requestStopForGrant(@Param("projectId") Long projectId, @Param("grantId") Long grantId,
                            @Param("now") LocalDateTime now);

    @Update("""
            UPDATE collaboration_batches SET run_lease_until = #{leaseUntil}
            WHERE batch_id = #{batchId} AND lifecycle = 'accepted' AND desired_intent = 'RUN'
              AND host_instance_id = #{hostInstanceId} AND run_epoch = #{runEpoch}
              AND run_lease_until > #{now} AND deadline_at > #{now}
            """)
    int renewRunLease(@Param("batchId") String batchId, @Param("hostInstanceId") String hostInstanceId,
                      @Param("runEpoch") Long runEpoch, @Param("now") LocalDateTime now,
                      @Param("leaseUntil") LocalDateTime leaseUntil);

    @Update("""
            UPDATE collaboration_batches
            SET intent_revision = intent_revision + CASE WHEN desired_intent = 'RUN' THEN 1 ELSE 0 END,
                end_reason = CASE WHEN desired_intent = 'STOP' THEN COALESCE(end_reason, 'user_stopped') ELSE #{reason} END,
                desired_intent = 'STOP', ended_at = #{now}, lifecycle = 'ended'
            WHERE batch_id = #{batchId} AND lifecycle = 'accepted'
              AND host_instance_id = #{hostInstanceId} AND run_epoch = #{runEpoch}
            """)
    int finish(@Param("batchId") String batchId, @Param("hostInstanceId") String hostInstanceId,
               @Param("runEpoch") Long runEpoch, @Param("reason") String reason, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE collaboration_batches
            SET desired_intent = 'STOP', intent_revision = intent_revision + 1,
                end_reason = 'host_stopped', ended_at = #{now}, lifecycle = 'ended'
            WHERE batch_id = #{batchId} AND lifecycle = 'queued' AND run_epoch = #{runEpoch}
            """)
    int finishQueuedAcceptance(@Param("batchId") String batchId, @Param("runEpoch") Long runEpoch,
                               @Param("now") LocalDateTime now);

    @Select("""
            <script>
            SELECT * FROM collaboration_batches
            WHERE project_id = #{projectId} AND user_id = #{userId}
            <if test="beforeCreatedAt != null">
              AND (created_at &lt; #{beforeCreatedAt}
                   OR (created_at = #{beforeCreatedAt} AND batch_id &lt; #{beforeBatchId}))
            </if>
            ORDER BY created_at DESC, batch_id DESC LIMIT #{limit}
            </script>
            """)
    List<CollaborationBatch> selectPage(@Param("projectId") Long projectId, @Param("userId") Long userId,
                                         @Param("beforeCreatedAt") LocalDateTime beforeCreatedAt,
                                         @Param("beforeBatchId") String beforeBatchId, @Param("limit") int limit);

    @Update("""
            UPDATE collaboration_batches
            SET desired_intent = 'STOP', intent_revision = intent_revision + 1,
                ended_at = CASE WHEN lifecycle = 'queued' THEN #{now} ELSE ended_at END,
                end_reason = COALESCE(end_reason, 'user_stopped'),
                lifecycle = CASE WHEN lifecycle = 'queued' THEN 'ended' ELSE lifecycle END
            WHERE batch_id = #{batchId} AND desired_intent = 'RUN' AND lifecycle IN ('queued', 'accepted')
            """)
    int requestStop(@Param("batchId") String batchId, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE collaboration_batches
            SET desired_intent = 'STOP', intent_revision = intent_revision + 1,
                ended_at = CASE WHEN lifecycle = 'queued' THEN #{now} ELSE ended_at END,
                end_reason = COALESCE(end_reason, 'binding_changed'),
                lifecycle = CASE WHEN lifecycle = 'queued' THEN 'ended' ELSE lifecycle END
            WHERE binding_id = #{bindingId} AND binding_revision = #{revision}
              AND desired_intent = 'RUN' AND lifecycle IN ('queued', 'accepted')
            """)
    int requestStopForBinding(@Param("bindingId") Long bindingId, @Param("revision") Long revision,
                              @Param("now") LocalDateTime now);

    @Update("""
            UPDATE collaboration_batches
            SET desired_intent = 'STOP', intent_revision = intent_revision + 1,
                ended_at = CASE WHEN lifecycle = 'queued' THEN #{now} ELSE ended_at END,
                end_reason = COALESCE(end_reason, 'project_deleted'),
                lifecycle = CASE WHEN lifecycle = 'queued' THEN 'ended' ELSE lifecycle END
            WHERE project_id = #{projectId} AND desired_intent = 'RUN' AND lifecycle IN ('queued', 'accepted')
            """)
    int requestProjectStop(@Param("projectId") Long projectId, @Param("now") LocalDateTime now);

    @Select("SELECT COUNT(*) FROM collaboration_batches WHERE project_id = #{projectId} AND lifecycle = 'accepted'")
    long countAcceptedByProject(@Param("projectId") Long projectId);

    @Select("SELECT COUNT(*) FROM collaboration_batches WHERE binding_id = #{bindingId} AND lifecycle = 'accepted'")
    long countAcceptedByBinding(@Param("bindingId") Long bindingId);

    @Delete("""
            DELETE t FROM collaboration_batch_tasks t
            JOIN collaboration_batches b ON b.batch_id = t.batch_id WHERE b.project_id = #{projectId}
            """)
    int deleteTasksByProject(@Param("projectId") Long projectId);

    @Delete("DELETE FROM collaboration_batches WHERE project_id = #{projectId}")
    int deleteByProject(@Param("projectId") Long projectId);

    @Update("""
            UPDATE collaboration_batches
            SET lifecycle = 'accepted', host_instance_id = #{hostInstanceId}, run_epoch = run_epoch + 1,
                run_lease_until = #{leaseUntil}, started_at = #{startedAt}, deadline_at = #{deadlineAt}
            WHERE batch_id = #{batchId} AND lifecycle = 'queued' AND desired_intent = 'RUN'
            """)
    int acceptQueued(@Param("batchId") String batchId, @Param("hostInstanceId") String hostInstanceId,
                     @Param("leaseUntil") LocalDateTime leaseUntil, @Param("startedAt") LocalDateTime startedAt,
                     @Param("deadlineAt") LocalDateTime deadlineAt);
}
