package com.linearlite.server.dto;

/** 宿主对项目产物的读取事实；不存在的文件不携带内容版本。 */
public record ArtifactObservation(String path, String state, String contentVersion) { }
