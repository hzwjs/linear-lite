package com.linearlite.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linearlite.server.entity.ProjectDocumentCollabTransition;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import java.util.List;

@Mapper
public interface ProjectDocumentCollabTransitionMapper extends BaseMapper<ProjectDocumentCollabTransition> {
    @Insert("""
            INSERT INTO project_document_collab_transitions
                (transition_id, project_id, root_document_id, operation, status, deadline_at)
            VALUES (#{transitionId}, #{projectId}, #{rootDocumentId}, #{operation}, 'ACTIVE',
                    UTC_TIMESTAMP(3) + INTERVAL 120 SECOND)
            """)
    int insertActive(@Param("transitionId") String transitionId, @Param("projectId") Long projectId,
                     @Param("rootDocumentId") Long rootDocumentId, @Param("operation") String operation);

    @Select("""
            SELECT transition_id, project_id, root_document_id, operation, status, deadline_at, created_at, updated_at
            FROM project_document_collab_transitions WHERE transition_id = #{transitionId} FOR UPDATE
            """)
    ProjectDocumentCollabTransition selectByIdForUpdate(@Param("transitionId") String transitionId);

    @Update("""
            UPDATE project_document_collab_transitions
            SET deadline_at = UTC_TIMESTAMP(3) + INTERVAL 120 SECOND, updated_at = CURRENT_TIMESTAMP(3)
            WHERE transition_id = #{transitionId} AND status = 'ACTIVE'
              AND deadline_at > UTC_TIMESTAMP(3)
            """)
    int renewActive(@Param("transitionId") String transitionId);

    @Select("""
            SELECT CAST(UNIX_TIMESTAMP(deadline_at) * 1000 AS UNSIGNED)
            FROM project_document_collab_transitions WHERE transition_id = #{transitionId}
            """)
    Long selectDeadlineEpochMs(@Param("transitionId") String transitionId);

    @Update("""
            UPDATE project_document_collab_transitions
            SET status = #{status}, updated_at = CURRENT_TIMESTAMP(3)
            WHERE transition_id = #{transitionId} AND status = 'ACTIVE'
              AND (#{status} <> 'FINALIZED' OR deadline_at > UTC_TIMESTAMP(3))
            """)
    int finishIfActive(@Param("transitionId") String transitionId, @Param("status") String status);

    @Select("""
            SELECT status FROM project_document_collab_transitions WHERE transition_id = #{transitionId}
            """)
    String selectStatus(@Param("transitionId") String transitionId);

    @Select("""
            SELECT status = 'ACTIVE' AND deadline_at > UTC_TIMESTAMP(3)
            FROM project_document_collab_transitions WHERE transition_id = #{transitionId}
            """)
    Boolean selectActiveAndUnexpired(@Param("transitionId") String transitionId);

    @Select("""
            SELECT status = 'ACTIVE' AND deadline_at <= UTC_TIMESTAMP(3)
            FROM project_document_collab_transitions WHERE transition_id = #{transitionId}
            """)
    Boolean selectActiveAndExpired(@Param("transitionId") String transitionId);

    @Select("""
            SELECT transition_id FROM project_document_collab_transitions
            WHERE status = 'ACTIVE' AND deadline_at <= UTC_TIMESTAMP(3)
            ORDER BY deadline_at ASC, transition_id ASC LIMIT 100
            """)
    List<String> selectExpiredTransitionIds();
}
