package com.linearlite.server.dto;

import java.util.List;

/** 前置交付在绑定设备的当前读取事实。 */
public record PrerequisiteSourceObservation(Long sourceAttemptId, Long sourceInputRevision,
                                            List<ArtifactObservation> observations) { }
