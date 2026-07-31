package com.linearlite.server.dto;

import java.time.LocalDateTime;

/** webhookToken 仅在创建或重置时返回，普通列表永不回传。 */
public record GitLabRepositoryResponse(
        Long id,
        String repositoryUrl,
        String repositoryPath,
        String webhookToken,
        LocalDateTime createdAt) {
}
