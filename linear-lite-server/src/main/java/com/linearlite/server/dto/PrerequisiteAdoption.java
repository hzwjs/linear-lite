package com.linearlite.server.dto;

import java.util.List;

/** 交付前对本次采用事实的内容复核。 */
public record PrerequisiteAdoption(String reviewOperationId, List<ArtifactObservation> observations) { }
