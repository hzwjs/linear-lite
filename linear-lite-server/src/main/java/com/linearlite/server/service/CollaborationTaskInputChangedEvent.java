package com.linearlite.server.service;

/** 已持项目锁的任务写入在同一事务中请求输入失效。 */
public record CollaborationTaskInputChangedEvent(Long projectId, Long taskId, Long actorUserId,
                                                  boolean inputChanged, boolean stopRequested) { }
