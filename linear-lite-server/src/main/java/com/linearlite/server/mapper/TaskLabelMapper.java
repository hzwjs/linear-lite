package com.linearlite.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linearlite.server.dto.TaskLabelAssignment;
import com.linearlite.server.entity.TaskLabel;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface TaskLabelMapper extends BaseMapper<TaskLabel> {
    @Select("""
            <script>
            SELECT
              tl.task_id AS taskId,
              l.id AS id,
              l.name AS name
            FROM task_labels tl
            INNER JOIN labels l ON l.id = tl.label_id
            WHERE tl.task_id IN
            <foreach collection="taskIds" item="id" open="(" separator="," close=")">
              #{id}
            </foreach>
            ORDER BY tl.task_id ASC, l.name ASC
            </script>
            """)
    List<TaskLabelAssignment> selectLabelsForTaskIds(@Param("taskIds") List<Long> taskIds);

    @Select("""
            SELECT
              tl.task_id AS taskId,
              l.id AS id,
              l.name AS name
            FROM labels l
            INNER JOIN task_labels tl ON tl.label_id = l.id
            WHERE l.project_id = #{projectId}
            ORDER BY tl.task_id ASC, l.name ASC
            """)
    List<TaskLabelAssignment> selectLabelsForProject(@Param("projectId") Long projectId);
}
