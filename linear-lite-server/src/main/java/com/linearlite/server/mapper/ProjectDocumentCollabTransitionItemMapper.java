package com.linearlite.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linearlite.server.entity.ProjectDocumentCollabTransitionItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ProjectDocumentCollabTransitionItemMapper
        extends BaseMapper<ProjectDocumentCollabTransitionItem> {
    @Select("""
            SELECT * FROM project_document_collab_transition_items
            WHERE transition_id = #{transitionId} ORDER BY document_id ASC FOR UPDATE
            """)
    java.util.List<ProjectDocumentCollabTransitionItem> selectByTransitionForUpdate(
            @Param("transitionId") String transitionId);

    @Select("SELECT * FROM project_document_collab_transition_items WHERE transition_id = #{transitionId} ORDER BY document_id ASC")
    java.util.List<ProjectDocumentCollabTransitionItem> selectByTransition(
            @Param("transitionId") String transitionId);

    @Update("""
            UPDATE project_document_collab_transition_items
            SET commit_seq = #{commitSeq}, version = #{version}
            WHERE transition_id = #{transitionId} AND document_id = #{documentId}
              AND epoch = #{epoch} AND next_epoch = #{nextEpoch}
              AND commit_seq <= #{commitSeq} AND version <= #{version}
            """)
    int updateDrained(@Param("transitionId") String transitionId, @Param("documentId") Long documentId,
                      @Param("epoch") String epoch, @Param("nextEpoch") String nextEpoch,
                      @Param("commitSeq") Long commitSeq, @Param("version") Long version);
}
