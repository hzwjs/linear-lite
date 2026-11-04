package com.linearlite.server.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linearlite.server.entity.RtAuditEvent;
import com.linearlite.server.mapper.RtAuditEventMapper;
import com.linearlite.server.time.BeijingTime;
import org.springframework.stereotype.Service;

/** 远程终端配置变更日志；载荷只放公开字段（指纹、公钥、授权名单），不含私钥材料。 */
@Service
public class RemoteTerminalAuditService {

    private final RtAuditEventMapper mapper;
    private final ObjectMapper objectMapper;

    public RemoteTerminalAuditService(RtAuditEventMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    public void record(Long projectId, Long actorId, String action, String subject, Object before, Object after) {
        RtAuditEvent event = new RtAuditEvent();
        event.setProjectId(projectId);
        event.setActorId(actorId);
        event.setAction(action);
        event.setSubject(subject);
        event.setBeforeJson(json(before));
        event.setAfterJson(json(after));
        event.setCreatedAt(BeijingTime.now());
        mapper.insert(event);
    }

    private String json(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("无法序列化审计载荷", e);
        }
    }
}
