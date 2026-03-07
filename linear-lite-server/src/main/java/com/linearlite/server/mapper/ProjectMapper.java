package com.linearlite.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linearlite.server.entity.Project;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ProjectMapper extends BaseMapper<Project> {
}
