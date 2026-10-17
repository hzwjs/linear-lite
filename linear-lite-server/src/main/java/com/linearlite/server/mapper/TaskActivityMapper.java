package com.linearlite.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linearlite.server.dto.TaskChangeRecord;
import com.linearlite.server.entity.TaskActivity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface TaskActivityMapper extends BaseMapper<TaskActivity> {

    @Select("""
            <script>
            SELECT
              a.id AS id,
              a.task_id AS taskId,
              t.task_key AS taskKey,
              a.field_name AS field,
              a.old_value AS oldValue,
              a.new_value AS newValue,
              a.created_at AS changedAt,
              a.user_id AS actorId,
              u.username AS actorDisplayName
            FROM task_activities a
            JOIN tasks t ON t.id = a.task_id
            LEFT JOIN users u ON u.id = a.user_id
            WHERE t.project_id = #{projectId}
              AND a.action_type = 'changed'
              AND a.created_at &gt;= #{from}
              AND a.created_at &lt; #{to}
              AND a.field_name IN
              <foreach collection="fields" item="field" open="(" separator="," close=")">
                #{field}
              </foreach>
              <if test="afterId != null">
                AND a.id &gt; #{afterId}
              </if>
            ORDER BY a.id ASC
            LIMIT #{fetchLimit}
            </script>
            """)
    List<TaskChangeRecord> selectMcpProjectTaskChanges(
            @Param("projectId") Long projectId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("fields") List<String> fields,
            @Param("afterId") Long afterId,
            @Param("fetchLimit") int fetchLimit);
}
