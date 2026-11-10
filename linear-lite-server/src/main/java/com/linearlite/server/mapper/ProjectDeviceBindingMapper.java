package com.linearlite.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linearlite.server.entity.ProjectDeviceBinding;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ProjectDeviceBindingMapper extends BaseMapper<ProjectDeviceBinding> {
    @Select("""
            SELECT * FROM project_device_bindings
            WHERE user_id = #{userId} AND project_id = #{projectId} AND device_id = #{deviceId}
            FOR UPDATE
            """)
    ProjectDeviceBinding selectForUpdate(@Param("userId") Long userId,
                                         @Param("projectId") Long projectId,
                                         @Param("deviceId") String deviceId);
}
