package com.linearlite.server.dto;

import java.time.LocalDateTime;
import java.util.List;

/** 操作标识由后续生命周期服务在执行记录行锁内去重。 */
public record TaskExecutionCheckpoint(String operationId, String agentInstanceId, LocalDateTime occurredAt,
                                      String finding, List<String> artifactRefs, String blockedReason,
                                      List<CollaborationContextRef> contextRefs,
                                      List<ArtifactObservation> artifactObservations,
                                      String previousContentVersion, String previousPath) { }
