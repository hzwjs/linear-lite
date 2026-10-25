package com.linearlite.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linearlite.server.entity.TaskAttachment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface TaskAttachmentMapper extends BaseMapper<TaskAttachment> {
    @Select("SELECT * FROM task_attachments WHERE id = #{id} AND task_id = #{taskId} FOR UPDATE")
    TaskAttachment selectByIdForUpdate(@Param("id") Long id, @Param("taskId") Long taskId);
}
