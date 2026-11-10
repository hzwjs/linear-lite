package com.linearlite.server.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linearlite.server.entity.TaskExecutionOperation;
import com.linearlite.server.exception.ConflictOperationException;
import com.linearlite.server.mapper.TaskExecutionOperationMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 调用方持有项目协调锁；请求与响应按显式业务字段构造，排除授权材料。 */
@Service
public class TaskExecutionOperationService {
    private final TaskExecutionOperationMapper mapper;
    private final ObjectMapper objectMapper;

    public TaskExecutionOperationService(TaskExecutionOperationMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public JsonNode findReplay(Long projectId, Long userId, String operationId, String kind, JsonNode request) {
        requireKey(projectId, userId, operationId, kind, request);
        TaskExecutionOperation previous = mapper.selectForUpdate(projectId, userId, operationId);
        if (previous == null) return null;
        // JSON 数据库往返不保留 IntNode/LongNode 的 Java 类型差异。
        if (!kind.equals(previous.getKind()) || !read(write(request)).equals(read(previous.getRequestJson()))) {
            throw new ConflictOperationException("operationId 对应不同的执行请求");
        }
        return read(previous.getResponseJson());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void save(Long projectId, Long userId, String operationId, String kind, Long attemptId,
                     JsonNode request, JsonNode response) {
        requireKey(projectId, userId, operationId, kind, request);
        if (response == null || response.isNull()) throw new IllegalArgumentException("执行操作结果不能为空");
        TaskExecutionOperation operation = new TaskExecutionOperation();
        operation.setProjectId(projectId);
        operation.setUserId(userId);
        operation.setOperationId(operationId);
        operation.setKind(kind);
        operation.setAttemptId(attemptId);
        operation.setRequestJson(write(request));
        operation.setResponseJson(write(response));
        if (mapper.insert(operation) != 1) throw new IllegalStateException("执行操作回执保存失败");
    }

    private void requireKey(Long projectId, Long userId, String operationId, String kind, JsonNode request) {
        if (projectId == null || projectId <= 0 || userId == null || userId <= 0
                || operationId == null || operationId.isBlank() || operationId.length() > 128
                || kind == null || kind.isBlank() || kind.length() > 32 || request == null || !request.isObject()) {
            throw new IllegalArgumentException("执行操作标识或请求无效");
        }
    }

    private String write(JsonNode value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException("执行操作回执序列化失败"); }
    }

    private JsonNode read(String value) {
        try { return objectMapper.readTree(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException("执行操作回执解析失败"); }
    }
}
