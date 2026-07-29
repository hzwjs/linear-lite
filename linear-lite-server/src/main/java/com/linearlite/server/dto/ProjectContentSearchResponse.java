package com.linearlite.server.dto;

/** 全局搜索的稳定轻量返回合同，不暴露任务或文档的完整正文。 */
public record ProjectContentSearchResponse(
        String contentType,
        String resourceId,
        Long projectId,
        String projectIdentifier,
        String projectName,
        String title,
        String excerpt) {
}
