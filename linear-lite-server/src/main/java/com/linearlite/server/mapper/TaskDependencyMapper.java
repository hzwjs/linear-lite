package com.linearlite.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linearlite.server.entity.TaskDependency;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface TaskDependencyMapper extends BaseMapper<TaskDependency> {
    @Select("SELECT * FROM task_dependencies WHERE task_id = #{taskId} ORDER BY id")
    List<TaskDependency> selectByTaskId(@Param("taskId") Long taskId);

    @Select("SELECT * FROM task_dependencies WHERE prerequisite_task_id = #{taskId} ORDER BY id")
    List<TaskDependency> selectByPrerequisiteTaskId(@Param("taskId") Long taskId);

    @Select("""
            SELECT EXISTS (
              SELECT 1 FROM tasks t WHERE t.id = #{taskId} AND (
                EXISTS (SELECT 1 FROM collaboration_batch_tasks bt JOIN collaboration_batches b ON b.batch_id = bt.batch_id WHERE bt.task_id = t.id)
                OR EXISTS (SELECT 1 FROM task_dependencies d WHERE d.task_id = t.id OR d.prerequisite_task_id = t.id)
                OR EXISTS (SELECT 1 FROM task_execution_attempts a WHERE a.task_id = t.id)
              )
            )
            """)
    boolean hasCollaborationParticipation(@Param("taskId") Long taskId);
}
