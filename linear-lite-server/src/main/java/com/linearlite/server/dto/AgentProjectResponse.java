package com.linearlite.server.dto;

/** Bridge 配置页可选择的项目；项目 ID 只作为内部稳定标识返回。 */
public record AgentProjectResponse(Long projectId, String projectName) {
}
