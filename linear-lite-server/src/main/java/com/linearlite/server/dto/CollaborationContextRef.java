package com.linearlite.server.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/** 文档版本与宿主文件内容版本使用各自来源字段。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CollaborationContextRef(String kind, Long documentId, Long documentVersion,
                                      String projectPath, String contentVersion) { }
