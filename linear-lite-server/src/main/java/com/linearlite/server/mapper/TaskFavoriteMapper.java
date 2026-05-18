package com.linearlite.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linearlite.server.entity.TaskFavorite;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface TaskFavoriteMapper extends BaseMapper<TaskFavorite> {
    @Select("""
            <script>
            SELECT task_id
            FROM task_favorites
            WHERE user_id = #{userId}
              AND task_id IN
            <foreach collection="taskIds" item="id" open="(" separator="," close=")">
              #{id}
            </foreach>
            </script>
            """)
    List<Long> selectFavoriteTaskIds(@Param("userId") Long userId, @Param("taskIds") List<Long> taskIds);

    @Select("""
            SELECT task_id
            FROM task_favorites
            WHERE user_id = #{userId}
            """)
    List<Long> selectFavoriteTaskIdsByUser(@Param("userId") Long userId);
}
