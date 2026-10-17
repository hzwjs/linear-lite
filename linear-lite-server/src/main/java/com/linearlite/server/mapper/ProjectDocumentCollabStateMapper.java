package com.linearlite.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linearlite.server.entity.ProjectDocumentCollabState;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ProjectDocumentCollabStateMapper extends BaseMapper<ProjectDocumentCollabState> {
    @Select("SELECT * FROM project_document_collab_state WHERE document_id = #{documentId} FOR UPDATE")
    ProjectDocumentCollabState selectByDocumentIdForUpdate(@Param("documentId") Long documentId);

    @Update("""
            UPDATE project_document_collab_state
            SET commit_seq = commit_seq + 1, state_binary = #{state.stateBinary,jdbcType=BLOB}, updated_at = CURRENT_TIMESTAMP
            WHERE document_id = #{documentId} AND epoch = #{epoch} AND commit_seq = #{expectedCommitSeq}
              AND ((transition_id IS NULL AND #{transitionId} IS NULL)
                   OR (transition_id = #{transitionId} AND EXISTS (
                       SELECT 1 FROM project_document_collab_transitions transition
                       WHERE transition.transition_id = #{transitionId} AND transition.status = 'ACTIVE'
                         AND transition.deadline_at > UTC_TIMESTAMP(3))))
            """)
    int updateStateIfMatches(@Param("documentId") Long documentId, @Param("epoch") String epoch,
                             @Param("expectedCommitSeq") Long expectedCommitSeq,
                             @Param("state") ProjectDocumentCollabState state,
                             @Param("transitionId") String transitionId);

    @Update("""
            UPDATE project_document_collab_state
            SET transition_id = #{transitionId}, updated_at = CURRENT_TIMESTAMP
            WHERE document_id = #{documentId} AND transition_id IS NULL
              AND EXISTS (SELECT 1 FROM project_document_collab_transitions transition
                          WHERE transition.transition_id = #{transitionId} AND transition.status = 'ACTIVE')
            """)
    int beginTransitionIfClear(@Param("documentId") Long documentId,
                               @Param("transitionId") String transitionId);

    @Update("""
            UPDATE project_document_collab_state
            SET epoch = #{nextEpoch}, transition_id = NULL, updated_at = CURRENT_TIMESTAMP
            WHERE document_id = #{documentId} AND epoch = #{epoch} AND commit_seq = #{commitSeq}
              AND transition_id = #{transitionId} AND EXISTS (
                  SELECT 1 FROM project_document_collab_transitions transition
                  WHERE transition.transition_id = #{transitionId} AND transition.status = 'ACTIVE'
                    AND transition.deadline_at > UTC_TIMESTAMP(3))
            """)
    int finalizeTransition(@Param("documentId") Long documentId, @Param("epoch") String epoch,
                           @Param("commitSeq") Long commitSeq, @Param("transitionId") String transitionId,
                           @Param("nextEpoch") String nextEpoch);

    @Update("""
            UPDATE project_document_collab_state
            SET transition_id = NULL, updated_at = CURRENT_TIMESTAMP
            WHERE document_id = #{documentId} AND transition_id = #{transitionId}
            """)
    int abortTransition(@Param("documentId") Long documentId, @Param("transitionId") String transitionId);

    @org.apache.ibatis.annotations.Delete("""
            DELETE FROM project_document_collab_state
            WHERE document_id = #{documentId} AND epoch = #{epoch} AND commit_seq = #{commitSeq}
              AND transition_id = #{transitionId} AND EXISTS (
                  SELECT 1 FROM project_document_collab_transitions transition
                  WHERE transition.transition_id = #{transitionId} AND transition.status = 'ACTIVE'
                    AND transition.deadline_at > UTC_TIMESTAMP(3))
            """)
    int deleteForTransition(@Param("documentId") Long documentId, @Param("epoch") String epoch,
                            @Param("commitSeq") Long commitSeq, @Param("transitionId") String transitionId);
}
