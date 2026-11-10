package com.linearlite.server.service;

/** 在项目锁下检查作为父节点的任务是否具备协作叶子语义。 */
public record CollaborationParentAssignedEvent(Long projectId, Long parentTaskId) { }
