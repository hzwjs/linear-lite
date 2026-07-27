package com.linearlite.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linearlite.server.entity.TaskSemanticIndexJob;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface TaskSemanticIndexJobMapper extends BaseMapper<TaskSemanticIndexJob> {
    @Insert("""
            INSERT INTO task_semantic_index_jobs (task_id, operation, content_hash, run_after, version, attempts)
            VALUES (#{taskId}, #{operation}, #{contentHash}, #{runAfter}, 1, 0)
            ON DUPLICATE KEY UPDATE
              operation = VALUES(operation), content_hash = VALUES(content_hash), run_after = VALUES(run_after),
              version = version + 1, attempts = 0
            """)
    int upsert(@Param("taskId") Long taskId, @Param("operation") String operation,
               @Param("contentHash") String contentHash, @Param("runAfter") LocalDateTime runAfter);

    @Select("SELECT task_id, operation, content_hash, run_after, version, attempts FROM task_semantic_index_jobs "
            + "WHERE run_after <= #{now} ORDER BY run_after ASC LIMIT #{limit}")
    List<TaskSemanticIndexJob> selectDue(@Param("now") LocalDateTime now, @Param("limit") int limit);

    @Delete("DELETE FROM task_semantic_index_jobs WHERE task_id = #{taskId} AND version = #{version}")
    int deleteIfVersion(@Param("taskId") Long taskId, @Param("version") Long version);

    @Update("UPDATE task_semantic_index_jobs SET attempts = attempts + 1, run_after = #{runAfter} "
            + "WHERE task_id = #{taskId} AND version = #{version}")
    int rescheduleIfVersion(@Param("taskId") Long taskId, @Param("version") Long version,
                            @Param("runAfter") LocalDateTime runAfter);
}
