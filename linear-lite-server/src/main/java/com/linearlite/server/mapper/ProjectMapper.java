package com.linearlite.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linearlite.server.entity.Project;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ProjectMapper extends BaseMapper<Project> {
    /** 协作写入按项目、任务或绑定、执行尝试的顺序加锁。 */
    @Select("SELECT id FROM projects WHERE id = #{projectId} FOR UPDATE")
    Long lockProject(@Param("projectId") Long projectId);
}
