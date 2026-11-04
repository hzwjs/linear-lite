package com.linearlite.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linearlite.server.entity.RtAuditEvent;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface RtAuditEventMapper extends BaseMapper<RtAuditEvent> {
}
